"""Tiny HTTPS dev server for serving the speaker_tuner PWA to a phone.

Web Audio + getUserMedia require a secure context, so plain http.server is not
enough on Android Chrome unless you reach the page via localhost. To use this
from the S25 over LAN:

    python serve.py            # serves on https://0.0.0.0:8443
    # connect the phone to the same Wi-Fi, open https://<pc-ip>:8443/
    # accept the self-signed cert warning once

A self-signed cert is generated under ./_certs/ on first run (requires the
`openssl` CLI to be on PATH).
"""

from __future__ import annotations

import http.server
import os
import shutil
import socketserver
import ssl
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CERT_DIR = HERE / "_certs"
CERT = CERT_DIR / "cert.pem"
KEY = CERT_DIR / "key.pem"
PORT = int(os.environ.get("PORT", "8443"))


def ensure_cert() -> None:
    if CERT.exists() and KEY.exists():
        return
    if shutil.which("openssl") is None:
        sys.exit(
            "openssl not found on PATH. Install OpenSSL or supply your own "
            f"cert/key at {CERT} and {KEY}."
        )
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    subprocess.check_call(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-out", str(CERT), "-keyout", str(KEY),
            "-days", "365",
            "-subj", "/CN=speaker-tuner.local",
            "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
        ]
    )


class Handler(http.server.SimpleHTTPRequestHandler):
    # Make sure ES module MIME is correct on every platform.
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".js": "application/javascript",
        ".mjs": "application/javascript",
    }

    def end_headers(self) -> None:
        # Required so getUserMedia / SharedArrayBuffer work without quirks.
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Cross-Origin-Embedder-Policy", "require-corp")
        super().end_headers()


def main() -> None:
    ensure_cert()
    os.chdir(HERE)
    socketserver.TCPServer.allow_reuse_address = True
    httpd = socketserver.TCPServer(("0.0.0.0", PORT), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(str(CERT), str(KEY))
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"Serving {HERE} on https://0.0.0.0:{PORT}/")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        httpd.server_close()


if __name__ == "__main__":
    main()
