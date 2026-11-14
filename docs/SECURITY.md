# Security & threat model

What the safeguards cover and, more importantly, what they don't.

> [!WARNING]
> This whole project, crypto included, was written by an AI coding assistant and hasn't been
> independently reviewed. The design below is what the code is *meant* to do. Treat it as unaudited.

## Safeguards

- **No manual coordinate entry.** Neither Ping nor the tweak has any UI or API for typing
  coordinates or dropping a pin. Both maps are read-only.
- **Live-fix validation on Android.**
  [`LocationValidator.kt`](../android/app/src/main/java/uk/maynotfunction/ping/core/LocationValidator.kt)
  drops any fix where `Location.isMock()` is set, coordinates out of range or at `0,0`, accuracy worse
  than 1500 m, and fixes older than 120 s (measured on the elapsed-realtime clock). This happens
  before anything is encrypted.
- **Freshness checks on the iPhone.** [`FMBShared.h`](../ios-tweak/FMBShared.h) rejects any incoming
  fix with `isMock == true`, a capture time more than 120 s from now, accuracy worse than 2000 m, or
  invalid coordinates. If Android stops reporting, the last accepted fix is kept and served until a
  new one arrives (Settings shows *Holding last fix*). It's served with the current time, so friends
  see that position as current rather than the relay iPhone's own location.
- **End-to-end encryption.** All relay and LAN traffic is AES-256-GCM with a random 96-bit IV, a
  128-bit tag and direction-bound AAD ([`PROTOCOL.md`](PROTOCOL.md#envelope)). The relay only sees
  ciphertext. The AES-GCM code is checked against NIST SP 800-38D vectors and tamper cases by
  [`tools/test_e2ee_protocol.m`](../tools/test_e2ee_protocol.m).
- **Replay protection.** A strictly increasing `seq` plus a max-age window (120 s uplink, 5 min
  downlink) stops captured messages being replayed.
- **A narrow LAN endpoint.** `/v1/sync` only acts on correctly encrypted, fresh messages. The pairing
  endpoints only answer loopback. Requests are capped at 64 KB with 5 s timeouts.
- **Injection-safe pairing parameters.** `lan` and `token` are limited to conservative characters on
  both sides, so a crafted code can't inject into URLs or HTTP headers.
- **No pairing-code intents.** Ping no longer accepts a pairing code through an intent extra, since
  any installed app could have used that to redirect your location.
- **Secrets stay out of backups and the repo.** Ping's settings (which hold the pairing code) are
  excluded from cloud backup and device transfer, the code is hidden until you tap **Show** and
  marked sensitive when copied, and the log never records coordinates or the code. The Google Maps
  key lives only in `local.properties`. The signing keystore and all build output are gitignored.

## What it does **not** protect against

- **Your Apple Account.** Driving Find My through jailbreak hooks and feeding Apple's servers a
  location that didn't come from the device is very likely against Apple's terms. Apple could flag,
  lock or disable the account. Nothing here can lower that risk. It's your call, and a secondary
  account is the safer choice.
- **A jailbroken phone holding your Apple Account session.** The relay iPhone runs with code
  signing and sandboxing weakened, and stays signed into your Apple Account permanently. Anything
  else you install on it can reach that session. Keep it single-purpose: install only what you
  need, and keep SSH key-only or off the internet.
- **The pairing code is a bearer secret.** The iPhone trusts *any* correctly encrypted, fresh
  message. `isMock` is just a value the Android side sends. So **anyone who holds the pairing code
  can set your Find My location** to arbitrary coordinates, within the accuracy and freshness
  limits. Treat the code like a password and rotate it if it leaks. Rejecting mock locations protects
  the honest Android client; it guarantees nothing to the iPhone.
- **This is location injection by design.** On the iPhone, the tweak replaces what CoreLocation
  returns and clears the "simulated by software" flag, so Apple accepts the Android position as
  genuine. The integrity of "my location" rests on the pairing secret, not on the OS.
- **Relay metadata.** The relay can't read payloads, but it sees topic names (derived from the
  channel ID), message timing and sizes, and both phones' IP addresses. On public `ntfy.sh` that's a
  third party. Self-host ntfy with a token to keep it in-house ([`RELAY.md`](RELAY.md)).
- **The LAN server listens on every interface.** TCP 8765 is reachable by anything on the iPhone's
  networks, including VPNs. Without the key it can only fetch a health response with the tweak's
  version, or make the iPhone try to decrypt garbage. Firewall it if that matters to you.
- **Android allows cleartext HTTP app-wide.** The LAN link is plain HTTP, and Android can't scope
  cleartext to private addresses, so `cleartextTrafficPermitted` is on globally. Payloads are still
  encrypted, but an `http://` relay URL would expose the access token.
- **Clock skew.** Replay protection relies on both phones' clocks being within about 2 minutes. A
  wrong clock fails closed (messages are rejected); it doesn't open anything up.
- **Device-location scope.** With **Also Replace Device Location** on (the default), Find My
  iPhone (`findmydeviced`) and the offline-finding network (`searchpartyd`) also report the Android
  position. Turn it off to leave them on the iPhone's real GPS while Share My Location still uses
  Android. Without this, locating the relay iPhone itself from iCloud would show the Android phone.
- **Disabling `identityservicesd` (OpenBubbles users).** This is required so the relay iPhone
  doesn't interfere with the OpenBubbles relay
  ([`README.md`](../README.md#6-openbubbles-users-disable-identityservicesd)). It's another
  unsupported change to a device signed into your account.
- **Reliance on private APIs.** Friend capture and the on-demand fetch hook undocumented iOS 15 Find
  My internals. They've only been tested on 15.4.1, and a different iOS version can break them in ways that fail
  silently.
- **Debug captures contain personal data.** With the debug flag set, the tweak writes raw Find My
  friend replies and request logs to disk. Delete them when you're done
  ([`ios-tweak/README.md`](../ios-tweak/README.md#debugging)).

## Handling the pairing code

- Share it over a trusted channel, not a public chat or a cloud clipboard. Over SSH you can read
  it from the iPhone's loopback endpoint without it appearing on screen:
  `curl -s http://127.0.0.1:8765/v1/pairing`.
- `config.json` and `pairing.txt` on the iPhone also hold it, readable by root and `mobile`.
- **New key** on either device invalidates the old code straight away. Paste the new one into the
  other device.
