# Protocol (`fmb1`)

The wire format shared by Ping and the tweak. The reference implementation is
[`ios-tweak/FMBShared.h`](../ios-tweak/FMBShared.h) (C/Objective-C on CommonCrypto). Android mirrors
it in [`core/PairingCode.kt`](../android/app/src/main/java/uk/maynotfunction/ping/core/PairingCode.kt)
and [`core/Envelope.kt`](../android/app/src/main/java/uk/maynotfunction/ping/core/Envelope.kt)
(`javax.crypto`). [`tools/test_e2ee_protocol.m`](../tools/test_e2ee_protocol.m) tests the shared
header against NIST vectors and the cases below.

## Pairing code

One string that both devices hold, shared out of band:

```
fmb1:<relayUrl>:<channelId>:<base64Key>[?lan=<host:port>][&token=<accessToken>]
```

| Part | Meaning |
|---|---|
| `relayUrl` | Base URL of the ntfy-compatible relay, no trailing slash (e.g. `https://ntfy.example.com`). Default `https://ntfy.sh`. |
| `channelId` | Random 96-bit ID as 24 hex characters. The topic names come from it. At least 16 characters. |
| `base64Key` | The 256-bit AES-GCM key. Standard or URL-safe base64; padding optional. |
| `lan` *(optional)* | `host:port` of the iPhone for a direct LAN/VPN link. Allowed characters: letters, digits, `. : - [ ]`. |
| `token` *(optional)* | Relay access token (`tk_…`). Allowed characters: letters, digits, `. _ -`. |

The code is split from the right (the last `:` comes before the key, the one before that before the
channel), so `relayUrl` can contain `:`. `lan` and `token` are limited to conservative characters,
at most 200 each, because they end up in URLs and headers. A code with an unsafe value is rejected
as a whole.

**The code contains the encryption key.** Treat it like a password.

### Who generates what

| Action | iPhone (Ping Relay) | Android (Ping) |
|---|---|---|
| First run | New channel + key, relay `ntfy.sh`, `lan=` set to its own address | New channel + key, relay `ntfy.sh`, no `lan` |
| Change relay URL | **New channel + key** | Same channel + key |
| Change access token | Same channel + key | Same channel + key |
| Change LAN address | — (set automatically on each new key) | Same channel + key |
| Paste a code | Replaces channel, key, relay, token, LAN | Replaces everything |
| New key | New channel + key; keeps relay, token, settings | New channel + key; keeps relay, token, LAN |

After any change that touches the key, copy the new code to the other device. For the LAN address
the tweak picks a `100.x.x.x` interface address first (Tailscale/NetBird-style VPNs), otherwise
`en0`'s, and adds port 8765.

## Envelope

Every message is AES-256-GCM (NIST SP 800-38D) with a random 96-bit IV, a 128-bit tag and
**direction-bound AAD**, so an uplink message can't be replayed as a downlink one or moved to
another channel:

```
AAD = "fmb1:<up|down>:<channelId>"
```

On the wire the envelope is JSON:

```json
{ "v": 1, "dir": "up", "ts": 1699999999999,
  "iv": "<base64>", "ct": "<base64>", "tag": "<base64>" }
```

`ct` is the encrypted payload (UTF-8 JSON). `ts` is informational; freshness comes from `seq` inside
the encrypted payload. A receiver rejects an envelope whose `dir` isn't the one it expects, whose IV
isn't 12 bytes or tag isn't 16 bytes, or that fails authentication.

When it arrives through ntfy, the envelope is the `message` field of an ntfy JSON event. Both sides
accept either form: an object with `ct`, or an ntfy event whose `message` is the envelope.

## Replay protection

Every payload carries `seq`: the sender's epoch-ms clock, bumped where needed so it strictly
increases. A receiver accepts a message only if:

- `seq` is present (messages without it are rejected; protocol ≥ 1.3.0),
- `seq` is greater than the last `seq` it accepted in that direction, and
- `|now − seq|` is within the maximum age: **120 s** for uplink (iPhone), **5 min** for downlink
  (Android).

The last accepted `seq` is held in memory. After a restart, the max-age window is what stops old
messages being replayed. It also means **both clocks must be roughly right**: a phone that is more
than 2 minutes off will have its uplinks rejected.

## Payloads

### Uplink (Android → iPhone), topic `fmb-<channel>-up`

**`location_update`**

