# Remote access (HTTPS tunnel)

Remote mode does **not** expose raw HTTP on your router by default. Instead, point clients at an **HTTPS tunnel** and protect WAN traffic with the gateway **access token**.

## Recommended tunnels

### Cloudflare Tunnel (`cloudflared`)

1. Install [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) on a host that can reach the gateway LAN IP (or run the connector on the Android device if you sideload `cloudflared`).
2. Create a tunnel and map a public hostname, e.g. `https://gateway.example.com`, to `http://<device-lan-ip>:3000`.
3. In StepDaddy Gateway → **Settings → Network → Remote**, set **Remote tunnel base URL** to `https://gateway.example.com` (no trailing slash).
4. Copy the **access token** and append it to playlist URLs for clients outside your LAN:
   - Query: `?access_token=<token>`
   - Header: `X-StepDaddy-Token: <token>`

Cloudflare terminates TLS; the gateway still requires the token for non-LAN clients.

### Tailscale / Tailscale Funnel

1. Install Tailscale on the Android device or a LAN peer.
2. Enable [Tailscale Funnel](https://tailscale.com/kb/1223/tailscale-funnel/) or serve HTTPS on a MagicDNS name pointing at port 3000.
3. Set the funnel HTTPS URL as the tunnel base in Settings.
4. Use the access token for clients that are not on the gateway LAN subnet.

## Token usage in TiviMate

TiviMate playlist URL example:

```
https://gateway.example.com/tivimate-playlist.m3u8?access_token=YOUR_TOKEN
```

EPG URL:

```
https://gateway.example.com/epg.xml?access_token=YOUR_TOKEN
```

Regenerate the token in Settings if it is leaked. Restart the server after changing network settings.

## Port forwarding (not recommended)

If you forward TCP 3000 on your router to the device, Remote mode still blocks anonymous WAN access unless the request includes a valid token. Prefer an HTTPS tunnel instead of plain HTTP on the public internet.

## Security notes

- Treat the access token like a password.
- Default mode is safest for single-device TiviMate setups.
- Local mode never enables WAN; use Remote only when you need off-LAN access.
