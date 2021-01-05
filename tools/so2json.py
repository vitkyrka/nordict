#!/usr/bin/env python3
# Examples:
# tools/so2json.py | jq '[.[] | select(.compound == false) | select((.pronunciation[0]|contains("´bel")|not) or .pos != "adjektiv") | select(.pronunciation[0]|contains("´"))]'
# tools/so2json.py | jq '[.[] | select(.compound == false) | select(.pronunciation[0]|contains("`"))]'

import argparse
import sqlite3
import json
import xml.etree.ElementTree as ET


def parse_pronunciation(s):
    """
    >>> parse_pronunciation("foo")
    ['foo']
    >>> parse_pronunciation("[<uttalstext>foo</uttalstext><ukom>äv.</ukom><uttalstext>bar</uttalstext>]")
    ['foo', 'äv. bar']
    >>> parse_pronunciation("[<uttalstext>strudel</uttalstext><ukom>(ljust sj-ljud) el.</ukom><uttalstext>bar</uttalstext>]")
    ['strudel (ljust sj-ljud)', 'el. bar']
    """
    if not s.startswith('['):
        return [s]

    s = s.strip('[]')
    items = []
    root = ET.fromstring(f'<foo>{s}</foo>')
    comment = ''
    for el in root:
        if el.tag == 'uttalstext':
            t = el.text
            if comment:
                t = f'{comment} {t}'
                comment = ''
            items.append(t)
        elif el.tag == 'ukom':
            t = el.text
            markers = ('el.', 'äv.')
            try:
                comment = next(m for m in markers if m in t)
            except StopIteration:
                pass

            for m in markers:
                t = t.replace(f'{m}', '')
            t = t.strip()
            if t:
                assert items
                items[-1] += f' {t}'

    return items


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--db', default='main.16.se.svenskaakademien.so16.obb')
    args = parser.parse_args()

    conn = sqlite3.connect(args.db)
    lemmas = []
    lemma = {}
    for row in conn.execute('SELECT article, code, data FROM so WHERE article IN (select article from so WHERE code == 77 AND data LIKE \"%el\") ORDER by rowid'):
        article, code, data = row

        if code == 12:
            lemma['pos'] = data
        elif code == 40:
            assert not lemma
            lemma = {'article': article}
        elif code == 41:
            try:
                lemma['pronunciation']

                # See for example ACCEPTABEL where the accent marker is only in the heading
                if '`' in lemma['word'] or '´' in lemma['word']:
                    lemma['pronunciation'][0] += ' ' + lemma['word']
            except KeyError:
                lemma['pronunciation'] = [lemma['word']]

            lemma['word'] = lemma['word'].replace('´', '').replace('`', '')

            try:
                lemma['hyphenation']
            except:
                lemma['hyphenation'] = lemma['word']

            lemma['compound'] = any(c in lemma['hyphenation'] for c in ('|', '-'))

            lemmas.append(lemma)
            lemma = []
        elif code == 49:
            lemma['declension'] = data
        elif code == 77:
            lemma['word'] = data
        elif code == 85:
            lemma['pronunciation'] = parse_pronunciation(data)
        elif code == 100:
            lemma['hyphenation'] = data
        elif code == 104:
            try:
                lemma['definition'].append(data)
            except KeyError:
                lemma['definition'] = [data]

    print(json.dumps(lemmas, indent=4))


if __name__ == '__main__':
    main()
