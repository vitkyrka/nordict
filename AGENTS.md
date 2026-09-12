# AGENTS.md

Guidance for AI agents working in this repository.

## Project overview

Nordict is an Android (Kotlin) dictionary app. It aggregates many online
dictionaries (Swedish, Danish, French, Portuguese, Spanish) and renders their
HTML entries in a WebView, adding cross-linking between words, pronunciation
autoplay, gender highlighting, and AnkiDroid integration.

There are no Kotlin libraries beyond the Android SDK, `org.jsoup` for HTML
parsing, Gson for JSON serialization, and OkHttp (its `HttpUrl` is used as the
JVM-neutral URL type in the shared module). JS assets run inside an Android
WebView; the jQuery-based rendering code is separated out and unit-tested with
Node/Jest.

The parsing core is a pure-JVM module (`:core`) shared by the Android app and a
desktop CLI (`:cli`), so the same parser code runs headlessly on a laptop. The
Android app keeps `android.net.Uri` at the UI boundary and converts to
`okhttp3.HttpUrl` for anything touching the shared model
(`app/.../HttpUrlBridge.kt`).

## Layout

```
core/src/                               Shared PURE-JVM parser core (no Android)
  main/java/...            Word, SearchResult, DleParser, EstParser, CollinsParser,
                           KeyItemSearchResults, WordJson (golden-schema JSON
                           mapping), Genders, Pos
  test/java/...            DleParserTest, EstParserTest, CollinsParserTest
                           (plain JUnit, no Robolectric), Goldens
cli/src/main/...                        Desktop CLI (application) using :core
app/src/main/java/...      Android-only Kotlin (dictionaries, activities, UI)
app/src/main/assets/        WebView assets (HTML/JS/CSS/jquery)
app/src/test/java/...      Robolectric unit + MockWebServer tests
app/src/test/js/            Jest tests + CLI for the JS renderer
app/src/androidTest/java/...                Instrumented tests (WordTest.kt)
testdata/                   Golden fixtures (.html/.json) for parser tests (separate git repo; gitignored here)
tools/                      Standalone python scripts (crawl.py, parse.py, ...)
```

## Core architecture

- **`Dictionary.kt`** — interface for a dictionary (`tag`, `lang`,
  `search(query)`, `get(uri)`). Concrete impls are named `<Name>Dictionary.kt`
  (e.g. `EstDictionary`, `DleDictionary`, `DdoDictionary`).
- **`Ordboken.kt`** — dictionary registry (`dictMap` keyed by `tag`) and the
  app entry point for lookups. Also builds the action bar's two-row navigation
  in `onResume`: a language row (`langRadio`) with one flag-only button per
  language, and a dictionary row (`dictRadio`, ids `radioScroll`/`dictRadio`)
  showing only the selected language's dictionaries with full tags
  (e.g. `DLE`). Language buttons carry `tag`/`contentDescription` = lang code;
  dict buttons carry `tag` = global index into `dictionaries`. Each dictionary's
  last selection is remembered per language in prefs under `dictIndex_<lang>`
  (falls back to the first dict of a language); `currentIndex` remains the
  global index.
- **`<Name>Parser.kt`** — companion-object parsers that take a raw HTML page,
  `okhttp3.HttpUrl`, and dict `tag`, and return `List<Word>`. They use Jsoup and
  clone the fragments they keep in `Word.element`. DLE, EST, and Collins all
  live in `:core` with `HttpUrl`-typed uris (the remaining app-side parsers
  still take `android.net.Uri`). Each also exposes a `parseSearch(body, uriOf)`
  that decodes that dictionary's search-endpoint JSON into `List<SearchResult>`
  (the RAE pair share the `KeyItemSearchResults` `/srv/keys` decoder); the
  app-side `<Name>Dictionary.search()` and the CLI both call it so there is one
  shared mapping (the app only builds the endpoint URL and the result `uri`).
