# Relay options

The relay carries the (already encrypted) messages between the two phones. It's an
[ntfy](https://ntfy.sh)-compatible pub/sub server: the devices POST to a per-channel topic and
stream it back. The relay never sees plaintext.

Three options, from least to most private:

## 1. Public `ntfy.sh` (default)

Works anywhere with no setup — it's the default `relayUrl` in a freshly generated pairing code.
The tradeoff is metadata: a third party sees your topic names, message timing/sizes and IPs.

ntfy.sh also rate-limits anonymous clients, so both apps keep a single streaming connection and back
off on HTTP 429 rather than polling.

## 2. Self-hosted ntfy (recommended for privacy)

Run your own ntfy server and keep all metadata in-house. Outline:

1. **Run ntfy** (container `binwiederhier/ntfy`) with:
   - `base-url` set to your server's public URL, `behind-proxy: true` if it sits behind a reverse
     proxy (and `proxy-forwarded-header` set to whatever the proxy sets).
   - `auth-default-access: "deny-all"` and `web-root: "disable"` so nothing is open.
2. **Create a restricted user and token:**
   ```bash
   ntfy user add fmb
   ntfy access fmb 'fmb-*' rw
   ntfy token add fmb
   ```
   This user can only use the `fmb-*` topics the bridge uses.
3. **Expose it** over HTTPS (reverse proxy or tunnel). Streaming must not be buffered — confirm a
   subscription stays open past ~60 s.
4. **Pair:** on either device set **Relay Server** to your URL and **Relay Access Token** to the
   `tk_…` token, then copy the regenerated pairing code to the other device. Both sides send the
   token as `Authorization: Bearer`.

Harden further with a firewall rule so the ntfy port is only reachable through your proxy, and a
health check on `/v1/health`.

## 3. Direct LAN / VPN

If both phones are on the same network or VPN, add `?lan=<host:port>` to the pairing code (the
tweak puts its own LAN address there automatically when it generates a code). Ping then POSTs
uplinks straight to the iPhone's `:8765` endpoint and gets the friends list back in the response.

A relay is still used for pushes the iPhone starts (friend updates, `locate_request`), so keep a
relay configured even when using LAN.

See [`PROTOCOL.md`](PROTOCOL.md) for the exact topic/endpoint names.
