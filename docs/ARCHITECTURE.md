# Architecture

How the bridge moves locations in each direction, and the iOS 15 Find My internals it relies on.

## Why a relay iPhone is needed

Apple restricts the active **"Share My Location" (`meDevice`)** role to iOS/iPadOS devices. A
Mac-class client (for example OpenBubbles / `rustpush`, which authenticates with
`fmfmobile.icloud.com` as `deviceClass: "Mac"`) can *view* friends' locations, but Apple's servers
keep `serverContext.sendMyLocation: false` for Mac UDIDs and ignore `myLocationChanged` writes from
Mac sessions. So "my location" has to come from a real iOS device.

The relay iPhone is signed into your Apple ID and set as **"Use This iPhone as My Location"**
(Settings → [your name] → Find My). A tweak on it replaces the location that iOS reports with the
Android phone's, so Apple sees the Android position as the iPhone's own.

On iOS 15 the relevant processes are:

- `locationd` — the CoreLocation server daemon.
- **`fmfd`** (`com.apple.icloud.fmfd`) — Find My Friends daemon.
- **`findmydeviced`**, **`searchpartyd`** — Find My Device / offline-finding daemons.
- **`FindMy.app`** (`com.apple.findmy`) — the Find My app.

The tweak injects into these processes (see [`ios-tweak/README.md`](../ios-tweak/README.md)).

## Your location: Android → Apple

1. **Capture.** Ping's foreground service reads hardware GPS via Android's `LocationManager`
   (platform fused provider, no Play Services).
2. **Validate.** `LocationValidator` rejects mock/simulated providers, coordinates out of range,
   accuracy worse than 1500 m, and fixes older than 120 s — before anything is sent.
3. **Encrypt.** The fix (lat/lon, accuracy, capture time, a monotonic `seq`, etc.) is AES-256-GCM
   encrypted into an `fmb1` envelope (see [`PROTOCOL.md`](PROTOCOL.md)).
4. **Relay.** Ping POSTs it to the relay topic (`<relay>/fmb-<channel>-up`), or straight to the
   iPhone over LAN/VPN (`http://<host:port>/v1/sync`).
5. **Receive.** On the iPhone, the tweak's hub (running in SpringBoard) streams the uplink topic,
   decrypts, checks freshness and `seq`, and writes the fix to a small state file. The fix expires
   after a TTL (default 20 min) if Android stops reporting.
6. **Inject.** Hooks in `fmfd`/`FindMy.app` (and optionally `findmydeviced`/`searchpartyd`) replace
   what `CLLocationManager` returns with the stored fix, and make
   `CLLocationSourceInformation.isSimulatedBySoftware` report `NO` while injecting. Find My then
   publishes the Android position as the iPhone's location.

## Friends' locations: Apple → Android

On iOS 15, friends' locations are **end-to-end encrypted** ("secure locations"). The `fmfmobile`
friends replies that `fmfd` fetches only list *who* shares with you — no coordinates. The actual
locations are fetched by `searchpartyd` from `gateway.icloud.com/findmyservice/fetch` and decrypted
on the phone into `SPSecureLocation` objects.

1. **Capture.** The tweak hooks the points where the decrypted locations are delivered —
   `SPSecureLocationsManager receivedUpdatedLocations:` and `SPSecureLocationsSubscriptionResult`
   (set/decode). Each `SPSecureLocation` carries a `findMyId`, coordinates, accuracy, timestamp and
   an optional place label.
2. **Label.** `SPSecureLocation` only has an ID, so the tweak keeps a `findMyId → handle/name` map
   built from the friends list (`following[].invitationAcceptedHandles` — the friend's own handle,
   not the address the share was sent to). Ping turns handles into names and photos from Contacts.
3. **Merge.** Friends are merged per person into the tweak's `friends.json` (newest wins), so a
   partial update never wipes other friends.
4. **Push.** When the list changes, the tweak encrypts it and publishes to the downlink topic
   (`<relay>/fmb-<channel>-down`). Ping holds one streaming subscription to that topic and updates
   within a second or two, even in the background.

### On-demand fetch

Friends' locations only arrive on the iPhone while something subscribes to them — normally only
while the Find My app is open. To avoid stale data, the tweak fetches them itself on request:

- When Ping asks for a refresh (on open, manual refresh, or its periodic location upload), the hub
  posts the `kNotifyRequestFriendsRefresh` Darwin notification.
- **`findmydeviced`** observes it and calls
  `SPSecureLocationsManager subscribeAndFetchLocationForIds:clientApp:completion:` for the known
  friend IDs. The decrypted results come back through the capture hooks above.
- Only `findmydeviced` does this — it holds the `com.apple.icloud.searchpartyd.securelocations.access`
  entitlement and already has `Sharing.framework` loaded. It is rate-limited to once per 8 s, and
  nothing is fetched between refreshes.

## Android app structure

Ping is a single-module app with a clear core/data/UI split; see
[`android/README.md`](../android/README.md) for the file-by-file layout, the battery-aware location
modes, and the optional Google Maps / OpenStreetMap backends.
