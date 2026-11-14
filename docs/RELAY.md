# Relay options

The relay carries the already-encrypted messages between the two phones. It's an
[ntfy](https://ntfy.sh)-compatible pub/sub server: each device POSTs to a per-channel topic and
holds one streaming subscription to the other's topic. The relay never sees plaintext, but it does
see metadata.

| Option | Setup | Who sees metadata | Rate limits | Verdict |
|---|---|---|---|---|
| **Self-hosted ntfy + token** | A container and a reverse proxy | Only you | Yours to set | **Highly recommended** |
| Public `ntfy.sh` | None (the default) | ntfy.sh's operator | Yes; anonymous clients hit 429s | Quick start only |
| Direct LAN/VPN | An address in the pairing code | Nobody else | None | An add-on; still needs a relay |

## 1. Self-hosted ntfy (recommended)

### Run it

Copy [`ntfy/compose.yml`](../ntfy/compose.yml) and [`ntfy/server.yml`](../ntfy/server.yml) to your
server. Pin the image tag in `compose.yml`, set `base-url` in `server.yml`, then start it:

```bash
docker compose up -d
```

The config denies all access by default, disables the web UI and keeps one hour of message cache
(the phones only replay the last 30–60 s when they reconnect). Data lives in `./data`.

### Create a restricted user and token

```bash
docker compose exec ntfy ntfy user add fmb
```

```bash
docker compose exec ntfy ntfy access fmb 'fmb-*' rw
```

```bash
docker compose exec ntfy ntfy token add fmb
```

The `fmb` user can only use the `fmb-*` topics the bridge uses. Keep the printed `tk_…` token; it
goes into the pairing code. You never need the user's password.

### Expose it over HTTPS

Put it behind your usual reverse proxy or tunnel. The bridge needs three things from it:

- **No response buffering.** Subscriptions are long-lived streams (`/json`). With nginx, set
  `proxy_buffering off` for the ntfy location.
- **An idle timeout of 100 s or more.** ntfy sends a keepalive every ~45 s and both phones give up
  on a connection after 100 s of silence. Cloudflare's 100 s limit is just enough.
- **TLS.** Both apps will accept an `http://` relay URL, but then the access token and all the
  metadata travel in the clear. Use `https://`.

Check it from any machine: a subscription should stay open well past a minute, printing keepalive
events.

```bash
curl -sN -H "Authorization: Bearer tk_…" https://ntfy.example.com/fmb-test-down/json
```

Harden it further with a firewall rule so the ntfy port is only reachable from the proxy, and point
an uptime monitor at `/v1/health`.

### Pair with it

Set the relay on **the iPhone first**, then copy its code to Android:

1. iPhone, **Settings → Ping Relay → Internet Relay Server**: your URL. This creates a new channel
   and key.
2. Same screen, **Relay Access Token**: the `tk_…` token.
3. Copy the **Active Pairing Code** and paste it into Ping (**Settings → Pairing code → Paste**).

You can also do it the other way round: Ping keeps its key when you change the relay or token,
so you can set both in Ping and paste Ping's code into the iPhone. Both sides send the token as
`Authorization: Bearer`.

## 2. Public `ntfy.sh`

It works anywhere with no setup, and it's the default relay in a freshly generated code. The
trade-offs:

- **Metadata.** A third party sees your topic names (derived from the channel ID), message timing
  and sizes, and both phones' IP addresses. It can't read payloads.
- **Rate limits.** ntfy.sh refills an anonymous client's request allowance at about one request
  every 5 s, with a daily message cap per IP. Both apps keep one streaming connection rather than
  polling, re-send an unchanged friends list at most every 5 minutes, and back off on HTTP 429. Even
  so, heavy use (the app open with **Fast** cadence) can still hit 429s, and Ping shows
  `Relay rate limit (429)`.
- **Availability** depends on a free public service.

## 3. Direct LAN / VPN

If both phones are on the same network or VPN, the pairing code can carry `?lan=<host:port>`. The
tweak adds its own address when it generates a code, and prefers a `100.x` VPN address over Wi-Fi.
You can also set or clear it in Ping under **Settings → Direct LAN/VPN address**.

Ping then POSTs each uplink straight to `http://<host:port>/v1/sync` and gets the friends list back
in the response. If the iPhone can't be reached within 2.5 s, it falls back to the relay.

The LAN link is plain HTTP, but the payloads are AES-GCM encrypted like everything else. **A relay
is still required.** The iPhone uses it for every push it starts (friend updates and
`locate_request`), because it can't open a connection to the Android phone.

See [`PROTOCOL.md`](PROTOCOL.md#endpoints) for the exact topics and endpoints.
