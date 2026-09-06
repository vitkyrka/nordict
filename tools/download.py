#!/usr/bin/env python3
"""Download a dictionary entry as HTML for use as a testdata fixture.

Usage:
    python3 tools/download.py est otro
    python3 tools/download.py EST otro

Downloads https://www.rae.es/diccionario-estudiante/otro (EST) or
https://dle.rae.es/otro (DLE) and writes the raw page (the same HTML the app
fetches via OkHttp) to testdata/<tag>/<word>.html.

The RAE site is fronted by Cloudflare, which challenges plain curl requests
(TLS/HTTP fingerprint) but serves real content to Python's stdlib urllib TLS
stack. We mirror the app's request: plain GET, no Accept header, no cookies,
and OkHttp's default user agent.
"""

import argparse
import gzip
import io
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TESTDATA = ROOT / "testdata"

DICTIONARIES = {
    "EST": "https://www.rae.es/diccionario-estudiante",
    "DLE": "https://dle.rae.es",
}


def fetch(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": "okhttp/4.9.1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
        encoding = response.headers.get_content_charset() or "utf-8"
        if response.headers.get("Content-Encoding") == "gzip":
            body = gzip.decompress(body)
        html = body.decode(encoding, errors="replace")

    if "Just a moment" in html or not html.strip():
        raise RuntimeError(
            f"Cloudflare challenge/interstitial returned for {url}; "
            "cannot download. Retry later."
        )
    return html


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dict", help="Dictionary abbreviation (EST or DLE; case-insensitive)")
    parser.add_argument("word", help="Word to download")
    args = parser.parse_args()

    tag = args.dict.upper()
    if tag not in DICTIONARIES:
        print(
            f"Unknown dictionary '{args.dict}'. Supported: {', '.join(DICTIONARIES)}",
            file=sys.stderr,
        )
        return 1

    word = args.word.strip().lower()
    base = DICTIONARIES[tag]
    url = f"{base}/{word}"
    html = fetch(url)

    out_dir = TESTDATA / tag.lower()
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{word}.html"
    out_path.write_text(html, encoding="utf-8")

    print(f"Saved {len(html)} bytes -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
