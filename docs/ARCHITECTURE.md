# Architecture

How Ping moves locations in each direction, which iOS 15 Find My internals the tweak relies on,
and where each piece keeps its state.

## Why a relay iPhone is needed

Apple only gives the **Share My Location** (`meDevice`) role to iOS and iPadOS devices. A Mac-class
client can *view* friends' locations: OpenBubbles / `rustpush`, for example, authenticates with
`fmfmobile.icloud.com` as `deviceClass: "Mac"`. But Apple's servers keep
`serverContext.sendMyLocation: false` for Mac UDIDs and ignore `myLocationChanged` writes from Mac
sessions. So "my location" has to come from a real iOS device.

The relay iPhone is signed into your Apple Account and set as **Use This iPhone as My Location**. The
tweak on it replaces the location iOS reports with the Android phone's, so Apple takes the Android
position for the iPhone's own.

## Components

```
 Android: Ping                              ntfy relay                 iPhone: Ping Relay (iOS 15)
 ─────────────                              ──────────                 ───────────────────────────────
 LocationService (foreground)                                          SpringBoard: relay hub
   LocationManager → LocationValidator                                   streams …-up, stores fix
   RelayClient ── POST ─────────────►  fmb-<channel>-up  ──stream──►     publishes …-down
               ◄── stream ───────────  fmb-<channel>-down ◄── POST ──    LAN server :8765
               ── POST /v1/sync, /v1/stream (LAN/VPN) ──────────────►
 BridgeRepository (DataStore, flows)                                   fmfd, FindMy: inject fix
 Compose UI: Friends · Me · Settings                                   findmydeviced, searchpartyd:
                                                                         capture friends, fetch on demand
                                                                       Preferences: settings pane
```

Ping is one Android app module; see [`android/README.md`](../android/README.md). The tweak is a
single dylib, [`ios-tweak/Tweak.m`](../ios-tweak/Tweak.m). Its constructor checks the process name
and takes on a different role in each process:

| Process | Role in the tweak |
|---|---|
| **SpringBoard** | **Relay hub.** Streams the uplink topic, decrypts and validates fixes, writes them to the state file, publishes downlinks, and runs the LAN HTTP server on TCP 8765. Always running, so the bridge doesn't depend on any app being open. |
| **`fmfd`** (Find My Friends daemon) | Injects the Android fix into CoreLocation. Captures the `fmfmobile` friends list and any decrypted friend locations. |
| **`FindMy`** (`com.apple.findmy`) | Same as `fmfd`, plus the floating **Ping Relay** button that opens the settings pane. |
| **`findmydeviced`** | Captures friends. Injects the fix only when **Also Replace Device Location** is on. Runs the on-demand friend fetch. |
| **`searchpartyd`** | Captures friends (it decrypts secure locations). Injects the fix only when **Also Replace Device Location** is on. |
| **Preferences** | Hosts the settings pane through PreferenceLoader. The same dylib is installed as the preference bundle. |

[`PingRelay.plist`](../ios-tweak/PingRelay.plist) also lists the legacy Find My Friends
bundle (`com.apple.mobileme.fmf1`); the constructor does nothing there.

### How the processes talk on the iPhone

The processes don't share memory. They coordinate through JSON files in the jailbreak's preferences
folder and Darwin notifications (`notify_post`):

| Notification (`uk.maynotfunction.pingrelay.…`) | Posted by | Handled by |
|---|---|---|
| `location_updated` | Hub after storing a fix; settings pane | Find My processes re-deliver the fix to active `CLLocationManager` delegates |
| `friends_updated` | Any process after `friends.json` changes; settings pane | Hub publishes `friends_state` (unchanged lists at most every 5 min) |
| `locate_needed` | Find My processes when something calls `requestLocation` | Hub publishes a `locate_request` to Android |
| `live_changed` | Find My processes when a live session starts, ends, or is refreshed (every 30 s); each keeps the time in the notify state of `live.<process>` | Hub publishes `live` to Android (see below) |
| `request_friends_refresh` | Hub when Android asks; settings pane | `findmydeviced` fetches friends' secure locations |
| `config_changed` | Settings pane, loopback pairing endpoint | Hub reconnects the relay stream; device daemons re-read the device-location toggle |

### Live sessions

When a friend opens your location (in Find My, or in Messages, which shows a live map of you), Apple
pushes a `START_LOCATE_COMMAND` to the iPhone and `findmydeviced` asks CoreLocation for continuous
updates at 10 m accuracy. Apple rarely sends `STOP_LOCATE_COMMAND`. Usually Find My stops by itself
4 to 8 minutes later, and a new push restarts it if the friend is still looking. While it runs,
Find My uploads a new position as soon as it gets one, plus one about every minute.