- **`core/.../Word.kt`** — the model serialized to JSON. Lives in the shared
  `:core` module; `uri`/`baseUrl` are `okhttp3.HttpUrl` / plain `String` so the
  class runs on a desktop JVM. `Word.Definition` and
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

### Shared-core tests + desktop CLI

The DLE, EST, and Collins parsers and the golden JSON mapping live in `:core`
(pure JVM — no Android, no Robolectric):

```sh
./gradlew :core:test                          # DleParserTest/EstParserTest/CollinsParserTest
./gradlew :core:test --tests se.whitchurch.nordict.EstParserTest
```

The `:cli` module runs the *same* parsers against arbitrary dictionary pages and
dumps the shared JSON schema (identical to `testdata/{dle,est,colspan}/*.json`):

```sh
./gradlew :cli:run --args="frente"                                  # default dict DLE: dle.rae.es/frente
./gradlew :cli:run --args="est frente"                              # RAE Diccionario del estudiante
./gradlew :cli:run --args="colspan frente"                          # Collins Spanish-English
./gradlew :cli:run --args="frente --search"                         # search results (default DLE)
./gradlew :cli:run --args="est frente --search"                     # search via a dictionary
./gradlew :cli:run --args="--dict colspan --search --file ../testdata/colspan-search.json"  # offline search
./gradlew :cli:run --args="--dict est --file ../testdata/est/morir.html"   # offline, no network
./gradlew :cli:run --args="--url https://dle.rae.es/cagar"
./gradlew :cli:run --args="frente -o /tmp/frente.json"              # write to file
```

Positional first arg selects the dict (default `dle`; aliases: `est`,
`colspan`/`col`); `--dict <name>` also works. Collins slugs turn spaces into
hyphens (`colspan ley de la gravedad`). `--search` dumps search-result JSON (an
array of `{mTitle, mSummary, uri}`) from the dictionary's autocomplete endpoint
(DLE/EST `srv/keys`, Collins `autocomplete/`) instead of a word page; it
composes with `--url`/`--file`/`-o`. Search responses are decoded by the same
per-dictionary parsers the app uses — `DleParser.parseSearch`/`EstParser.parseSearch`
(the RAE `/srv/keys` shape, via the shared `KeyItemSearchResults`) and
`CollinsParser.parseSearch` (the `/autocomplete/` `{"title"}` shape) — so the
app, the CLI, and the `*ParserTest.kt` suites lock one mapping against
`testdata/{dle,est,colspan}-search.json`. Note: collinsdictionary.com serves a
Cloudflare JS challenge to datacenter IPs, so live `colspan` fetches can 403
from this machine — use `--file` against the fixtures instead (the parser
itself is fully covered by tests).

JSON goes to stdout (summary on stderr; nonzero exit on failure). Pipe the
output to the JS renderer for a browser preview:
`cd app/src/test/js && npm run render -- /tmp/frente.json`.

### Kotlin unit tests (Robolectric)

```sh
./gradlew testDebugUnitTest                              # all
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.EstIntegrationTest'
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.DleIntegrationTest'
```

The parser tests (`DleParserTest`, `EstParserTest`, `CollinsParserTest`) all
run in `:core` as plain JUnit and read fixtures relatively as
`../testdata/...` (working dir `core/`). App-side integration tests spin up a
MockWebServer serving `testdata/<tag>-search.json` / `testdata/<tag>.html`.

The parser tests use a true golden pattern via the shared
`Goldens.assertGolden(...)` helper (`core/.../Goldens.kt`): the parsed output
is asserted against the committed JSON fixture (`testdata/dle/`,
`testdata/est/`, `testdata/colspan/`) and is never rewritten in normal runs.
When parser behavior changes intentionally, regenerate the fixtures with