| Field | Type | Notes |
|---|---|---|
| `type` | `"location_update"` | The default if `type` is missing |
| `seq` | int (ms) | See above |
| `lat`, `lon` | double | Required |
| `hAcc` | double (m) | Required; must be > 0 and ≤ 2000 on the iPhone |
| `vAcc`, `alt`, `speed`, `course` | double | Defaults 8, 0, 0, −1 if the phone has none |
| `fixTimeMs` | int (ms) | **Capture time** of the fix; required; must be within 120 s of the iPhone's clock |
| `sentTimeMs` | int (ms) | Informational |
| `provider` | string | e.g. `fused` |
| `isMock` | bool | Always `false` from Ping; `true` is rejected |
| `batteryPct` | int | Informational |
| `requestFriendsRefresh` | bool | Asks the iPhone to push and re-fetch friends |

The iPhone also rejects coordinates out of range or exactly `0,0`. A fix is only stored while the
bridge is enabled.

**`refresh_request`**: `type`, `seq`, `requestFriendsRefresh: true`, `sentTimeMs`. Asks the iPhone
to push friends and fetch new ones, with no location.

### Downlink (iPhone → Android), topic `fmb-<channel>-down`

Both downlink types share one shape:

| Field | Type | Notes |
|---|---|---|
| `type` | `"friends_state"` or `"locate_request"` | |
| `seq` | int (ms) | |
| `friends` | array | See below |
| `relayActive` | bool | The bridge's **Enable** switch |
| `meDeviceActive` | bool | Whether Apple says this iPhone is the My Location device (`serverContext.sendMyLocation`) |
| `liveFixActive` | bool | Whether an unexpired Android fix is stored |
| `lastInjectedFixTimeMs` | int (ms) | Capture time of that fix, or 0 |
| `relayTimeMs` | int (ms) | Informational |
| `fmfdHooked` | bool | Always `true`; informational |

A `locate_request` asks Android to upload a fresh, precise fix now. The iPhone sends one whenever
Find My starts or requests a location update. Android also applies its `friends` list.

Each entry in `friends`:

| Field | Type | Notes |
|---|---|---|
| `id` | string | Apple's `findMyId` (or FMF server ID) |
| `handle` | string | The friend's e-mail or phone number, if known |
| `name` | string | From Apple's contact details, otherwise the handle, otherwise `Friend` |
| `lat`, `lon`, `hAcc`, `alt` | double | |
| `timestampMs` | int (ms) | When Apple located the friend |
| `address` | string | Place label or address; may be Apple's internal form (`_$!<Home>!$_`) |
| `status` | string | `"live"` |

## Endpoints

### Relay (ntfy-style), per channel

| Direction | Publish | Subscribe |
|---|---|---|
| Uplink | `POST <relay>/fmb-<channel>-up` | `GET <relay>/fmb-<channel>-up/json?since=…` (iPhone) |
| Downlink | `POST <relay>/fmb-<channel>-down` | `GET <relay>/fmb-<channel>-down/json?since=…` (Android) |

- With a token, every request carries `Authorization: Bearer <token>`.
- Each subscriber keeps one long-lived streaming connection, not a polling loop. On connect it asks
  for recent history (`since=30s` on the iPhone, `60s` on Android), and after that for everything
  since the last event it saw. The idle timeout is 100 s; ntfy sends keepalives every ~45 s.
- On HTTP 429 both sides pause for `Retry-After` (default 60 s). Stream reconnects back off
  exponentially from 5 s up to 5 min.

### Direct LAN/VPN (optional), served by the tweak on TCP 8765

A minimal HTTP/1.1 server in SpringBoard, listening on all IPv4 interfaces. Requests are capped at
64 KB, with 5-second read/write timeouts and `Connection: close`.

| Request | Who may call | Response |
|---|---|---|
| `POST /v1/sync` | Anyone, but it only acts on a valid, fresh uplink envelope | `200` with an encrypted `friends_state` envelope, or `401` |
| `GET /v1/pairing` | **Loopback only** | `200 text/plain`: the current pairing code |
| `POST /v1/pairing` | **Loopback only** | `200 {"ok":true,"paired":true}` or `400`. Replaces the config with the posted code. This resets **Enabled**, **TTL** (20 min) and **Also Replace Device Location** to their defaults. |
| anything else | Anyone | `200 {"ok":true,"service":"PingRelay","version":"…"}` health check |

A pairing request from a non-loopback address gets the health response, not the code. When a LAN
endpoint is configured, Ping tries `/v1/sync` first, with a 2.5 s connect / 3.5 s read timeout, and
falls back to the relay. The relay still carries every push the iPhone starts.

## Versioning

| Version | Change |
|---|---|
| 1.3.0 | `seq` / replay protection. Older messages without `seq` are rejected, so update the app and the tweak together. |

Respring after installing a tweak update so SpringBoard loads the new hub.