Ping Relay treats any `startUpdatingLocation` at 100 m accuracy or better as a live session that
lasts until every such manager calls `stopUpdatingLocation`. Background tracking asks for a much
coarser accuracy and doesn't count. The hub sends Android `live` with `active: true` when a session
starts and about every 30 s while it lasts. It sends `active: false` once the session has been over
for 5 s, because Find My sometimes swaps one manager for another mid-session. The keep-alives are
driven by the location updates Find My receives during the session: timers on a locked, sleeping
iPhone can run more than a minute late.

During a session Ping takes high-accuracy fixes every 3 s and uploads whenever you've moved 5 m
(at most every 10 s on the public `ntfy.sh`). It goes back to its normal mode
([`android/README.md`](../android/README.md#battery)) in whichever of these comes first:

| Ends when | Typically | Why |
|---|---|---|
| The iPhone sends `active: false` | 5 s after Find My stops, 1 to 8 minutes into the session | The normal case. Ping doesn't need Apple's stop push. |
| No `live` message for 150 s | 2 to 3 minutes after the iPhone goes quiet | The iPhone lost signal or died mid-session, or `active: false` got lost. |
| 30 minutes after the session started | Only if keep-alives keep coming | A cap in case Find My gets stuck. Ping then ignores that session's keep-alives until the iPhone sends `active: false` or goes quiet for 150 s. After that, a new session can start. |

So Ping streams precise fixes for 30 minutes at most, and only while the iPhone keeps saying someone
is looking. This logic is in `LiveSession` (`core/LiveSession.kt`, with unit tests).

## Your location: Android → Apple

1. **Capture.** Ping's foreground service reads location from Android's `LocationManager` with the
   platform fused provider (no Play Services). The request adapts to whether the app is on screen,
   whether you're moving, and the **Update frequency** setting
   ([`android/README.md`](../android/README.md#battery)).
2. **Validate.** `LocationValidator` drops mock providers, out-of-range coordinates, accuracy worse
   than 1500 m and fixes older than 120 s before anything is sent.
3. **Decide whether to upload.** A fix goes up when you've moved past the cadence's distance (15 /
   40 / 100 m, at most every 10 s), when the heartbeat is due (2 / 5 / 10 min), or on an explicit
   request: app opened, **Sync now**, or a `locate_request` from the iPhone. During a live session
   (below), any 5 m move goes up, every 3 s (10 s on the public `ntfy.sh`).
4. **Encrypt.** The fix becomes a `location_update` payload (coordinates, accuracy, capture time, a
   monotonic `seq`, …) inside an AES-256-GCM `fmb1` envelope ([`PROTOCOL.md`](PROTOCOL.md)).
5. **Send.** If a LAN/VPN address is set, Ping tries `POST http://<lan>/v1/sync` first, with a 2.5 s
   connect timeout. Otherwise, or if that fails, it POSTs to `<relay>/fmb-<channel>-up`.
6. **Receive.** The SpringBoard hub holds one streaming subscription to the uplink topic. It
   decrypts each message, checks `seq` and freshness ([`SECURITY.md`](SECURITY.md)), and writes the fix
   to `state.json`. Then it posts `location_updated`.
7. **Inject.** In the Find My processes the tweak hooks `CLLocationManager`:
   - `location` returns the stored fix;
   - `setDelegate:` swizzles the delegate's `locationManager:didUpdateLocations:` so it receives the
     fix instead of the real one;
   - `startUpdatingLocation` / `requestLocation` push the fix straight away. `requestLocation`
     posts `locate_needed`, so Android sends a fresh one. A precise `startUpdatingLocation` starts a
     live session, which `stopUpdatingLocation` ends ([Live sessions](#live-sessions));
   - `CLLocationSourceInformation` `isSimulatedBySoftware` / `isProducedByAccessory` return `NO`
     while a fix is being injected.

   Find My then publishes the Android position as the iPhone's location. A stored fix never expires:
   if Android goes quiet, the last one keeps being served, so Find My doesn't jump to the relay
   iPhone's own position. The hooks only fall back to the real CoreLocation result when the bridge
   is off or no fix is stored (none received yet, or **Clear Cached Android Fix**).

## Friends' locations: Apple → Android

On iOS 15, friends' locations are **end-to-end encrypted** ("secure locations"). The `fmfmobile`
friends replies that `fmfd` fetches only say *who* shares with you, with no coordinates.
`searchpartyd` fetches the actual locations from `gateway.icloud.com/findmyservice/fetch` and
decrypts them on the phone into `SPSecureLocation` objects.

1. **Learn who's who.** The tweak hooks `NSURLSession dataTaskWithRequest:completionHandler:` in the
   Find My processes and reads replies from `fmfmobile.icloud.com/fmipservice/friends/`. From
   `following[]` it builds a `findMyId → handle/name` map (`friendmeta.json`). The handle comes from
   `invitationAcceptedHandles`, which is the friend's own address, not the one the share was sent to.
   Your own addresses (`myInfo.emails`) are skipped, and names come from `contactDetails` where
   present. The same reply also sets `meDeviceActive` (`serverContext.sendMyLocation`).
2. **Capture.** The tweak hooks the places decrypted locations are delivered:
   - `SPSecureLocationsManager receivedUpdatedLocations:`
   - `SPSecureLocationsSubscriptionResult setLocations:` and `initWithCoder:` (results passed between
     processes over XPC)
   - for older delivery paths, `FMFSessionDataManager setLocations:`, `FMFSession` /
     `FMFSessionProxy didUpdateLocations:` (`FMFLocation` objects).

   These classes load lazily, so the hooks are installed again at 3, 9 and 27 s after launch.
3. **Label and merge.** Each location is labelled from `friendmeta.json` and merged by ID into
   `friends.json`, newest timestamp wins, so a partial update never wipes other friends. Several
   processes write this file. Every write is atomic, and `friends_updated` is posted only when the
   content actually changes.
4. **Push.** The hub encrypts the list as a `friends_state` payload and publishes it to
   `<relay>/fmb-<channel>-down`. An unchanged list is re-sent at most every 5 minutes, and a 429
   pauses publishing for the relay's `Retry-After`.
5. **Display.** Ping holds one streaming subscription to the downlink topic, even in the background,
   so updates arrive within a second or two. With contacts permission it matches each handle to
   a contact (phone or e-mail lookup) for the name and photo. Apple's internal place labels such as
   `_$!<Home>!$_` are shown as `Home`.

### On-demand fetch

Friends' locations only reach the iPhone while something subscribes to them, which normally means
while the Find My app is open. So that Ping doesn't show stale data, the tweak fetches them itself
when asked:

- Ping sets `requestFriendsRefresh` when the app opens, on **Sync now** and on the refresh button,
  and on a background upload at most every 15 minutes.
- The hub posts `request_friends_refresh` and also sends back the friends it already has right
  away (a forced `friends_state`).
- **`findmydeviced`** handles that notification by calling
  `SPSecureLocationsManager subscribeAndFetchLocationForIds:clientApp:completion:` for every ID in
  `friendmeta.json`. The decrypted results come back through the completion handler and the capture
  hooks above, and a changed list is pushed to Android.
- Only `findmydeviced` does this. It holds the `com.apple.icloud.searchpartyd.securelocations.access`
  entitlement and already has `Sharing.framework` loaded, whereas `searchpartyd` is the server. It's
  throttled to once per 8 s, and nothing is fetched between requests.

`friendmeta.json` is only written from an `fmfmobile` friends reply. On a fresh install, open Find
My → People on the iPhone once, or the fetch has no IDs to ask for.

## State on the iPhone

Everything is under `/var/jb/var/mobile/Library/Preferences/` (rootless), or the same path without
`/var/jb` if that folder doesn't exist:

| File (`uk.maynotfunction.pingrelay.…`) | Contents |
|---|---|
| `config.json` | `enabled`, `hookDeviceLocation`, `relayUrl`, `relayToken`, `channelId`, `keyBase64`, `lanEndpoint`, `pairingCode`. Created with a fresh key on first run. |
| `pairing.txt` | The current pairing code on its own |
| `state.json` | The latest Android fix plus `receivedAtMs` |
| `friends.json` | Merged friends list and `meDeviceActive` |
| `friendmeta.json` | `findMyId → {handle, name}` |
| `debug` / `debug.d/` | Opt-in debug flag and its captures ([`ios-tweak/README.md`](../ios-tweak/README.md#debugging)) |

`config.json` and `pairing.txt` hold the encryption key.

## State on Android

DataStore `bridge_settings` holds the pairing code, sharing on/off, cadence, map settings and the
last friends list, so the map isn't empty on launch. It is excluded from cloud backup and device
transfer. On first launch Ping generates its own random pairing code, which is replaced when you
paste the iPhone's. This phone's fix, relay status and the 80-line log live only in memory. The log
never contains coordinates or the pairing code.
