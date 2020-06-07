#!/usr/bin/env python3

import argparse
import os
import time
import random
import logging
import urllib.parse
import sys

from urllib.parse import urlparse
from pathlib import Path

import lxml.html

import pprint
import itertools
import requests
import requests_cache


def make_throttle_hook():
    def hook(response, *args, **kwargs):
        if not getattr(response, 'from_cache', False):
            delay = random.uniform(0.5, 3.0)
            time.sleep(delay)
        return response
    return hook


def scrapelist(s, resp):
    page = resp.text
    html = lxml.html.fromstring(page)

    for url in html.xpath('//div[@class="opslagsordBox"]//div[@class="searchResultBox"]/div/a/@href'):
        yield url

    down = html.xpath('//div[@class="opslagsordBox"]//div[@class="rulNed"]/a/@href')
    if down:
        resp = s.get(down[0])
        yield from scrapelist(s, resp)


def getwordlist(s, wordlength):
    query = '?' * wordlength
    resp = s.get('https://ordnet.dk/ddo/ordbog', params={'query': query})
    resp.raise_for_status()

    yield from scrapelist(s, resp)


def getwordurls(s):
    for i in range(1, 7):
        yield from getwordlist(s, i)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()

    logging.basicConfig(level=logging.DEBUG)
    requests_cache.install_cache('requests')

    s = requests_cache.CachedSession()
    s.hooks = {'response': make_throttle_hook()}

    wordurls = []

    # print(list(itertools.islice(getwordurls(s), 100)))

    basepath = Path('ddo')

    if args.write:
        try:
            os.makedirs(basepath)
        except FileExistsError:
            pass

    wordurls = list(getwordurls(s))
    random.shuffle(wordurls)
    for u in wordurls:
        parts = urllib.parse.urlparse(u)
        params = urllib.parse.parse_qs(parts.query)

        try:
            del params['lpage']
        except KeyError:
            pass

        # An extra space at the end of some words leads to an extra + at the
        # end of the url which results in a 404.  This happens with for example
        # the word "country".
        params['select'] = [a.strip() for a in params['select']]
        params['query'] = params['select']

        if any(x in params['query'][0] for x in (' ', '.', '-', "'", '/')):
            continue

        if any(x.isupper() or x.isdigit() for x in params['query'][0]):
            continue

        resp = s.get('https://ordnet.dk/ddo/ordbog', params=params)
        if not resp.ok:
            continue

        word = params['query'][0]

        if args.write:
            with open(basepath / f'{word}.html', 'w') as f:
                f.write(resp.text)

if __name__ == '__main__':
    main()