```sh
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.DleParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.EstParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.CollinsParserTest'
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
for browser preview. A list of words (the parser-golden shape, e.g.
`testdata/colspan/frente.json`) is treated as a homonym set and rendered as
the combined page with per-heading nav rows, exactly like the app does.

### Instrumented tests

```sh
./gradlew connectedAndroidTest
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=se.whitchurch.nordict.NavigationTest   # one class
```

`NavigationTest` (Espresso) launches `MainActivity`, clears the app prefs and
`Ordboken` singleton, and exercises the two-row language/dictionary nav
(per-language selection memory, restore on recreate). Requires a connected
device/emulator. The androidTest androidx.test dependencies are pinned to
versions that work on current Android (espresso 3.6.1 / runner 1.6.2 etc.);
older ones crash with a `PendingIntent` FLAG_IMMUTABLE error on Android 12+.

### Deploying and verifying on a device

```sh
./gradlew assembleDebug                              # build debug APK
# APK ends up in: app/build/outputs/apk/debug/app-debug.apk
adb devices                                          # find the connected device serial
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell monkey -p se.whitchurch.nordict -c android.intent.category.LAUNCHER 1   # launch
```

Package / launch activity is `se.whitchurch.nordict` / `.MainActivity`. A
physical phone typically shows up over adb-over-TLS
(e.g. `adb-RFCY10MKMMD-...._adb-tls-connect._tcp` series).

To inspect the running UI without eyes on the device, the `android` CLI works
best when the serial is a TLS one (the plain `uiautomator dump` can silently
fail while a WebView is on screen):

```sh
android --sdk=$ANDROID_HOME layout --device <serial>   # interactive UI tree as JSON (text/bounds/state)
android --sdk=$ANDROID_HOME screen capture -o /tmp/out.png  # screenshot (visual, if the model can read images)
```

When interacting via raw taps, read the element bounds/centers from the layout
dump and tap with `adb shell input tap X Y`; re-dump after each action to
confirm state changes. Beware that `adb shell input text` appends to whatever
is already in a focused field — use the SearchView's "Clear query" (X) button
instead of trying to delete characters, or select-all (`input keyevent --meta
CTRL_ON 29`) + delete.

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

Relevant files: `core/.../EstParser.kt`, `EstDictionary.kt`, `Word.kt`,
`assets/renderer.js`, `assets/renderer.css`,
`core/.../EstParserTest.kt`,
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
the definition text. Tests: `DleParserTest.kt` (in `:core`, plain JUnit),
`DleIntegrationTest.kt` (in `app`), fixtures `testdata/dle.{html,json,search.json}`.

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
  The shared `:core` tests (e.g. `DleParserTest`, `EstParserTest`,
  `CollinsParserTest`) are plain JUnit and run on a desktop JVM.
- `Word` fields are read by `renderer.js` by exact JSON name; renaming fields
  in `Word.kt` requires updating the golden-schema data classes in
  `core/.../WordJson.kt`, the `testdata/{dle,est,colspan}/` fixtures, and
  `renderer.js`/its tests.
- Multi-entry pages: when a JSON dictionary page yields more than one word
  (RAE homographs/.sols sub-entries, Collins POS-group homs) each `Word`
  carries a serializable `mHomonymEntries` list `HomonymEntry(mTitle, ref,
  ...)` snapshotting every page entry (page order, itself included), filled
  by `Word.homonymEntries(...)` in `EstParser`/`DleParser`/`CollinsParser`.
  `renderer.js` draws all entries stacked on one page, with a nav row above
  each heading (`#hom-N` anchors) listing every entry (duplicate titles get
  RAE-style ordinals) and bolding the one that follows the row. Legacy
  (non-JSON) dictionaries leave the list empty and keep the OS-level homograph
  strip (`WordActivity.loadHomographs`, now skipped for `renderAsJson` words).
  The word-view WebView sits `wrap_content` inside a `LockableNestedScrollView`
  (`activity_word.xml`, id `scroll_view`). For `renderAsJson` words,
  `WordActivity.loadWebView` locks the outer view and sizes the WebView to the
  viewport (`pinWebViewToViewport`), so the WebView scrolls internally and the
  `#hom-N` anchors jump reliably; otherwise (legacy dictionaries) the outer
  view scrolls the whole page (`unpinWebView` unlocks both).
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