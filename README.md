# Find My Live Bridge

Android app `uk.maynotfunction.ping`; iOS tweak package `com.findmy.livebridge`.

Two-way live location bridge between an Android device and a jailbroken iOS relay device (iOS 15.4.1, Dopamine rootless + ElleKit) for Apple's **Find My** (`fmfd` / `FindMy.app`).

- **Android app (Kotlin, Jetpack Compose, Material 3 Expressive)**: see [`android/README.md`](android/README.md)

---

## Why This Architecture Is Needed

1. **Why OpenBubbles (`rustpush`) Cannot Publish "My Location" Directly**:
   - OpenBubbles authenticates with `fmfmobile.icloud.com` using a macOS device profile (`deviceClass: "Mac"`, `platform: "macosx"`).
   - Apple restricts the active **"Share My Location" (`meDevice`)** role strictly to iOS/iPadOS devices. Macs can view friends' locations, but Apple's servers keep `serverContext.sendMyLocation: false` for Mac UDIDs and ignore `myLocationChanged` writes from Mac sessions.
2. **Why Hooking `fmfd` + `FindMy` on the Relay Device Works**:
   - The iOS relay device is signed into your Apple ID and set as **"Use This iPhone as My Location"** in `Settings → [Your Name] → Find My`.
   - On iOS 15, `locationd` (`/usr/libexec/locationd`) is the C++ server daemon, while **`fmfd`** (`/usr/libexec/fmfd`, `com.apple.icloud.fmfd`), **`findmydeviced`**, **`searchpartyd`**, and **`FindMy.app`** (`com.apple.findmy`) are the `CoreLocation.framework` clients that query `CLLocationManager` and talk to `https://p{N}-fmfmobile.icloud.com/fmipservice/friends/...`.

3. **How friends' locations reach Android (iOS 15)**:
   - Friends' locations are end-to-end encrypted ("secure locations"). `searchpartyd` fetches them from `gateway.icloud.com/findmyservice/fetch` and decrypts them on the phone; the `fmfmobile` friends replies only list who shares with you (no locations).
   - The tweak hooks where the Find My app receives the decrypted `SPSecureLocation` objects (`SPSecureLocationsManager receivedUpdatedLocations:` and `SPSecureLocationsSubscriptionResult`), and labels them using a `findMyId` → handle map from the `initClient` friends list (`following[].invitationAcceptedHandles`). The Android app turns handles into names and photos from Contacts.
   - Friends are merged per person into `com.findmy.livebridge.friends.json` and pushed to Android when they change.
   - **On-demand fetch:** when the Android app asks for a refresh (on open, on a manual refresh, or on its periodic location upload), the tweak posts `kNotifyRequestFriendsRefresh`. `findmydeviced` observes it and calls `SPSecureLocationsManager subscribeAndFetchLocationForIds:clientApp:completion:` for the known friend ids, so friends refresh without the Find My app being open. Only `findmydeviced` does this (it holds `com.apple.icloud.searchpartyd.securelocations.access`); it is rate-limited to once per 8 s. Between refreshes nothing is fetched.
   - Debugging: `touch /var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug` turns on capture of Find My request logs, raw friends replies and private class lists into `com.findmy.livebridge.debug.d/` (delete both when done; the captures contain personal data).

---

## Security & Live-Location Safeguards

What the safeguards do and don't cover:

- **No manual coordinate entry.** Neither the Android app nor the iOS tweak has any UI or API to type coordinates or pick a map pin. The map is read-only.
- **Mock-location rejection happens on Android only.** [`LocationValidator.kt`](android/app/src/main/java/uk/maynotfunction/ping/core/LocationValidator.kt) drops any fix where `Location.isMock()` is set, plus stale (>120 s) or inaccurate (>1500 m) fixes, before anything is encrypted. The iPhone **cannot** verify this: it trusts any correctly encrypted message, and the `isMock` field is just a value the Android app sends. **Anyone holding the pairing code can therefore feed the iPhone arbitrary coordinates** (within the checks below). Treat the pairing code like a password and rotate it ("New Key") if it leaks.
- **On the iPhone this is location injection.** The tweak replaces what CoreLocation returns inside `fmfd`/`FindMy.app` (and, if enabled, `findmydeviced`/`searchpartyd`), and makes `CLLocationSourceInformation.isSimulatedBySoftware` report `NO` while it's injecting, so Apple's servers see the Android position as the iPhone's own. When no fix is being injected, the hooks pass through to the real values.
- **Freshness checks (iOS).** [`FMBShared.h`](ios-tweak/FMBShared.h) rejects any fix with `isMock == true`, a capture time (`fixTimeMs`, from `Location.getTime()`) more than 120 s from now, or accuracy worse than 2000 m. Stored fixes expire after the TTL (default 20 minutes) if Android stops reporting, so stale coordinates are never served as fresh.
- **End-to-end encryption.** All relay and LAN traffic is AES-256-GCM (Apple CommonCrypto on iOS, `javax.crypto` on Android) with a random 96-bit IV, a 128-bit tag and direction-bound AAD (`fmb1:up:<channelId>` / `fmb1:down:<channelId>`), so the relay only sees ciphertext.
- **Replay protection.** Every payload carries a `seq` (the sender's epoch ms, forced to strictly increase). Each side only accepts messages newer than the last one it accepted and no older than 120 s (uplink) / 5 minutes (downlink), so a captured message can't be replayed later.
- **Relay metadata.** The relay can't read payloads, but it does see topic names (derived from the channel ID), message timing and sizes, and both devices' IP addresses. On public `ntfy.sh` that's a third party; a self-hosted ntfy server with an access token keeps it in-house (see below).
- **Device location scope.** With **Also Replace Device Location** on (the default, matching earlier versions), Find My iPhone and the offline finding network also report the Android position. Turn it off in the tweak's settings to keep them on the iPhone's real GPS while Share My Location still uses Android.
- **LAN endpoint.** The tweak listens on TCP 8765 on all interfaces. `/v1/sync` only acts on correctly encrypted, fresh messages; `/v1/pairing` only answers from loopback. Requests are capped at 64 KB with a 5-second read timeout.

## Relay options

The pairing code is `fmb1:<relayUrl>:<channelId>:<base64Key>[?lan=<host:port>][&token=<accessToken>]`.

- **Public ntfy.sh** (default): works anywhere, no setup, but metadata goes to a third party.
- **Self-hosted ntfy**: set **Relay Server** to your URL and **Relay Access Token** to an ntfy token (`tk_…`) on either device, then copy the new pairing code to the other. Both sides send it as `Authorization: Bearer`.
- **Direct LAN/VPN** (`?lan=`): Android posts straight to the iPhone and gets the friends list back in the response. The relay is still used for pushes from the iPhone (`locate_request`, friend updates), so keep a relay configured.

**Upgrading:** 1.3.0 adds `seq` to every message and rejects messages without it, so update the Android app and the tweak together. Respring after installing the deb so SpringBoard loads the new relay hub.

## Project Structure

### 1. Android App ([`android/`](android/))
Kotlin + Jetpack Compose with Material 3 Expressive (min API 31, target API 37), package `uk.maynotfunction.ping`. See [`android/README.md`](android/README.md) for the architecture, build and install steps. It replaced the original hand-built Java app (`com.findmy.livebridge`, removed).

### 2. iOS 15.4.1 Dopamine Rootless ElleKit Tweak ([`ios-tweak/`](ios-tweak))
- [`ios-tweak/Tweak.m`](ios-tweak/Tweak.m) — Single-dylib tweak + native iOS Settings pane (`FMBSettingsViewController`).
- [`ios-tweak/FMBShared.h`](ios-tweak/FMBShared.h) — Protocol code shared with the test tool: CommonCrypto AES-GCM, envelopes, pairing codes, fix validation, replay protection.
- The rootless `.deb` is produced in `ios-tweak/build/` (gitignored) by [`ios-tweak/build_deb.py`](ios-tweak/build_deb.py), reading the version from [`ios-tweak/control`](ios-tweak/control).

### 3. Verification & Relay Test Suite ([`tools/`](tools))
- [`tools/test_e2ee_protocol.m`](tools/test_e2ee_protocol.m) — Tests the tweak's own `FMBShared.h`: NIST SP 800-38D vector, tamper rejection, envelope direction binding, fix validation, pairing-code parsing and replay protection. `--simulate-relay <code>` acts as a stand-in relay device.
  Build and run: `cd tools && clang -fobjc-arc -I ../ios-tweak -framework Foundation -framework Security test_e2ee_protocol.m -o test_e2ee_protocol && ./test_e2ee_protocol`
