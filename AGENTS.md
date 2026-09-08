# AGENTS.md

Guidance for AI agents working in this repository.

## Project overview

Nordict is an Android (Kotlin) dictionary app. It aggregates many online
dictionaries (Swedish, Danish, French, Portuguese, Spanish) and renders their
HTML entries in a WebView, adding cross-linking between words, pronunciation
autoplay, gender highlighting, and AnkiDroid integration.

There are no Kotlin libraries beyond the Android SDK, `org.jsoup` for HTML
parsing, and Gson for JSON serialization. JS assets run inside an Android
WebView; the jQuery-based rendering code is separated out and unit-tested with
Node/Jest.

## Layout

```
app/src/main/java/se/whitchurch/nordict/   Kotlin source (single package)
app/src/main/assets/                        WebView assets (HTML/JS/CSS/jquery)
app/src/test/java/se/whitchurch/nordict/   Robolectric unit + MockWebServer tests
app/src/test/js/                            Jest tests + CLI for the JS renderer
app/src/androidTest/java/...                Instrumented tests (WordTest.kt)
testdata/                                   Golden fixtures (.html/.json) for parser tests (separate git repo; gitignored here)
tools/                                      Standalone python scripts (crawl.py, parse.py, ...)
```

## Core architecture

- **`Dictionary.kt`** — interface for a dictionary (`tag`, `lang`,
  `search(query)`, `get(uri)`). Concrete impls are named `<Name>Dictionary.kt`
  (e.g. `EstDictionary`, `DleDictionary`, `DdoDictionary`).
- **`Ordboken.kt`** — dictionary registry (`dictMap` keyed by `tag`) and the
  app entry point for lookups.
- **`<Name>Parser.kt`** — companion-object parsers that take a raw HTML page,
  `Uri`, and dict `tag`, and return `List<Word>`. They use Jsoup and clone the
  fragments they keep in `Word.element`.
- **`Word.kt`** — the model serialized to JSON. `Word.Definition` and
  `Word.Idiom` are nested classes; `element`/`lemma` fields are `@Transient`
  (excluded from Gson output). `Word.Synonym` carries the display `text`, an
  `href` (the full source link target, e.g. a RAE DLE `?id=` deep-link), and a
  `plev` marker (the DLE `abbr.sin_alert` title, e.g. "malsonante").
- **`WordActivity.kt`** — fetches a word and, when `word.renderAsJson` is true,
  serializes the `Word` with Gson and injects it into
  `assets/word_template.html` via `loadWord(...)`. Otherwise it keeps the
  original HTML fragments (`getPage()`) for non-JSON dictionaries.
- **`assets/renderer.js`** — builds the DOM from the JSON word object
  (`renderWord(word)` -> `$('#content').html(...)`).
- **`assets/word.js`** — turns words inside definitions/examples into
  `/search/...` links (`createLinks`). Synonyms rendered as `<a>` anchors by
  `renderer.js` are skipped (the regex already skips anchor content). Needs
  to keep working if selectors in `renderer.js` change.
- **`assets/renderer.css`** — styling for the JSON-rendered content
  (`span.grammar`/`domain`/`geo`, `ol.definitions`, `ul.idiom-list`, gender
  backgrounds, small-screen layout). Loaded by `word_template.html`.
- **`assets/word.css`** — styling/overrides for legacy dictionaries that render
  original HTML from their sources (loaded in `WordActivity.loadWebView` on the
  non-JSON path).

### The JSON rendering + testing pipeline (what most parser work touches)

1. Parser (e.g. `EstParser.parse`) builds `Word`s with `renderAsJson = true`.
2. Golden test `EstParserTest` parses `testdata/est.html`, maps `Word`s onto
   plain `WordData`/`DefinitionData`/`IdiomData` data classes, **rewrites**
   `testdata/est.json`, then reads it back and asserts equality. So updating the
   parser means the fixture JSON gets regenerated on the next run; the data
   classes in the test must mirror any new `Word` fields (and their Gson field
   order).
3. `renderer.js` renders that JSON in the app. Its output must match `word.js`
   selectors and be styled by `renderer.css`.
4. `renderer.test.js` (Jest + jsdom) verifies the DOM produced by `renderWord`.

## Commands

### Kotlin unit tests (Robolectric)

```sh
./gradlew testDebugUnitTest                              # all
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.EstParserTest'
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.EstIntegrationTest'
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.DleParserTest'
```

Parser tests read fixtures relatively as `../testdata/<name>.json` (they run in
`app/` working dir). Integration tests spin up a MockWebServer serving
`testdata/<tag>-search.json` / `testdata/<tag>.html`.

The parser tests (`CollinsParserTest`, `EstParserTest`, `DleParserTest`) use a
true golden pattern via the shared `Goldens.assertGolden(...)` helper
(`Goldens.kt`): the parsed output is asserted against the committed JSON
fixture (`testdata/colspan/`, `testdata/est/`, `testdata/dle/`) and is never
rewritten in normal runs. When parser behavior changes intentionally,
regenerate the fixtures with

