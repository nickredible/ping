# Security & threat model

What the safeguards cover, and — just as important — what they don't.

## Safeguards

- **No manual coordinate entry.** Neither Ping nor the tweak has any UI or API to type coordinates
  or drop a pin. The map is read-only.
- **Live-fix validation (Android).**
  [`LocationValidator.kt`](../android/app/src/main/java/uk/maynotfunction/ping/core/LocationValidator.kt)
  drops any fix where `Location.isMock()` is set, out-of-range coordinates, accuracy worse than
  1500 m, and fixes older than 120 s — before anything is encrypted.
- **Freshness checks (iOS).** [`FMBShared.h`](../ios-tweak/FMBShared.h) rejects any incoming fix with
  `isMock == true`, a capture time more than 120 s from now, or accuracy worse than 2000 m. Stored
  fixes expire after the TTL (default 20 min) if Android stops reporting, so a stale coordinate is
  never served as fresh.
- **End-to-end encryption.** All relay and LAN traffic is AES-256-GCM with a random IV, a 128-bit
  tag, and direction-bound AAD (see [`PROTOCOL.md`](PROTOCOL.md)). The relay only sees ciphertext.
- **Replay protection.** Monotonic `seq` plus a max-age window stops captured messages being
  replayed.
- **LAN endpoint is narrow.** The tweak's `/v1/sync` acts only on correctly encrypted, fresh
  messages; the pairing endpoints answer only from loopback. Requests are capped at 64 KB.
- **Secrets stay out of the repo.** The Google Maps API key lives only in `local.properties`
  (gitignored); the signing keystore and all build output are gitignored.

## What it does **not** protect against

- **The pairing code is a bearer secret.** The iPhone trusts *any* correctly encrypted, fresh
  message. The `isMock` flag is just a value the Android side sends. So **anyone who holds the
  pairing code can feed the iPhone arbitrary coordinates** (within the freshness/accuracy bounds).
  Treat the code like a password; rotate it if it leaks. The mock-location rejection is a guard on
  the honest Android client, not a guarantee to the iPhone.
- **This is location injection.** On the iPhone the tweak replaces what CoreLocation returns and
  hides the "simulated by software" flag, so Apple's servers accept the Android position as genuine.
  That is the whole point, but it means the integrity of "my location" rests on the pairing secret,
  not on the OS.
- **Relay metadata is visible.** The relay can't read payloads, but it sees topic names (derived
  from the channel id), message timing and sizes, and both devices' IP addresses. On public
  `ntfy.sh` that's a third party — self-host ntfy with a token to keep it in-house
  ([`RELAY.md`](RELAY.md)).
- **Device-location scope.** With **Also Replace Device Location** on (the default), Find My iPhone
  and the offline-finding network also report the Android position. Turn it off in the tweak's
  settings to leave them on the iPhone's real GPS while Share My Location still uses Android.
- **Apple-account risk.** Driving Find My through jailbreak hooks could, in principle, get the Apple
  ID flagged. That's a judgement call for you.
- **Hand-rolled reliance on private APIs.** Friend capture and on-demand fetch hook undocumented
  iOS 15 Find My internals. They're specific to 15.4.1 and may break on other versions.

## Handling the pairing code

- Share it over a trusted channel (not a public chat). Ping hides it until you tap **Show**, marks
  it sensitive when copied, and excludes it from Android backups. Over SSH you can set it on the
  iPhone directly via the loopback pairing endpoint without it appearing on screen.
- Rotating the key ("New Key") invalidates the old code immediately; re-pair both devices.
