# Ping for Android

Kotlin, Jetpack Compose and Material 3 Expressive. Package `uk.maynotfunction.ping`, min API 31
(Android 12), target API 37. The design follows [Tomato](https://github.com/nsh07/Tomato): a floating
toolbar of expressive toggle buttons, segmented list items, connected button groups and the
expressive motion scheme.

For how the app fits into the whole bridge, see [`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).

## Layout

```
app/src/main/java/uk/maynotfunction/ping/
├── BridgeApplication.kt     App-wide singletons (repository, relay client, contacts); no DI framework
├── core/                    Pure protocol code, wire-compatible with ios-tweak/FMBShared.h
│   ├── PairingCode.kt       fmb1:<relay>:<channel>:<key>[?lan=…][&token=…] parse / build / generate
│   ├── Envelope.kt          AES-256-GCM envelope (javax.crypto) + SeqGuard replay protection
│   ├── LocationValidator.kt Rejects mock, stale (>120 s) and inaccurate (>1500 m) fixes
│   └── Models.kt            FriendLocation, MyFix, Cadence, LogLine
├── data/
│   ├── BridgeRepository.kt  DataStore settings; StateFlows for this phone's fix, relay status,
│   │                        friends and the in-memory log
│   ├── RelayClient.kt       Uplink over the ntfy relay or direct LAN/VPN; streaming downlink
│   │                        subscription with backoff
│   └── ContactsRepository.kt  Contacts lookups (PhoneLookup / e-mail) for friends' names and photos
├── service/LocationService.kt  Foreground location service: adaptive location requests, uploads on
│                               movement / heartbeat / request, owns the downlink stream
└── ui/
    ├── MainActivity.kt, MainViewModel.kt, BridgeScreen.kt  Scaffold, tabs, toolbar, permissions
    ├── friends/ me/ settings/  The three tabs
    ├── onboarding/          First-run setup: permissions, pairing, connection check, battery
    ├── map/                 LiveMap: Compose-canvas slippy map (TileLoader with memory + disk cache,
    │                        MapCamera pan/zoom/fling); GoogleLiveMap (Maps Compose); MapAvailability
    ├── components/          Avatar, list items, dialogs, formatting
    └── theme/               MaterialExpressiveTheme with dynamic colour; typography
```

**Platform first.** Location comes from `LocationManager` with the platform fused provider (no Play
Services), HTTP from `HttpURLConnection`, crypto from `javax.crypto`, settings from DataStore and
contacts from `ContactsContract`. The dependencies are AndroidX/Compose, kotlinx-coroutines and
`maps-compose`. Maps Compose is only used when Google Play services and a Maps key are both available;
see [Maps](#maps).

**Type.** On Pixels the app uses the system's own per-style Google Sans Flex families
(`variable-body-large`, `variable-title-medium-emphasized`, … from
`/product/etc/fonts_customization.xml`) with letter spacing 0, so text matches the system exactly.
Elsewhere it falls back to a bundled, Latin-subset Google Sans Flex with the same weights and
rounding. Google Sans Code is used for the pairing code and the log. Both fonts come from Google
Fonts under the SIL OFL 1.1 (`app/src/main/assets/licenses/`).

## How it runs

- **First run** shows setup instead of the map, until it's finished. Installs from before setup
  existed skip it if they already have friends from the iPhone. **Settings → Run setup again**
  brings it back.

- **The `LocationService` foreground service** does the work while sharing is on: location, uploads
  and the downlink stream. It shows an ongoing notification with **Sync now** and **Stop sharing**.
  It starts when the app opens with location permission, so **after a reboot, open Ping once**.
  There's no boot receiver, and Ping doesn't ask for background location.
- **With sharing paused**, the app still streams friend updates, but only while it's on screen.
- **The downlink** is one long-lived streaming connection to `<relay>/fmb-<channel>-down/json` with a
  100 s read timeout (ntfy keepalives arrive every ~45 s). It reconnects with exponential backoff (5 s
  to 5 min), honours `Retry-After`, and restarts whenever the pairing code changes.
- **Uploads** go to the LAN/VPN address first if one is set, then to the relay.

## Battery

The GNSS chip dominates power use, so `LocationService` adapts its location request. The current
mode shows in the **Me** tab.

| Mode | When | Request |
|---|---|---|
| Precise | App on screen | High accuracy, every 5 s, 5 m filter |
| Moving | Background, **Fast** | High accuracy, every 15 s, 10 m filter |
| Moving | Background, **Balanced** | Balanced power (Wi-Fi/cell), every 60 s (≥ 30 s), 25 m filter |
| Moving | Background, **Saver** | Balanced power, every 3 min (≥ 60 s), 50 m filter, batched up to 6 min |
| Stationary | No movement beyond 75 m (or the fixes' combined accuracy) for 5 min | Passive only (fixes other apps already paid for). The significant-motion sensor switches back to Moving. Skipped on phones without that sensor. |

What gets uploaded depends on the **Update frequency** setting:

| Cadence | Upload after moving | Heartbeat at least every |
|---|---|---|
| Fast | 15 m | 2 min |
| Balanced (default) | 40 m | 5 min |
| Saver | 100 m | 10 min |

Movement uploads are at least 10 s apart. Heartbeats keep the iPhone's copy fresh (it shows
*Holding last fix* after 20 min without one); while stationary each one takes a single balanced-power fix. **Sync now**, opening the
app, and the iPhone's `locate_request` each take one precise fix (or reuse a recent one within
50 m). A background upload asks the iPhone to refresh friends at most every 15 minutes. The
notification only redraws when its text changes.

Check with:

```bash
adb shell dumpsys location | grep -i maynotfunction.ping
```

```bash
adb shell dumpsys batterystats --charged uk.maynotfunction.ping
```

Some vendors' battery savers kill foreground services anyway. If updates stop in the background,
set Ping's battery usage to *Unrestricted*.

## Maps

**Settings › Map provider** is *Auto*, *Google* or *OSM*. *Auto* and *Google* use Google Maps
(`GoogleLiveMap.kt`, Maps Compose) only when Google Play services works and the build has a key.
Otherwise the OpenStreetMap map is used, and the setting says why. Both maps share one `MapCamera`,
so switching keeps your place. Google's blue dot is fed from the app's own fixes rather than a second
location request.

The OpenStreetMap map uses OSM's standard tiles by default (with a colour-matrix dark mode), CARTO
basemaps if you paste a CARTO API key, or any `{z}/{x}/{y}` template URL (**Settings › OpenStreetMap
tiles**). Tiles are cached in memory and in `cacheDir/tiles`. Both maps are read-only: there's no way
to pick or enter a location.

## Build and install

Pre-built APKs are on [GitHub Releases](https://github.com/nickredible/ping/releases). To build it
yourself, you need **JDK 21** and the Android SDK.

1. Point `local.properties` at the SDK:
   ```properties
   sdk.dir=/path/to/Android/sdk
   ```
2. Create the signing keystore that `app/build.gradle.kts` expects. Release builds are minified with
   R8 and signed with `keystore/debug.keystore` (gitignored), using store/key password `android` and
   alias `androiddebugkey`:
   ```bash
   mkdir -p keystore && keytool -genkeypair -v -keystore keystore/debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Ping"
   ```
   Or point `signingConfigs` at your own keystore.
3. *(Optional)* Google Maps: create a Google Cloud project, enable **Maps SDK for Android**, and
   restrict the key to this package name and your keystore's SHA-1
   (`keytool -list -v -keystore keystore/debug.keystore -storepass android`). Add it to
   `local.properties`:
   ```properties
   MAPS_API_KEY=...
   ```
   Without it the app uses OpenStreetMap.
4. Build and install:
   ```bash
   ./gradlew :app:assembleRelease
   ```
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   If your default Java isn't 21, prefix the build with `JAVA_HOME=…` (e.g.
   `/opt/homebrew/opt/openjdk@21` with Homebrew).

On first launch Ping runs its setup (permissions, pairing, connection check, battery). To try setup
without touching an installed copy, build a debug APK with its own package name and install it next
to it:

```bash
./gradlew :app:assembleDebug -Pping.appIdSuffix=.test
```

## Scripting

```bash
adb shell am start -n uk.maynotfunction.ping/.ui.MainActivity -a uk.maynotfunction.ping.ACTION_PUBLISH_NOW
```

This forces an upload. `ACTION_REFRESH_FRIENDS` asks the iPhone for friends instead. The old
`pairing_code` intent extra is gone, because any app could have used it to redirect your location.
