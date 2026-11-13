# Find My Live Bridge for Android

Kotlin, Jetpack Compose and Material 3 Expressive. Package `uk.maynotfunction.ping`,
min API 31, target API 37. The design follows [Tomato](https://github.com/nsh07/Tomato): a floating
toolbar of expressive toggle buttons, segmented list items, connected button groups and the
expressive motion scheme.

## Layout

```
app/src/main/java/uk/maynotfunction/ping/
├── BridgeApplication.kt     App-wide singletons (repository, relay client, contacts)
├── core/                    Pure protocol code, wire-compatible with ios-tweak/FMBShared.h
│   ├── PairingCode.kt       fmb1:<relay>:<channel>:<key>[?lan=…][&token=…] parse/build/generate
│   ├── Envelope.kt          AES-256-GCM envelope (javax.crypto) + SeqGuard replay protection
│   ├── LocationValidator.kt Rejects mock, stale (>120 s) and inaccurate (>1500 m) fixes
│   └── Models.kt            FriendLocation, MyFix, Cadence, LogLine
├── data/
│   ├── BridgeRepository.kt  DataStore settings,
│   │                        StateFlows for this phone's fix, relay status, friends and the log
│   ├── RelayClient.kt       HTTPS relay (ntfy) and direct LAN/VPN transport, downlink polling
│   └── ContactsRepository.kt  Contacts provider lookups (PhoneLookup / Email lookup), photos
├── service/LocationService.kt  Foreground location service: fused provider via LocationManager,
│                               uploads on movement/heartbeat, polls the downlink every 5 s
└── ui/
    ├── MainActivity.kt, MainViewModel.kt, BridgeScreen.kt  Scaffold, tabs, toolbar, permissions
    ├── map/                 Compose-canvas slippy map: TileLoader (memory + disk cache),
    │                        MapCamera (pan/zoom/fling/animated moves), LiveMap (tiles + pins)
    ├── friends/ settings/ me/  The three tabs
    ├── components/          Avatar, list items, dialogs, formatting
    └── theme/               MaterialExpressiveTheme with dynamic colour; system typography
```

**Platform first:** location comes from `LocationManager` with the platform fused provider (no
Play Services), HTTP from `HttpURLConnection`, crypto from `javax.crypto`, settings from
DataStore, contacts from `ContactsContract`. The only dependencies are AndroidX/Compose and
kotlinx-coroutines.

**Type:** on Pixels the app uses the system's own per-style Google Sans Flex families
(`variable-body-large`, `variable-title-medium-emphasized`, …, from
`/product/etc/fonts_customization.xml`) with letter spacing 0, so text matches the system exactly.
Elsewhere it falls back to a bundled, Latin-subset Google Sans Flex with the same weights and
rounding. Google Sans Code is used for the pairing code and log. Fonts are from Google Fonts under
the SIL OFL 1.1 (`app/src/main/assets/licenses/`).

**Battery:** the GNSS chip dominates, so `LocationService` adapts its request (the current mode
shows in the Me tab):

| Mode | When | Request |
|---|---|---|
| Precise | App on screen | High accuracy, every 5 s |
| Moving | Background, Fast | High accuracy, every 15 s, 10 m filter |
| Moving | Background, Balanced | Balanced power (Wi-Fi/cell), every 60 s, 25 m filter |
| Moving | Background, Saver | Balanced power, every 3 min, 50 m filter, batched up to 6 min |
| Stationary | No movement for 5 min | Passive only (fixes other apps already paid for); the significant-motion sensor switches back to Moving |

Heartbeats while stationary take one balanced-power fix; Sync now and iPhone locate requests take
one precise fix. The relay link is a single streaming connection (ntfy keepalive every 80 s, under
Cloudflare's 100 s idle limit), and the notification only redraws when its text changes. Check
with `adb shell dumpsys location | grep livebridge` and `adb shell dumpsys batterystats --charged`.

**Map provider:** Settings › Map provider is Auto, Google or OSM. Auto (and Google) use Google Maps
(`GoogleLiveMap.kt`, Maps Compose) only when Google Play services works and the build has a key
(`MAPS_API_KEY=` in `local.properties`, gitignored); otherwise the OpenStreetMap map is used. Both
share one `MapCamera`, so switching keeps your place, and Google's blue dot is fed from the app's
own fixes instead of a second location request. The key needs "Maps SDK for Android" enabled and an
Android-app restriction for this package and SHA-1.

**Map:** OpenStreetMap tiles by default (with a colour-matrix dark mode), CARTO with an API key, or
any `{z}/{x}/{y}` template. The map is read-only; there is no way to pick or enter a location.

## Build and install

Needs JDK 21 and the Android SDK. Point `local.properties` at the SDK (`sdk.dir=...`). Release
builds are minified with R8 and signed with a keystore at `keystore/debug.keystore` (gitignored;
create one with `keytool`, or adjust `signingConfigs` in `app/build.gradle.kts`).

For Google Maps, create a Google Cloud project, enable "Maps SDK for Android", and restrict the
key to this package name and your keystore's SHA-1 (`keytool -list -v -keystore keystore/debug.keystore`).
Put the key in `local.properties` as `MAPS_API_KEY=...`. Without it the app uses OpenStreetMap.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleRelease
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Scripting: `adb shell am start -n uk.maynotfunction.ping/.ui.MainActivity -a uk.maynotfunction.ping.ACTION_PUBLISH_NOW`
forces an upload (`ACTION_REFRESH_FRIENDS` asks the iPhone for friends). The old `pairing_code`
intent extra is gone, since any app could have used it to redirect your location.
