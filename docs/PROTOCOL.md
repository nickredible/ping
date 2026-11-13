# Protocol (`fmb1`)

The wire format shared by Ping and the tweak. The reference implementation is
[`ios-tweak/FMBShared.h`](../ios-tweak/FMBShared.h) (C/Objective-C); the Android side mirrors it in
[`android/app/src/main/java/uk/maynotfunction/ping/core/`](../android/app/src/main/java/uk/maynotfunction/ping/core/)
(`PairingCode.kt`, `Envelope.kt`). [`tools/test_e2ee_protocol.m`](../tools/test_e2ee_protocol.m)
tests both against each other.

## Pairing code

A single string, shared out of band, that both devices hold:

```
fmb1:<relayUrl>:<channelId>:<base64Key>[?lan=<host:port>][&token=<accessToken>]
```

- `relayUrl` — the ntfy-compatible relay base URL (e.g. `https://ntfy.sh`).
- `channelId` — a random 96-bit id (hex), used to derive the topic names.
- `base64Key` — the 256-bit AES-GCM key (standard or URL-safe base64).
- `lan` *(optional)* — `host:port` of the iPhone for a direct LAN/VPN link.
- `token` *(optional)* — a relay access token (e.g. `tk_…`) for an authenticated ntfy server.

The code **contains the encryption key**: treat it like a password, share it over a trusted channel,
and rotate it ("New Key") if it leaks. `lan`/`token` values are restricted to conservative
characters so they can't be used to inject into URLs or headers.

## Envelope

Each message is AES-256-GCM (NIST SP 800-38D): a random 96-bit IV, a 128-bit tag, and
**direction-bound AAD** so an uplink message can't be replayed as a downlink one:

```
AAD = "fmb1:<up|down>:<channelId>"
```

The envelope is JSON:

```json
{ "v": 1, "dir": "up", "ts": 1699999999999,
  "iv": "<base64>", "ct": "<base64>", "tag": "<base64>" }
```

`ct` is the encrypted payload (also JSON). iOS uses Apple's CommonCrypto (`CCCryptorGCM…`); Android
uses `javax.crypto`. Both are validated against NIST test vectors by the test tool.

## Replay protection

Every payload carries `seq`: the sender's epoch-ms clock, forced to strictly increase. A receiver
accepts a message only if its `seq` is newer than the last it accepted **and** within a max age —
120 s for uplink, 5 min for downlink. So a captured message can't be replayed later, even across a
restart. Messages without a `seq` are rejected (protocol ≥ 1.3.0).

## Payloads

**Uplink (Android → iPhone):**

- `location_update` — `lat`, `lon`, `hAcc`, `alt`, `speed`, `course`, `fixTimeMs` (capture time),
  `provider`, `isMock` (always `false`), `batteryPct`, `requestFriendsRefresh`, `seq`.
- `refresh_request` — asks the iPhone to re-send / re-fetch friends. `seq`.

**Downlink (iPhone → Android):**

- `friends_state` — `friends[]` (each: `id`, `handle`, `name`, `lat`, `lon`, `hAcc`, `timestampMs`,
  `address`), `meDeviceActive`, `relayActive`, `seq`.
- `locate_request` — asks Android to upload a fresh fix now. `seq`.

Freshness: the iPhone rejects a `location_update` whose `fixTimeMs` is more than 120 s from now, or
whose accuracy is worse than 2000 m, or with `isMock == true`.

## Endpoints

**Relay (ntfy-style), per channel:**

- Uplink: `POST <relay>/fmb-<channelId>-up`, streamed via `GET <relay>/fmb-<channelId>-up/json`.
- Downlink: `POST <relay>/fmb-<channelId>-down`, streamed via `GET <relay>/fmb-<channelId>-down/json`.
- An access token, if set, is sent as `Authorization: Bearer <token>` in both directions.
- Both sides keep one long-lived streaming connection rather than polling, and back off on HTTP 429.

**Direct LAN/VPN (optional), served by the tweak on the iPhone (TCP 8765):**

- `POST /v1/sync` — accepts an encrypted uplink envelope, returns an encrypted `friends_state`.
- `GET /v1/pairing` — returns the current pairing code (**loopback only**).
- `POST /v1/pairing` — sets the pairing code (**loopback only**).

Requests are capped at 64 KB with a 5-second read timeout. When a LAN endpoint is configured, Ping
posts directly to it but still uses the relay for pushes initiated by the iPhone.

## Versioning

`seq`/replay protection landed in **1.3.0**; update the Android app and the tweak together, since
older messages (without `seq`) are rejected. Respring after installing the tweak so SpringBoard
loads the new relay hub.
