#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["curl_cffi"]
# ///
"""Download a dictionary entry as HTML for use as a testdata fixture.

Usage:
    uv run tools/download.py est otro
    uv run tools/download.py COLSPAN otro
    uv run tools/download.py didac cap
    uv run tools/download.py didac cap1 --url   # single entry page

Downloads https://www.rae.es/diccionario-estudiante/otro (EST),
https://dle.rae.es/otro (DLE),
https://www.collinsdictionary.com/dictionary/spanish-english/otro (COLSPAN) or
https://www.diccionari.cat/cerca/didac?search_api_fulltext_cust=cap (DIDAC;
the search view embeds every matching entry inline) and writes the raw page
(the same HTML the app fetches via OkHttp) to testdata/<tag>/<word>.html.
LINGPT word pages are
https://www.linguee.pt/portugues-ingles/traducao/<word> (served latin-1).

DIDAC has no "<base>/<word>" entry URL — headwords are served from the search
view, and homographs get numbered entry URLs like /didac/cap1. Pass `--url`
(plus the full entry URL, e.g. `--url https://www.diccionari.cat/didac/cap1`)
to grab a single homograph entry instead.

The RAE, Collins and diccionari.cat sites are fronted by Cloudflare, which
challenges plain curl/urllib requests (TLS/HTTP fingerprint) but serves real
content to a Chrome TLS stack. OkHttp on Android also gets through, because its
Conscrypt (Google BoringSSL) stack presents the same Chrome-family ClientHello.
curl_cffi baits the same fingerprint, so we impersonate Chrome for every
dictionary.
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
    "COLFREN": "https://www.collinsdictionary.com/dictionary/french-english",
    "DIDAC": "https://www.diccionari.cat/cerca/didac",
    "LINGPT": "https://www.linguee.pt/portugues-ingles/traducao",
}

# Dictionaries that still serve latin-1 (ISO-8859-15) pages; the captured
# bytes are kept in the original charset so the app and tests see what the
# live site actually sends (UTF-8 re-encoding would mismatch).
LATIN1 = {"LINGPT"}

# Dictionaries whose pages are NOT fetched as "<base>/<word>". DIDAC is special:
# its search view (/cerca/didac?search_api_fulltext_cust=<word>) embeds every
# matching entry inline, so a plain "word" maps to the search URL.
SEARCH_STYLE = {"DIDAC"}


def build_url(tag: str, word: str) -> str:
    if tag in SEARCH_STYLE:
        return f"{DICTIONARIES[tag]}?search_api_fulltext_cust={word}&show=title"
    return f"{DICTIONARIES[tag]}/{word}"


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
    parser.add_argument(
        "--url",
        action="store_true",
        help="treat `word` as a full URL (e.g. a DIDAC homograph page) instead of a headword",
    )
    args = parser.parse_args()

    tag = args.dict.upper()
    if tag not in DICTIONARIES:
        print(
            f"Unknown dictionary '{args.dict}'. Supported: {', '.join(DICTIONARIES)}",
            file=sys.stderr,
        )
        return 1

    word = args.word.strip().lower()
    url = args.word if args.url else build_url(tag, word)
    html = fetch(url)

    if args.url:
        from urllib.parse import urlparse
        slug = urlparse(args.word).path.strip("/").rsplit("/", 1)[-1]
        slug = slug.replace(":", "").replace("/", "_") or "entry"
    else:
        slug = word
    out_dir = TESTDATA / tag.lower()
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{slug}.html"
    encoding = "iso-8859-1" if tag in LATIN1 else "utf-8"
    out_path.write_text(html, encoding=encoding)

    print(f"Saved {len(html)} bytes -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())