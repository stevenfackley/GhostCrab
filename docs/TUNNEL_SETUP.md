# Cloudflare Tunnel Setup for GhostCrab

Connect to your OpenClaw Gateway from anywhere — no port forwarding required.

## How it works

1. `cloudflared` creates a secure tunnel from your server to Cloudflare's network
2. Cloudflare assigns a public URL (e.g. `https://abc-xyz.trycloudflare.com`)
3. `tunnel-qr` reads that URL and serves a QR code page at LAN port 19999
4. Open `http://<your-server-ip>:19999` in a browser on your phone → tap **Open Camera** in GhostCrab → done

The QR code contains only the gateway URL. Bearer tokens are added in-app after scanning.

---

## Option A — Docker Compose (recommended)

### Prerequisites
- Docker + Docker Compose installed on your gateway host
- Your gateway already running with a `docker-compose.yml`

### Steps

1. Copy the two service blocks from [`docker/docker-compose.example.yml`](../docker/docker-compose.example.yml) into your existing `docker-compose.yml`.

2. Replace `<gateway-service>` with the name of your gateway service (the service name in your compose file, not a hostname).

3. Apply the change:
   ```bash
   docker compose up -d cloudflared tunnel-qr
   ```

4. Wait ~15 seconds, then open `http://<server-LAN-ip>:19999` in your phone browser. You should see the QR code.

### Named tunnel (permanent URL — optional)

Quick tunnels get a new URL on every restart. For a permanent URL:

1. Create a free Cloudflare account and create a named tunnel in the Cloudflare dashboard
2. Copy the tunnel token
3. Add to the `cloudflared` service in your compose file:
   ```yaml
   environment:
     CLOUDFLARE_TUNNEL_TOKEN: "your-token-here"
   command: tunnel run
   ```
4. `tunnel-qr` works identically — the QR page will show your permanent URL

---

## Option B — Direct Install (Linux / macOS / Windows)

### Step 1: Install cloudflared

**Linux (Debian/Ubuntu):**
```bash
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o cloudflared.deb
sudo dpkg -i cloudflared.deb
```

**macOS:**
```bash
brew install cloudflare/cloudflare/cloudflared
```

**Windows:** Download the MSI from https://github.com/cloudflare/cloudflared/releases/latest

### Step 2: Start the tunnel

```bash
# Linux/macOS — tee output to a shared log file
cloudflared tunnel --url http://localhost:18789 --no-autoupdate 2>&1 | tee /tmp/cloudflared.log &
```

```powershell
# Windows PowerShell
Start-Process cloudflared -ArgumentList "tunnel --url http://localhost:18789 --no-autoupdate" -RedirectStandardOutput "$env:TEMP\cloudflared.log" -RedirectStandardError "$env:TEMP\cloudflared.log"
```

### Step 3: Install and run tunnel-qr

**Option 1 — pip:**
```bash
pip install openclaw-tunnel-qr
TUNNEL_LOG=/tmp/cloudflared.log openclaw-tunnel-qr
```

**Option 2 — Docker standalone:**
```bash
docker run -p 19999:19999 \
  -v /tmp/cloudflared.log:/shared/tunnel.log:ro \
  ghcr.io/openclaw/tunnel-qr:latest
```

### Step 4: Scan and connect

Open `http://<your-machine-ip>:19999` in your phone browser. Scan the QR code with GhostCrab.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| QR page shows "Waiting for tunnel…" | cloudflared is still starting (takes ~10s). Refresh the page. |
| QR page is not reachable at :19999 | Make sure your server firewall allows inbound TCP 19999 on LAN. Port 19999 should NOT be open to the internet. |
| GhostCrab shows "doesn't contain a valid gateway URL" | The QR code is not from GhostCrab's tunnel-qr page. Use the correct URL. |
| Tunnel URL changes after restart | Use a named tunnel (see Option A above) for a permanent URL. |
