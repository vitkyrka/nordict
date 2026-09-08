#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["curl_cffi"]
# ///
"""Download a dictionary entry as HTML for use as a testdata fixture.

Usage:
    uv run tools/download.py est otro
    uv run tools/download.py COLSPAN otro

Downloads https://www.rae.es/diccionario-estudiante/otro (EST),
https://dle.rae.es/otro (DLE) or
https://www.collinsdictionary.com/dictionary/spanish-english/otro (COLSPAN)
and writes the raw page (the same HTML the app fetches via OkHttp) to
testdata/<tag>/<word>.html.

The RAE and Collins sites are fronted by Cloudflare, which challenges plain
curl/urllib requests (TLS/HTTP fingerprint) but serves real content to a
Chrome TLS stack. OkHttp on Android also gets through, because its Conscrypt
(Google BoringSSL) stack presents the same Chrome-family ClientHello. curl_cffi
baits the same fingerprint, so we impersonate Chrome for every dictionary.
"""

import argparse
import sys
from pathlib import Path

from curl_cffi import requests

ROOT = Path(__file__).resolve().parent.parent
TESTDATA = ROOT / "testdata"

DICTIONARIES = {
    "EST": "https://www.rae.es/diccionario-estudiante",
    "DLE": "https://dle.rae.es",
    "COLSPAN": "https://www.collinsdictionary.com/dictionary/spanish-english",
}


def fetch(url: str) -> str:
    response = requests.get(url, impersonate="chrome", timeout=30)
    if response.status_code != 200:
        raise RuntimeError(f"HTTP {response.status_code} for {url}")
    html = response.text

    if "Just a moment" in html or not html.strip():
        raise RuntimeError(
            f"Cloudflare challenge/interstitial returned for {url}; "
            "cannot download. Retry later."
        )
    return html


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "dict",
        help="Dictionary abbreviation ({0}; case-insensitive)".format(
            ", ".join(DICTIONARIES)
        ),
    )
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