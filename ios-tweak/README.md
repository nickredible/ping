# Ping Relay — iOS tweak

A single-dylib jailbreak tweak for **iOS 15.4.1** (Dopamine rootless + ElleKit) that bridges Apple's
Find My to an Android phone. It injects the Android location into Find My, captures friends'
decrypted locations, and runs the encrypted relay hub. See
[`../docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for how it fits together.

> Targets iOS 15.4.1 and hooks that version's private Find My internals. Other versions aren't
> supported.

## Files

| File | What it is |
|---|---|
| `Tweak.m` | The tweak: hooks, relay hub, and the native Settings pane (`FMBSettingsViewController`). |
| `FMBShared.h` | Shared protocol code (CommonCrypto AES-GCM, `fmb1` envelope, pairing codes, validation, replay). Also used by [`../tools/test_e2ee_protocol.m`](../tools/test_e2ee_protocol.m). |
| `FindMyLiveBridge.plist` | MobileSubstrate filter: which processes to load into. |
| `control` | Debian package metadata; the **version** is read from here at build time. |
| `build_deb.py` | Builds and signs the dylib and packages the rootless `.deb`. |

## Which processes it loads into

Set by `FindMyLiveBridge.plist`, with different roles per process:

- **SpringBoard** — the relay hub: streams the uplink topic, decrypts, stores fixes, serves the LAN
  endpoint, and publishes downlinks.
- **`fmfd`**, **`FindMy.app`** — inject the Android location into CoreLocation; capture friends.
- **`findmydeviced`**, **`searchpartyd`** — capture friends; `findmydeviced` also runs the on-demand
  secure-location fetch (it holds the needed entitlement).

## Build

Requires Xcode command-line tools (for `xcrun`/`clang` against the iOS SDK). The version comes from
`control`, so bump it there before building.

```bash
python3 build_deb.py
```

This compiles `Tweak.m`, ad-hoc signs it, and writes `build/com.findmy.livebridge_<version>_iphoneos-arm64.deb`
(the `build/` directory is gitignored).

## Install

The package depends on **PreferenceLoader** (for the Settings pane) and a substrate
(ElleKit/libhooker), so install those from your package manager first, then:

```bash
# copy the .deb to the device, then as root:
dpkg -i com.findmy.livebridge_<version>_iphoneos-arm64.deb
```

**Respring afterwards** so SpringBoard loads the relay hub. The package's `postinst` restarts
`fmfd`, `findmydeviced`, `searchpartyd`, Find My and Preferences.

Then set the iPhone as your location device: **Settings → [your name] → Find My → Use This iPhone
as My Location**.

## Settings pane

Under **Settings → Ping Relay** (and a floating button inside the Find My app):

- **Enable Location Bridge** — master on/off.
- **Pairing code** — copy it to Android, paste one in, or generate a new key. It contains the
  encryption key; treat it like a password.
- **Relay server / access token** — for a self-hosted ntfy server (see
  [`../docs/RELAY.md`](../docs/RELAY.md)).
- **Live Expiration TTL** — how long a fix stays valid without an update (default 20 min).
- **Also Replace Device Location** — whether Find My iPhone and the offline-finding network also
  report the Android position (on by default). Off leaves them on the iPhone's real GPS.

## Pairing

The pairing code can be set over the loopback endpoint without showing it on screen:

```bash
# on the device (or over SSH):
curl -s http://127.0.0.1:8765/v1/pairing                       # read the current code
curl -s -X POST --data-binary @code.txt http://127.0.0.1:8765/v1/pairing   # set a new code
```

## Debugging

Create a flag file to turn on verbose capture (into
`…/Preferences/com.findmy.livebridge.debug.d/`): Find My request logs, raw friend replies, and
private class dumps.

```bash
touch /var/jb/var/mobile/Library/Preferences/com.findmy.livebridge.debug
```

**Delete the flag and the `.debug.d` directory when done — the captures contain personal data.**
The capture runs only while the flag file exists. A dump is written shortly after each hooked
process launches, so restart the Find My daemons (or respring) to refresh it.
