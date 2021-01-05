#!/usr/bin/env python3

import sys
import json

import jinja2


def main():
    lemmas = json.load(sys.stdin)

    template = jinja2.Environment().from_string(source='''
{% for lemma in lemmas %}
    <h2><a href="https://svenska.se/so/?sok={{lemma.word}}">{{lemma.word}}</a> <small>{{lemma.declension|escape}} <i>{{lemma.pos}}</i></small><br>{{lemma.pronunciation|escape}}</h2>

    {% for def in lemma.definition %}
        <li>{{def}}</li>
    {% endfor %}
{% endfor %}

''')
    print(template.render(lemmas=lemmas))


if __name__ == '__main__':
    main()
