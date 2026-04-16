#!/usr/bin/env python3
"""tunnel-qr: Reads the Cloudflare quick-tunnel URL from cloudflared log output
and serves a QR code page on LAN port 19999.

Environment variables:
  TUNNEL_LOG   Path to the file where cloudflared stdout/stderr is tee'd.
               Default: /shared/tunnel.log
  QR_PORT      HTTP port for the QR page. Default: 19999
"""
import base64
import http.server
import io
import os
import re
import threading
import time

import qrcode

TUNNEL_LOG = os.environ.get("TUNNEL_LOG", "/shared/tunnel.log")
QR_PORT = int(os.environ.get("QR_PORT", "19999"))
_URL_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com", re.IGNORECASE)

_tunnel_url: str | None = None
_lock = threading.Lock()


def _watch_log() -> None:
    """Background thread: scan TUNNEL_LOG line by line until the tunnel URL is found."""
    global _tunnel_url
    while True:
        try:
            with open(TUNNEL_LOG, encoding="utf-8", errors="replace") as f:
                for line in f:
                    m = _URL_RE.search(line)
                    if m:
                        with _lock:
                            _tunnel_url = m.group(0)
                        print(f"[tunnel-qr] Tunnel URL: {_tunnel_url}", flush=True)
                        return
        except FileNotFoundError:
            pass
        time.sleep(2)


def _qr_data_uri(url: str) -> str:
    img = qrcode.make(url)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


_HTML_WAITING = """<!doctype html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="3">
<title>GhostCrab — Waiting for tunnel…</title>
<style>
body{background:#0F1115;color:#ccc;font-family:sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.box{text-align:center} h1{color:#5BE9FF;font-size:1.3em} p{color:#888;font-size:.9em}
</style></head>
<body><div class="box">
<h1>Waiting for tunnel…</h1>
<p>Cloudflare is starting up. This page refreshes every 3 seconds.</p>
</div></body></html>"""


def _html_ready(url: str) -> str:
    qr = _qr_data_uri(url)
    return f"""<!doctype html>
<html><head><meta charset="utf-8">
<title>GhostCrab — Scan to Connect</title>
<style>
body{{background:#0F1115;color:#ccc;font-family:sans-serif;
     display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}}
.box{{text-align:center}}
h1{{color:#5BE9FF;font-size:1.5em;font-weight:bold;margin-bottom:4px}}
p.sub{{color:#888;font-size:.9em;margin-bottom:24px}}
img{{border:2px solid #5BE9FF;border-radius:8px;width:220px;height:220px}}
p.url{{color:#7BD8FF;font-family:monospace;font-size:.85em;margin-top:16px;word-break:break-all}}
</style></head>
<body><div class="box">
<h1>GhostCrab</h1>
<p class="sub">Open the GhostCrab app and scan this QR code</p>
<img src="{qr}" alt="QR code">
<p class="url">{url}</p>
</div></body></html>"""


class _Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self) -> None:  # noqa: N802
        with _lock:
            url = _tunnel_url

        if self.path == "/url":
            body = (url or "").encode()
            code = 200 if url else 404
            ct = "text/plain; charset=utf-8"
        else:
            body = (_html_ready(url) if url else _HTML_WAITING).encode()
            code = 200
            ct = "text/html; charset=utf-8"

        self.send_response(code)
        self.send_header("Content-Type", ct)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    threading.Thread(target=_watch_log, daemon=True).start()
    server = http.server.HTTPServer(("0.0.0.0", QR_PORT), _Handler)
    print(f"[tunnel-qr] Listening on :{QR_PORT} — watching {TUNNEL_LOG}", flush=True)
    server.serve_forever()
