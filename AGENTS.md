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
testdata/                                   Golden fixtures (.html/.json) for parser tests
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
  (excluded from Gson output).
- **`WordActivity.kt`** — fetches a word and, when `word.renderAsJson` is true,
  serializes the `Word` with Gson and injects it into
  `assets/word_template.html` via `loadWord(...)`. Otherwise it keeps the
  original HTML fragments (`getPage()`) for non-JSON dictionaries.
- **`assets/renderer.js`** — builds the DOM from the JSON word object
  (`renderWord(word)` -> `$('#content').html(...)`).
- **`assets/word.js`** — turns words inside definitions/examples into
  `/search/...` links (`createLinks`). Needs to keep working if selectors in
  `renderer.js` change.
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
  `title`, e.g. `América`), `examples` (`.ejemplo`). Idioms carry
  `grammar`, `geo`, and `examples` too.

Relevant files: `EstParser.kt`, `EstDictionary.kt`, `Word.kt`,
`assets/renderer.js`, `assets/renderer.css`, `EstParserTest.kt`,
`EstIntegrationTest.kt`, `testdata/est.{html,json,search.json}`.

## DLE dictionary (RAE Diccionario de la lengua española)

Same pattern as EST but that site has no structured domain/geo markup: the
parser just snapshots whole `<li>` fragments and the markers are embedded in
the definition text. Tests: `DleParserTest.kt`, `DleIntegrationTest.kt`,
fixtures `testdata/dle.{html,json,search.json}`.

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
- Do not commit `local.properties`, `app/src/test/js/node_modules/`, or build
  output (`.gitignore` already covers `build/`, `.gradle/`, `local.properties`;
  `testdata/` is intentionally checked in).