```sh
UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.CollinsParserTest'
```

(or any parser test class), then review the git diff; keep the test's semantic
assertions in sync.

### JS renderer tests

```sh
cd app/src/test/js
npm test                  # jest
npm run render -- testdata-path /tmp/out.html   # cli.js, preview in browser
```

`npm run render` (i.e. `node cli.js`) takes a JSON file (same schema as
`testdata/est.json`) and emits a standalone HTML file with all assets inlined,
for browser preview.

### Instrumented tests

```sh
./gradlew connectedAndroidTest
```

## EST dictionary (RAE Diccionario del estudiante)

- `EstDictionary`: search hits the JSON API `/srv/keys?q=<query>`; parse
  fixtures in `testdata/est-search.json`. `get(uri)` fetches HTML, calls
  `EstParser.parse`, and resolves homographs via the `__ref` query param.
- `EstParser` flow: isolate `#resultados`, strip `.verDLE`, re-parse, expand
  every `<abbr>` to its `title` text (e.g. `Meteor.` -> `meteorología`, `f.` ->
  `nombre femenino`), then iterate `<article>` lemmas.
- Each lemma is one `Word`; the page may contain multiple `article`s
  (homographs).
- Definitions are the lemma's direct `> div.acep` children; idioms come from
  `.locs .fc` -> `.acep`. Fields captured per definition: `grammar` (`.gram`),
  `domain` (`.domain` abbr `title`, e.g. `meteorología`), `geo` (`.geo` abbr
  `title`, e.g. `América`), `plev` (`.plev` abbr `title`, e.g. `malsonante`),
  `examples` (`.ejemplo`). Idioms carry
  `grammar`, `geo`, `plev`, and `examples` too.

Relevant files: `EstParser.kt`, `EstDictionary.kt`, `Word.kt`,
`assets/renderer.js`, `assets/renderer.css`, `EstParserTest.kt`,
`EstIntegrationTest.kt`, `testdata/est.{html,json,search.json}`,
`testdata/est/cagar.{html,json}` (golden test for the `plev` "malsonante"
marker: 4 definitions + 3 idioms). `testdata/est/muerte.{html,json}`
(regression test: the idiom loop must select `div.acep` only — the
`<a class="acep">` cross-reference anchors would otherwise produce ghost
zero-gloss idiom duplicates; also covers relative `a.synon` hrefs resolving
against the base URL). The `.sols` sub-entries on that page (`muerte natural`,
`muerte violenta`) are parsed as separate headword `Word`s with their own
`__ref` (resolvable via `EstDictionary.get` from both the search-result URL
and the homograph link), not as trailing definitions of the parent lemma.

## DLE dictionary (RAE Diccionario de la lengua española)

Same pattern as EST but that site has no structured domain/geo markup: the
parser just snapshots whole `<li>` fragments and the markers are embedded in
the definition text. Tests: `DleParserTest.kt`, `DleIntegrationTest.kt`,
fixtures `testdata/dle.{html,json,search.json}`.

Synonyms in the DLE footer (`.c-word-list__items .sin`) are parsed into
structured `Word.Synonym` objects: `text` is the display form, `href` is the
full deep-link back to the RAE article (from the `data-id` attribute,
`https://dle.rae.es/?id=<id>`), and `plev` is populated from
`abbr.sin_alert` (NOT the parent `<span title="...">`), so only "malsonante"
markers appear — "uso coloquial" / "usado en América" are dropped.

## Conventions / gotchas

- All Kotlin source is in one package, `se.whitchurch.nordict`, in both
  `main` and `test`.
- Unit tests use Robolectric (`@RunWith(RobolectricTestRunner::class)`,
  `@Config(sdk = [28])`) because Android classes (e.g. `Uri`) are involved.
- `Word` fields are read by `renderer.js` by exact JSON name; renaming fields
  in `Word.kt` requires updating the data classes in `EstParserTest.kt`,
  `testdata/est.json`, and `renderer.js`/its tests.
- The `definitions`/`idioms` lists in `Word` carry plain fields only; jsoup
  `Element`s are `@Transient` and never reach the renderer.
- `renderer.js` accepts both plain strings and structured `{text, href, plev}`
  objects for synonyms. DLE emits structured synonyms (deep-link `<a>` anchors
  to `https://dle.rae.es/?id=<id>`); EST emits structured synonyms too — the
  `href` comes from the `a.synon` anchor (resolved to an absolute
  `https://www.rae.es/diccionario-estudiante/<word>` URL when the page uses a
  relative href), so it doesn't rely on `word.js` auto-linking.
- Do not commit `local.properties`, `app/src/test/js/node_modules/`, or build
  output (`.gitignore` already covers `build/`, `.gradle/`, `local.properties`,
  `testdata/`). `testdata/` lives in a separate git repo to avoid distributing
  original dictionary pages with the app code.