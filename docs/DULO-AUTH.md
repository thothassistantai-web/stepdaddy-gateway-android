# dulo.cx Live TV auth (keyring)

StepDaddy Gateway resolves dulo.cx Live TV via `POST /api/live-tv/playback-session` with a Supabase JWT (`Authorization: Bearer …`). Catalog fetch is public; playback needs `supplementDuloCxAccessToken` on the gateway.

**Do not put passwords or JWTs in this doc, commits, or release notes.**

## What login looks like

dulo.cx uses **Supabase Auth** (`wsudbodtjjfenprwsagd.supabase.co`):

- **Continue with Google** (OAuth) — common for existing accounts
- **Email + password** — works when a password identity exists
- Google-only accounts return `invalid_credentials` on password grant until a password is set (Forgot password) or a session/refresh token is stored

## Linux keyring layout

Use `secret-tool` / libsecret:

| Attribute | Purpose |
|-----------|---------|
| `email` | Account email |
| `password` | Account password (Google or dulo email login) |
| `dulo-cx-access-token` | Live TV JWT |
| `dulo-cx-refresh-token` | Supabase refresh token |
| `supabase-url` | Project URL (public) |
| `supabase-publishable-key` | Publishable/anon key (public client key) |

All entries use:

```text
application = stepdaddy-gateway
service     = dulo.cx
```

### Lookup (prints secrets — use carefully)

```bash
secret-tool lookup application stepdaddy-gateway service dulo.cx attribute email
secret-tool lookup application stepdaddy-gateway service dulo.cx attribute password
secret-tool lookup application stepdaddy-gateway service dulo.cx attribute dulo-cx-access-token
secret-tool lookup application stepdaddy-gateway service dulo.cx attribute dulo-cx-refresh-token
```

### Re-store (values from your password manager — not from chat)

```bash
printf '%s' 'you@example.com' | secret-tool store --label='dulo.cx account email' \
  application stepdaddy-gateway service dulo.cx attribute email

printf '%s' 'YOUR_PASSWORD' | secret-tool store --label='dulo.cx account password' \
  application stepdaddy-gateway service dulo.cx attribute password

printf '%s' 'YOUR_JWT' | secret-tool store --label='dulo.cx Live TV access token (JWT)' \
  application stepdaddy-gateway service dulo.cx attribute dulo-cx-access-token
```

### Fallback file (only if keyring fails)

`$HOME/.config/stepdaddy-gateway/dulo-cx.env` mode `600`. Never commit. The auth script writes this only when keyring read-back fails.

## Refresh + push to gateway

```bash
bash scripts/dulo-cx-auth.sh
bash scripts/dulo-cx-auth.sh --set-gateway http://GATEWAY_IP:3000
GATEWAY_URL=http://127.0.0.1:3000 bash scripts/dulo-cx-auth.sh --set-gateway
```

The script:

1. Prefers `refresh_token` grant, else email/password grant
2. Stores new JWT (+ refresh) in the keyring
3. Probes `playback-session` (prints channel name + `playback_ok`, not the URL/token)
4. Optionally `PATCH /api/v1/settings` with `supplementDuloCxEnabled` + `supplementDuloCxAccessToken`

If auth fails with `invalid_credentials`, the account is likely **Google OAuth-only**:

1. Open https://dulo.cx/login in a normal browser
2. **Continue with Google** for the thoth account (interactive consent may be required)
3. Or use **Forgot password** to attach an email/password identity, then re-run the script

## Manual JWT paste (browser already signed in)

From DevTools on `https://dulo.cx` → Application → Local Storage → key `sb-wsudbodtjjfenprwsagd-auth-token` → copy `access_token`, then store as `dulo-cx-access-token` (command above) and run:

```bash
bash scripts/dulo-cx-auth.sh --set-gateway http://GATEWAY_IP:3000 --no-probe
# or refresh/probe after storing refresh_token too
```

## Related

- Gateway setting: `supplementDuloCxAccessToken` / `supplementDuloCxEnabled`
- Health fields (newer builds): `duloCxAuthConfigured`, `duloCxResolveProbeOk`
- Release keystore keyring notes: [KEYSTORE-BACKUP.md](KEYSTORE-BACKUP.md)
