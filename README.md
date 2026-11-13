# Find My Live Bridge

Put an **Android phone onto Apple's Find My**, live, by relaying its location through a spare
jailbroken iPhone.

Apple only lets an iPhone or iPad be your active "Share My Location" device, so an Android phone
can't appear in Find My on its own. This project bridges the gap: the Android phone shares its GPS
with a jailbroken iPhone that is signed into your Apple ID, and a tweak on that iPhone feeds the
Android location into Find My as if it were the iPhone's own. The same bridge carries your friends'
locations back to the Android phone, so you get a working Find My client on Android.

It has three parts:

| Part | What it is | Lives in |
|---|---|---|
| **Ping** | The Android app (Kotlin, Jetpack Compose, Material 3 Expressive) | [`android/`](android/) |
| **The tweak** | A jailbreak tweak for iOS 15.4.1 (Dopamine rootless + ElleKit) | [`ios-tweak/`](ios-tweak/) |
| **Test tools** | Cross-checks the shared crypto/protocol code | [`tools/`](tools/) |

> **Scope:** this targets **iOS 15.4.1** specifically and leans on that version's private Find My
> internals, so it is a personal-use interoperability project, not a polished release. Newer iOS
> versions would need the hooks revisited.

## How it works

```
  YOUR location (Android -> Apple)                 FRIENDS' locations (Apple -> Android)
  ────────────────────────────────                 ────────────────────────────────────
  Ping reads hardware GPS                           Apple delivers friends' encrypted
        │  validate (reject mock/stale)             ("secure") locations; searchpartyd
        │  AES-256-GCM encrypt                      decrypts them on the iPhone
        ▼                                                   │
  ntfy relay  (or direct LAN/VPN)                   tweak hooks capture the decrypted
        │                                           SPSecureLocation objects
        ▼                                                   │  AES-256-GCM encrypt
  iPhone: tweak hub stores the fix                          ▼
        │                                           ntfy relay
        ▼                                                   │
  hooks in fmfd / FindMy inject it into                     ▼
  CoreLocation  ──►  Apple's Find My servers        Ping shows friends on the map
```

Both directions are end-to-end encrypted; the relay only ever sees ciphertext. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full data flow and the iOS 15 internals.

## Features

- **Live two-way sharing** — your Android location appears in Find My; friends appear in Ping.
- **End-to-end encrypted** — AES-256-GCM with replay protection; the relay is untrusted.
- **Flexible relay** — public `ntfy.sh`, your own ntfy server, or a direct LAN/VPN link.
- **Battery-aware** — adaptive GPS modes; near-idle when you're not moving.
- **On-demand friend refresh** — the tweak fetches friends' locations when Ping asks, without
  needing the Find My app open.
- **Native-feeling Android app** — Material 3 Expressive UI, optional Google Maps or OpenStreetMap.

## Quick start

1. **Pick a relay.** `ntfy.sh` works out of the box; for privacy, host your own ntfy (see
   [`docs/RELAY.md`](docs/RELAY.md)).
2. **Install the tweak** on the jailbroken iPhone and set it as your Find My "My Location" device —
   see [`ios-tweak/README.md`](ios-tweak/README.md).
3. **Build and install Ping** on the Android phone — see [`android/README.md`](android/README.md).
4. **Pair them** by copying the `fmb1:…` pairing code from one device to the other.

## Documentation

- [`android/README.md`](android/README.md) — the Android app: architecture, build, battery, maps.
- [`ios-tweak/README.md`](ios-tweak/README.md) — the tweak: build, install, settings, debugging.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — end-to-end data flow and iOS 15 Find My internals.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — pairing codes, the `fmb1` envelope, message types, endpoints.
- [`docs/SECURITY.md`](docs/SECURITY.md) — safeguards and threat model (what it does and doesn't protect).
- [`docs/RELAY.md`](docs/RELAY.md) — relay options and self-hosting ntfy.

## Status

Working on the author's devices: Android app and iOS tweak sharing live in both directions. It is
not hardened for general use — read [`docs/SECURITY.md`](docs/SECURITY.md) before relying on it.
Using jailbreak hooks against Apple's Find My service carries some risk to the Apple ID; that's a
judgement call for you.
