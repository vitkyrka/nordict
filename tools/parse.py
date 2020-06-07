#!/usr/bin/env python3

import argparse
import sys
import os
import re
import pickle
import sqlite3
import subprocess
import pprint
import json

import lxml.html
import itertools

from collections import defaultdict
from multiprocessing import Pool


def parse_one(f):
    words = []
    x = lxml.html.parse(f)
    root = x.getroot()
    for e in root.xpath('//span[@class="lydskrift"]'):
        try:
            audio = e.xpath('.//a[contains(@id,"fallback")]')[0]
        except IndexError:
            continue

        audio = audio.attrib['href']
        phonetic = e.xpath('./text()')[0]
        words.append((phonetic, audio))

    return words


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', action='store_true')
    parser.add_argument('--dump', action='store_true')
    parser.add_argument('files', nargs='+')
    args = parser.parse_args()

    words = []

    if args.cache:
        try:
            with open('words.pickle', 'rb') as f:
                words = pickle.load(f)
        except:
            pass

    if not words:
        with Pool(10) as p:
            words = p.map(parse_one, args.files)

        words = [item for sublist in words for item in sublist]

        if args.cache:
            with open('words.pickle', 'wb') as f:
                pickle.dump(words, f)

    words = [w for w in words if '-' not in w[0]]
    words = sorted(words, key=lambda w:w[0])
    words = list(next(g) for _, g in itertools.groupby(words, key=lambda w:w[0]))

    with open('words.json', 'w') as f:
        json.dump([{'text': w[0], 'audio': w[1]} for w in words], f, indent=2)

if __name__ == '__main__':
    main()
