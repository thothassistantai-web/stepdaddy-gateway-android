# Linux & desktop packaging

StepDaddy **desktop** gateway lives in `~/Programs/stepdaddy-web` (symlink: `~/livehd/current/stepdaddy-web`). Releases are published from [StepDaddyLiveHD](https://github.com/thothassistantai-web/StepDaddyLiveHD).

This document covers **deb** packaging for Debian/Ubuntu and brief notes for Windows `.exe`.

---

## Tarball release (current method)

```bash
cd ~/Programs/stepdaddy-web
./scripts/build-release.sh beta-$(date +%Y%m%d)
```

Output:

```
release/stepdaddy-livehd-beta-YYYYMMDD.tar.gz
release/stepdaddy-livehd-beta-YYYYMMDD.zip
```

Deploy on target machine:

```bash
tar -xzf stepdaddy-livehd-beta-YYYYMMDD.tar.gz -C ~/Programs
cd ~/Programs/stepdaddy-web
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
./start.sh
```

Health check: `curl -s http://127.0.0.1:3000/health`

---

## Debian package (.deb) — manual recipe

There is no automated `deb` target in-tree yet. Use **nfpm** or **fpm** on a build host.

### Prerequisites

```bash
# nfpm (recommended)
sudo apt install nfpm   # or: go install github.com/goreleaser/nfpm/v2/cmd/nfpm@latest
```

### Example `nfpm.yaml` (add to stepdaddy-web repo)

```yaml
name: stepdaddy-livehd
arch: amd64
platform: linux
version: "0.1.0"
section: net
priority: optional
maintainer: thothassistantai-web
description: Self-hosted IPTV gateway with web UI
homepage: https://github.com/thothassistantai-web/StepDaddyLiveHD
license: MIT
contents:
  - src: ./release/stage/opt/stepdaddy-livehd
    dst: /opt/stepdaddy-livehd
  - src: ./packaging/stepdaddy-livehd.service
    dst: /lib/systemd/system/stepdaddy-livehd.service
scripts:
  postinstall: ./packaging/postinstall.sh
```

### Staging directory

```bash
cd ~/Programs/stepdaddy-web
./scripts/build-release.sh beta-YYYYMMDD
STAGE=release/deb-stage/opt/stepdaddy-livehd
mkdir -p "$STAGE"
rsync -a --exclude venv --exclude .git --exclude logs . "$STAGE/"
```

### systemd unit (`packaging/stepdaddy-livehd.service`)

```ini
[Unit]
Description=StepDaddy LiveHD Gateway
After=network-online.target

[Service]
Type=simple
User=stepdaddy
WorkingDirectory=/opt/stepdaddy-livehd
EnvironmentFile=/etc/stepdaddy-livehd/env
ExecStart=/opt/stepdaddy-livehd/venv/bin/python -m uvicorn app.backend:app --host 0.0.0.0 --port 3000
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

### Build deb

```bash
nfpm pkg --packager deb --target release/stepdaddy-livehd_0.1.0_amd64.deb
```

### Install on Ubuntu/Debian

```bash
sudo dpkg -i stepdaddy-livehd_0.1.0_amd64.deb
sudo cp /opt/stepdaddy-livehd/.env.example /etc/stepdaddy-livehd/env
sudo systemctl enable --now stepdaddy-livehd
```

### Dependencies

Package should `Depends: python3 (>= 3.10), python3-venv, adduser` or bundle a PyInstaller one-file binary (larger artifact, simpler deps).

---

## Docker (alternative to deb)

From `StepDaddyLiveHD` repo:

```bash
docker compose up -d
```

Exposes port 3000 via Caddy or direct uvicorn per `docker-compose.yml`.

---

## Windows `.exe` (brief)

Not officially shipped. Options for future:

| Approach | Notes |
|----------|-------|
| **PyInstaller** | `pyinstaller --onefile` on `app/backend.py` — large binary, AV false positives |
| **WSL2** | Run Linux tarball inside WSL; document in README |
| **Docker Desktop** | Same as Linux Docker path |

No Windows CI in current org repos.

---

## Android gateway (this repo)

Android APK/AAB: [RELEASE.md](RELEASE.md) and `scripts/build-release.sh`.

---

## Version alignment

| Component | Version source |
|-----------|----------------|
| Linux web | `VERSION` file / git tag in StepDaddyLiveHD |
| Android gateway | `app/build.gradle.kts` `versionName` |
| Health JSON | Each reports its own `version` string |

Parity is best-effort; compare route behavior in [ARCHITECTURE.md](../ARCHITECTURE.md).
