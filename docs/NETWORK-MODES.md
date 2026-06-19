# Network access modes

StepDaddy Gateway enforces three network modes in Settings → **Network**. The mode controls which client IPs may reach the embedded Ktor server and which URLs the dashboard shows.

## Default (loopback only)

- Server binds to `127.0.0.1` only.
- Only clients on the same device (TiviMate, browser on-device) can connect.
- LAN and internet clients receive **403 Forbidden**.
- Dashboard playlist/EPG URLs use `http://127.0.0.1:<port>/…`.

**Use when:** TiviMate runs on the same Android TV / stick as the gateway.

## Local (same subnet)

- Server binds to `0.0.0.0` but a request guard enforces subnet policy.
- Allows `127.0.0.1` and clients on the same `/24` as `wlan0` / `eth0`.
- All other IPs receive **403 Forbidden**.
- No tunnel or WAN access; remote port-forward traffic is blocked.
- Dashboard URLs use `http://<lan-ip>:<port>/…`.
- QR dialog shows a LAN QR code.

**Use when:** Phones, tablets, or other TVs on the same home Wi‑Fi need the playlist.

## Remote (tunnel + token)

- Server binds to `0.0.0.0`; guard allows loopback, LAN, and WAN **with a valid access token**.
- Configure an **HTTPS tunnel base URL** (Cloudflare Tunnel, Tailscale funnel, etc.) in Settings.
- Non-LAN clients must send `?access_token=<token>` or header `X-StepDaddy-Token: <token>`.
- Raw WAN HTTP on a router port-forward is blocked unless the token is present.
- A random access token is generated when Remote is first enabled.
- Dashboard / QR URLs use the tunnel base and append the token for external clients.

**Use when:** You need secure access outside the home network without exposing an unauthenticated HTTP port.

## Health endpoint

`GET /health` is always allowed from loopback (for the in-app dashboard poller). All other clients are subject to the active mode rules.

## Changing modes

1. Open **Settings → Network**.
2. Select **Default**, **Local**, or **Remote**.
3. For Remote: set tunnel URL and copy the access token.
4. **Save** and **restart the server** so bind address and guard take effect.

See also: [REMOTE-ACCESS.md](REMOTE-ACCESS.md) for tunnel setup.
