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

import itertools
import genanki
import attr


class MinimalPairNote(genanki.Note):

    @property
    def guid(self):
        return genanki.guid_for(self.fields[0])


@attr.s
class Feature(object):
    slug = attr.ib()
    title = attr.ib()
    key = attr.ib()


def make_deck(deckid, model, feature, words):
    deck = genanki.Deck(deckid, feature.title)

    for key, g in itertools.groupby(sorted(words, key=feature.key), key=feature.key):
        similar = list(g)
        if len(similar) == 1:
            continue
    
        print(key)
        similar = sorted(similar, key=lambda w:w['text'])
        print(similar)

        extra = '<br>'.join([f'''
<div style="vertical-align: middle; font-size: 1em; font-family: Arial">
    [{s["text"]}]&nbsp;
    <audio style="vertical-align: middle;" src="{s["audio"]}" controls></audio>
</div>
        ''' for s in similar])

        for s in similar:
            note = MinimalPairNote(model=model, fields=[s['text'], s['audio'], extra])
            deck.add_note(note)

    return deck

def main():
    parser = argparse.ArgumentParser()
    args = parser.parse_args()

    model = genanki.Model(
            812794318729,
            'Minimal pair',
            fields=[
                {'name': 'text'},
                {'name': 'audio'},
                {'name': 'extra'},
            ],
            templates=[
                {
                    'name': 'Card 1',
                    'qfmt': '''
<center><audio autoplay controls src="{{audio}}"></audio></center>
''',
                    'afmt': '''
<hr id="answer">
<center>
<audio autoplay controls src="{{audio}}"></audio>
<div style="font-size: 3em; font-family: Arial;">[{{text}}]</div>

<p>{{extra}}
</center>
''',
                },
            ])

    with open('words.json', 'r') as f:
        words = json.load(f)

    features = [
        Feature(slug='stød', title='Stød', key=lambda w:w['text'].replace('ˀ', '')),
        Feature(slug='front1', title='[e] / [ε]', key=lambda w:re.sub('(e|ε)ː?', 'X', w['text'])),
        Feature(slug='front2', title='[ε] / [εj] / [æ]', key=lambda w:re.sub('(εj|æː?|εː?)', 'X', w['text'])),
    ]

    withoutstod = lambda w:w['text'].replace('ˀ', '')
    # withoutstod = lambda w:re.sub('(εj?|æː?)', 'X', w['text'])
    withoutstod = lambda w:re.sub('(εj|æː|ε|æ|e)', 'X', w['text'])

    for i, feature in enumerate(features):
        deckid = 19874192732 + i
        deck = make_deck(deckid, model, feature, words)
        genanki.Package(deck).write_to_file(feature.slug + '.apkg')

    return

if __name__ == '__main__':
    main()
