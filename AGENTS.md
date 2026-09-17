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
  main/java/...            Word, SearchResult, Dictionary (abstract), all
                           <Name>Dictionary.kt, <Name>Parser.kt, HttpUrlExt
                           (URL helpers), Goldens, WordJson, Genders, Pos
test/java/...            DleParserTest, EstParserTest, CollinsParserTest,
                            ColfrenParserTest, DiccionariParserTest,
                            LeRobertParserTest, LingueeParserTest,
                            InfopediaParserTest, WiktionaryParserTest,
                            DdoParserTest, the moved *IntegrationTest
                            suite (plain JUnit, no
                            Robolectric), Goldens
cli/src/main/...                        Desktop CLI (application) using :core
app/src/main/java/...      Android-only Kotlin (Ordboken registry, activities,
                           UI, Flags.kt — the flagCode → R.drawable mapping)
app/src/main/assets/        WebView assets (HTML/JS/CSS/jquery)
app/src/test/java/...      Robolectric unit + MockWebServer tests
app/src/test/js/            Jest tests + CLI for the JS renderer
app/src/androidTest/java/...                Instrumented tests (WordTest.kt)
testdata/                   Golden fixtures (.html/.json) for parser tests (separate git repo; gitignored here)
tools/                      Standalone python scripts (crawl.py, parse.py, ...)
```

## Core architecture

- **`Dictionary.kt`** — abstract base class for a dictionary in `:core`
  (`tag`, `lang`, `flagCode` — a JVM-neutral `"se"`/`"dk"`/`"sedk"`/`"es"`/
  `"ca"`/`"pt"`/`"fr"` key, NOT a `R.drawable`), `search(query)`,
  `fullSearch(query)`, `get(uri: HttpUrl)`, a `java.util.logging.Logger` (`log`,
  lazy — the `tag` override isn't initialized when the base constructor runs)
  and `fetch(pageUrl)`. Concrete impls are named `<Name>Dictionary.kt` (e.g.
  `EstDictionary`, `DleDictionary`, `DdoDictionary`) and all live in `:core`;
  they take `okhttp3.OkHttpClient` and return/take `okhttp3.HttpUrl` so they
  run on a desktop JVM. The Android app keeps `android.net.Uri` at the UI
  boundary (see `HttpUrlBridge`) and maps `flagCode` → drawable via
  `Flags.kt`/`Ordboken.get`. The DLE, EST, Collins (sp/en + fr/en),
  diccionari.cat family, Linguee, Infopedia, Le Robert, Wiktionary, and SO
  dictionaries take an optional `baseUrl`; DDO/SDO share `DslDictionary`,
  parameterized with default `apiBaseUrl`/`siteBaseUrl` (the
  `ws.dsl.dk`/`ordnet.dk` hosts) so a single MockWebServer can stand in for
  both in tests. The word cache in `Ordboken` is keyed on `HttpUrl`.
- **`Ordboken.kt`** — dictionary registry (`dictMap` keyed by `tag`), the app
  entry point for lookups (`getWord(uri)`, cached, probes every dictionary; and
  `search(query, count)`, cached per `currentIndex`), and the persisted
  `lastWhere`/`lastWhat`/`currentIndex` state. `currentIndex` is Compose state
  (`mutableStateOf`), so the Compose `DictionaryNav` rows and the search-bar
  suggestions recompose when the dictionary changes. Each language's selection
  is remembered in prefs under `dictIndex_<lang>` (falls back to the first dict
  of a language); `currentIndex` remains the global index. `setLanguage(lang)`
  / `setCurrentDictionary(index)` are the single switching path shared by the
  nav rows and the agent driver.
- **`MainActivity.kt` + `AppNavHost.kt`** — the whole app is one activity: a
  global MD3 `SearchBar` (debounced live suggestions from `Ordboken.search`),
  the `DictionaryNav` rows, and a Navigation-Compose `NavHost` with three
  destinations — `home` (history), `search?query=`, and
  `word?uri=&title=`. `MainActivity` sets the initial route from the persisted
  `lastWhere` (fresh install -> Home) and honors a `data:`-style intent by
  routing straight to the word destination.
- **`WordScreen.kt`** — the word destination: `WordViewModel` (scoped to the
  word `NavBackStackEntry` via `viewModel(entry)`, so stacked word views keep
  independent state like the old activities). It fetches the word through
  `Ordboken.getWord`, serializes the `Word` with Gson, and injects it into
  `assets/word_template.html` via `loadWord(...)`. The WebView is pinned to
  the viewport height and owns its own scroll (reliable `#hom-N` anchors).
  The docked word action bar is an M3
  `BottomAppBar` (`BottomAppBarDefaults.exitAlwaysScrollBehavior`) overlaid on
  the WebView, which runs behind it to the screen bottom; the bar collapses on
  a downward scroll and returns on an upward one — driven through
  `WordViewModel.webViewScrolled` (a `WebView.OnScrollChangeListener` bridge,
  since the pinned WebView's internal scroll is invisible to Compose);
  `WordViewModel.captureScroll`/`restoreWebViewScroll` snapshot the WebView's
  scroll offset across configuration changes and the collapse state.
  Owns the WebView, an `ExoPlayer`, and the
  oracle history SQLite writes; navigation side effects flow out through
  `onOpenUri`/`onOpenExternal`/`onFillSearch` callbacks.
- **`<Name>Parser.kt`** — companion-object parsers that take a raw HTML page,
  `okhttp3.HttpUrl`, and dict `tag`, and return `List<Word>`. They use Jsoup.
  Every parser feeds the JSON rendering pipeline (`Word` -> `renderer.js`). Each
  also exposes a `parseSearch(body, uriOf)`
  that decodes that dictionary's search-endpoint JSON into `List<SearchResult>`
  (the RAE pair share the `KeyItemSearchResults` `/srv/keys` decoder); the
  app-side `<Name>Dictionary.search()` and the CLI both call it so there is one
  shared mapping (the app only builds the endpoint URL and the result `uri`).
- **`core/.../Word.kt`** — the model serialized to JSON. Lives in the shared
  `:core` module; `uri` is an `okhttp3.HttpUrl` so the class runs on a desktop
  JVM. `Word.Definition` and
  `Word.Idiom` are nested classes; their `element`/`lemma` fields are `@Transient`
  (excluded from Gson output). `Word.Synonym` carries the display `text`, an
  `href` (the full source link target, e.g. a RAE DLE `?id=` deep-link), and a
  `plev` marker (the DLE `abbr.sin_alert` title, e.g. "malsonante").
- **`assets/renderer.js`** — builds the DOM from the JSON word object
  (`renderWord(word)` -> `$('#content').html(...)`).
- **`assets/word.js`** — turns words inside definitions/examples into
  `/search/...` links (`createLinks`). Synonyms rendered as `<a>` anchors by
  `renderer.js` are skipped (the regex already skips anchor content). Needs
  to keep working if selectors in `renderer.js` change.
- **`assets/renderer.css`** — styling for the JSON-rendered content
  (`span.grammar`/`domain`/`geo`, `ol.definitions`, `ul.idiom-list`, gender
  backgrounds, small-screen layout). Loaded by `word_template.html`.

### The JSON rendering + testing pipeline (what most parser work touches)

1. Parser (e.g. `EstParser.parse`) builds `Word`s with the JSON schema.
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
./gradlew :core:test                          # DleParserTest/EstParserTest/CollinsParserTest/LeRobertParserTest/…
./gradlew :core:test --tests se.whitchurch.nordict.EstParserTest
./gradlew :core:test --tests se.whitchurch.nordict.ColfrenParserTest   # Collins French-English
```

The `:cli` module runs the *same* parsers against arbitrary dictionary pages and
dumps the shared JSON schema (identical to `testdata/{dle,est,colspan,colfren}/*.json`):

```sh
./gradlew :cli:run --args="frente"                                  # default dict DLE: dle.rae.es/frente
./gradlew :cli:run --args="est frente"                              # RAE Diccionario del estudiante
./gradlew :cli:run --args="colspan frente"                          # Collins Spanish-English
./gradlew :cli:run --args="colfren table"                           # Collins French-English
./gradlew :cli:run --args="gdlc cap"                                # GDLC (diccionari.cat, monolingual Catalan)
./gradlew :cli:run --args="ca-es taula"                             # català-castellà (diccionari.cat)
./gradlew :cli:run --args="ca-en taula --search"                    # català-anglès autocomplete
./gradlew :cli:run --args="wfr table"                               # French Wiktionary
./gradlew :cli:run --args="lingpt mesa"                             # Linguee pt-en
./gradlew :cli:run --args="infopedia mesa"                          # Infopédia pt
./gradlew :cli:run --args="rob table"                               # Le Robert fr
./gradlew :cli:run --args="so hus"                                  # SO is search-first (no headword URL)
./gradlew :cli:run --args="so hus --search"                         # ... so list entries, then --url <result>
./gradlew :cli:run --args="sdo hus --search"                        # Svensk ordbok (search-first)
./gradlew :cli:run --args="ddo hus --search"                        # Den Danske Ordbog (search-first)
./gradlew :cli:run --args="frente --search"                         # search results (default DLE)
./gradlew :cli:run --args="est frente --search"                     # search via a dictionary
./gradlew :cli:run --args="--dict colspan --search --file ../testdata/colspan-search.json"  # offline search
./gradlew :cli:run --args="--dict colfren --file ../testdata/colfren/table.html"  # offline parse
./gradlew :cli:run --args="--dict colfren --search --file ../testdata/colfren-search.json"  # offline search
./gradlew :cli:run --args="--dict est --file ../testdata/est/morir.html"   # offline, no network
./gradlew :cli:run --args="--url https://dle.rae.es/cagar"
./gradlew :cli:run --args="frente -o /tmp/frente.json"              # write to file
```

Positional first arg selects the dict (default `dle`; aliases: `est`,
`colspan`/`col`, `colfren`, `so`, `sdo`, `ddo`, `lingpt`, `infopedia`, `rob`,
`wfr`, `gdlc`, `ca-es`, `ca-en`); `--dict <name>` also works. The search-first
dictionaries (`so`, `sdo`, `ddo`, and `rob`) have no word URL from a headword,
so a bare `<word>` errors with guidance: list entries with `--search` and open
one with `--url <result>`, or `--file` a local page (supply `--url` as the
parse base when the dict is search-first). Collins
slugs turn spaces into hyphens (`colspan ley de la gravedad`). `--search` dumps
search-result JSON (an array of `{mTitle, mSummary, uri}`) from the dictionary's
autocomplete endpoint (DLE/EST `srv/keys`, Collins `autocomplete/`,
diccionari.cat `search_api_autocomplete/…`) instead of a word page; it
composes with `--url`/`--file`/`-o`. Search responses are decoded by the same
per-dictionary parsers the app uses — `DleParser.parseSearch`/`EstParser.parseSearch`
(the RAE `/srv/keys` shape, via the shared `KeyItemSearchResults`),
`CollinsParser.parseSearch` (the `/autocomplete/` `{"title"}` shape), and
`DiccionariParser.parseSearch` (the diccionari.cat `{value,url,label}` shape) —
so the app, the CLI, and the `*ParserTest.kt` suites lock one mapping against
`testdata/{dle,est,colspan,colfren,gdlc,ca-es,ca-en}-search.json`. Note:
collinsdictionary.com serves a Cloudflare JS challenge to datacenter IPs, so
live `colspan`/`colfren` fetches can 403 from this machine — use `--file`
against the fixtures instead (the parser itself is fully covered by tests).

JSON goes to stdout (summary on stderr; nonzero exit on failure). Pipe the
output to the JS renderer for a browser preview:
`cd app/src/test/js && npm run render -- /tmp/frente.json`.

### Agent REPL (headless + on-device driving)

The debug build ships a loopback agent server (`app/src/debug/.../AgentServer`,
`AgentProtocol.PORT = 42837`) bound to `127.0.0.1` on the device; the CLI's
`repl` subcommand drives it. Each input line is one JSON `AgentCommand`
(`{"op": "search"|"runSearch"|"open"|"openUri"|"nextPage"|"back"|"openCards"|"createCard"|"audio"|"setDict"|"setLang"|"swapLang"|"state"|"quit",
"query"?, "uri"?, "url"?, "tag"?, "lang"?, "index"?}`); each produces exactly one JSON
`AgentResult` (`ok`, `error`, optional `state`/`word`), in order, over a
persistent session until EOF or `quit`. `"word"` carries the loaded word's
`mTitle`, `uri`, `xrefs` and a per-entry `selected` index. Ops run against the
app's live `Ordboken` + the single `MainActivity` navigation graph and are
exercised by `AppDriverTest` (Robolectric — the app is one activity, so the
word-view ops run end-to-end under Robolectric) and the on-device E2E;
word-view ops never involve `startActivity` because all their routes live in
one activity. `search` is the headless autocomplete list; `runSearch` is the
UI path behind a search-bar enter — it navigates to the `search` destination
(where `fullSearch` renders the current dictionary's results) and waits for the
route to land, so a results-screen composition crash surfaces on the op. On the
headless CLI driver (no UI) `runSearch` resolves the same result list.

The card ops drive the AnkiDroid `CardActivity` (which *is* a separate
activity, launched with the word view's "add card" intent): `openCards`
launches it for the current word with `deckName = "Nordict - <dict>"` for a
single dictionary, or `"Nordict - <LANG>"` (the shared language code, e.g.
`"Nordict - ES"`) for a combined multi-dictionary word, and
waits for it to be the resumed activity; `createCard` waits for the card
screen's async word load, then creates the card for the numbered proposal
(`index`, zero-based over `Cards.proposals` — definitions first, then idioms,
default the first definition) through the same `Cards.*` pipeline the Create
button uses, reporting the new Anki note id. `debugAnkiApi`
(`CardActivity` companion) lets tests/CLI inject a fake `AnkiApi` instead of
touching a real AnkiDroid.

`audio` replays the current word's pronunciation through the word view's
ExoPlayer (the same path as the docked play button) and reports the resulting
playlist size; a `url` argument plays that URL instead of the word's own
`audio` list (for the search-first dictionaries, whose headword page rather
than the word itself carries the speaker links). Every play must start a
fresh single-item playlist — the regression that left playback working only
once (the player parked on the ended item while each tap stacked another
copy of the media item).

```sh
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> forward tcp:42837 tcp:42837        # device loopback -> host (NOT adb reverse!)
./gradlew :cli:installDist
printf '{"op":"search","query":"frente"}\n{"op":"open","query":"frente"}\n{"op":"state"}\n{"op":"quit"}\n' \
  | cli/build/install/cli/bin/cli repl --device <serial>     # run via installDist bin (gradle :cli:run doesn't forward stdin)
```

The backend starts `.MainActivity` (`am start`) so the driver has a clean task
root; `--device` retries the connection a few times to absorb cold-start
races. `state.activity` reports the current NavHost route base name
(`home`/`search`/`word`), not an activity class — `AppDriver.routeName`
projects the destination's route pattern onto it. `setDict`/`setLang` clear
the cross-link hook and switch in place, so the agent stays on the current
word view while the next `search`/`open` uses the new dictionary (they
deliberately do *not* pop the word destination: a real back on the device
would restore the previous word and immediately reopen another word view).
`nextPage` walks the homonym entries of the current word; it strips any
existing `__ref` from the loaded word's `uri` before adding the next one, or
the dictionary would resolve the first `__ref` param again. Each
`open`/`nextPage` navigates a fresh `word` destination onto the root stack, so
`back` pops to the *previous* word view (reporting "closed the word view") and
only lands on home ("left the word view") when popping the last word.
`AppDriver` `requireActivity()` returns the top resumed activity from a LIFO
`ActivityTracker` (a last-resumed pointer would read null right after the
destroy of a finished activity, since destroy callbacks run after the activity
below has already resumed).

### Kotlin unit tests

The parser and dictionary integration tests now all run in `:core` as plain
JUnit against a MockWebServer (no Robolectric, no Android):

```sh
./gradlew :core:test                                          # all core tests
./gradlew :core:test --tests 'se.whitchurch.nordict.EstIntegrationTest'
./gradlew :core:test --tests 'se.whitchurch.nordict.DleIntegrationTest'
./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.AppDriverTest'   # app-side Robolectric tests
```

The parser tests (`DleParserTest`, `EstParserTest`, `CollinsParserTest`,
`ColfrenParserTest`, `LeRobertParserTest`, `LingueeParserTest`,
`InfopediaParserTest`, `WiktionaryParserTest`) all run in
`:core` as plain JUnit
and read fixtures relatively as `../testdata/...` (working dir `core/`).
App-side integration tests spin up a MockWebServer serving
`testdata/<tag>-search.json` / `testdata/<tag>.html`.

The parser tests use a true golden pattern via the shared
`Goldens.assertGolden(...)` helper (`core/.../Goldens.kt`): the parsed output
is asserted against the committed JSON fixture (`testdata/dle/`,
`testdata/est/`, `testdata/colspan/`) and is never rewritten in normal runs.
When parser behavior changes intentionally, regenerate the fixtures with

```sh
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.DleParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.EstParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.CollinsParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.ColfrenParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.LeRobertParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.LingueeParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.InfopediaParserTest'
UPDATE_GOLDEN=1 ./gradlew :core:test --tests 'se.whitchurch.nordict.WiktionaryParserTest'
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

The launcher activity is `se.whitchurch.nordict.MainActivity`, which hosts the
whole app (Compose NavHost: home/search/word). A fresh install lands on the
Home tab; an existing `lastWhere=WORD` restores the word view. A physical
phone typically shows up over adb-over-TLS
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
is already in a focused field — use the search bar's "Clear" (X) trailing icon
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

## Collins bilingual (COLSPAN, COLFREN)

`CollinsSpanishEnglishDictionary` (COLSPAN, `spanish-english`) and
`CollinsFrenchEnglishDictionary` (COLFREN, `french-english`) share one parser,
`CollinsParser` (`:core`), parameterized by `dictCode`. Each POS-group hom
(`div.hom`) in the `benedict` main dictionary is its own renderable `Word`;
`div.hom` cross-reference stubs collapse through `data-xrentry`/`hom sense`;
Easy Learning entries (`easy`) become separate `Word`s with the leading article
stripped into `rawHeadword` ("la table" -> "table"). Multi-word search slugs are
lower-cased with spaces as hyphens (`table basse` -> `table-basse`).

Pronunciation audio is read from each block's `div.mini_h2` strip and filtered
by the source language: COLSPAN keeps only the Spain clip (`ES-ES`, dropping
the `ES-419` Latin American one), COLFREN keeps the single French clip
(`FR-…` in the main dictionary, `fr_<word>.mp3` in Easy Learning). The Spain
match is case-insensitive and also catches the lower-cased `es_es_<word>.mp3`
spelling-variant clips; a headword with no Spain clip at all falls back to
whatever audio is present (e.g. "feble", which only has the ES-419 clip).
Fixtures covering both edges: `testdata/colspan/feble.*` (single-clip
fallback) and `testdata/colspan/pócima.*` (two Spain clips: `es_es_pocima.mp3`
+ `ES-ES-W....mp3`).

Tests: `CollinsParserTest`/`CollfrenParserTest` (both `:core`, plain JUnit
goldens), `CollinsIntegrationTest`/`ColfrenIntegrationTest` (MockWebServer).
Fixtures: `testdata/{colspan,colfren}/<word>.{html,json}` and
`{colspan,colfren}-search.json`.

## diccionari.cat family (DIDAC, GDLC, CA-ES, CA-EN)

Four dictionaries share the Enciclopedia Catalana platform. DIDAC has its own
parser (`DidacParser`); the other three releases — the monolingual GDLC
(`node--type-diccionari-gdlc`) and the bilingual català-castellà (`-ca-es`) /
català-anglès (`-ca-en`) — share `DiccionariParser.parse(page, uri, tag,
nodeClass, bilingual)`.

- `- DiccionariDictionary` (app) instantiates one config per release: the
  autocomplete key (`diccionari_gdlc`, note ca-es is `diccionari_ca_es_` with a
  trailing underscore, `diccionari_ca_en`) and the `/cerca/<view>` search-path
  name (`gran-diccionari-de-la-llengua-catalana`, `diccionari-catala-castella`,
  `diccionari-catala-angles`). The CLI registers the same three via
  `diccionariDict(…)` (`gdlc`, `ca-es`, `ca-en`).
- The search view (`fullSearch`) embeds every matching entry inline, so a word
  fetch is the `/cerca/…` page; each `<article>` carries its own `about` URL
  (e.g. `/GDLC/cap1`, `/catala-castella/taula`) that becomes the word's `uri`.
- Titles come from the article `h2.node__title` (which encodes XML markers
  `<title type="display">…</title><lbl type="homograph">1</lbl>` as literal
  text) or, on a single-entry page, the page `<h1>` (sans `sup.homograph`).
- Definitions/idioms live in `<ol class="dict">`; a `<li>` opening with `<b>`
  is a locution (`Word.Idiom`), otherwise a `Word.Definition`. A locution whose
  senses sit in a nested `<ol>` becomes one idiom with as many glosses.
  `.grammar`, `.register` and `.dom` markers label the `<li>`s that follow
  them, including through wrapper `<li>` sense groups (domain/register inherit
  when an item has none). Grammar holds full Catalan labels ("masculí",
  "femení plural", "locució adverbial"); `genderOf` maps them to
  `Genders.MASCULINE`/`FEMININE` ("femenino"/"masculino") and returns `""` when
  both or neither are present.
- Monolingual GDLC extracts sentence-length `<i>` blocks as usage examples. The
  bilingual pair attaches an `<i>` usage example to the target-language
  translation that follows it ("<i>Tenir cap</i>, tener cabeza."), while short
  `<i>` markers (`m`, `f`, `sing`) and parenthesized `(o …)` variant connectors
  stay inline.
- Search results (autocomplete) decode the `{value,url,label}` label shape via
  `DiccionariParser.parseSearch`.

Fixtures: `testdata/{gdlc,ca-es,ca-en}/*.{html,json}` (word pages + goldens) and
`{gdlc,ca-es,ca-en}-search.json`. Tests: `DiccionariParserTest` (`:core`, plain
JUnit), `DiccionariIntegrationTest` (`app`, Robolectric + MockWebServer).

## SO (Svensk ordbok, published by Svenska Akademien)

`SoDictionary` (`:core`) is baseUrl-parameterized (default
`https://svenska.se`, tests use a MockWebServer) against the site's Nuxt /
FastAPI JSON API — there is no HTML word page anymore. `search()` hits
`/api/autocomplete?q=<query>&size=10` (returns `{saol,so,saob}`; only the
`so` array is decoded) and `get(uri)` fetches `/api/article/so/<l_nr>`.
`fullSearch` aliases `search`. The canonical word/search-result URI is the
raw JSON URL `https://svenska.se/api/article/so/<l_nr>`; `get()` also accepts
a main-site URL with `?activeTab=so&q=<word>&id=<l_nr>` (the `id` query param
or a trailing `/api/article/so/<id>` path segment is used). Homographs are
separate article docs, so each `get()` returns exactly one `Word` with no
`mHomonymEntries`/`__ref` (the `MultiDict.entriesFor` fallback to
`Word.homonymEntries(listOf(word))` covers this in combining), and the CLI
registers it as search-first (`wordUrl = null`). `SoDictionary` gates the
request on the base host (a foreign host → `null`).

`SoParser.parse` unwraps the document's `_source` object (or treats a bare
root as the source) and builds one JSON-rendered `Word` per article:

- **Header**: `ortografi` is `mTitle`/`mSlug`/`summary`/`rawHeadword`;
  `ordklass` maps through `normalizePos` (including `preposition`) to `Pos`
  and becomes each definition's `pos`/`grammar`; `böjning` (HTML) is jsoup-
  texted into `conjugation`; `uttal[].lemmaMedTryckangivelse` (non-null
  entries) join with " / " into `pronunciation`. Audio comes from
  `filnamnInlästUttal` (e.g. `"185690_1.mp3"`, `.m4a`→`.mp3`) →
  `https://isolve-so-service.appspot.com/pronounce?id=<filename>` (the
  filename already carries its `.mp3`, so no suffix is appended).
- **Definitions**: one per `huvudbetydelse`. The gloss is
  `definition_full` (if present) else `definition`, plus a
  `" (formkommentar.text)"` suffix when a `formkommentar` exists — this is
  the *unstyled* SO text (grammar-marked words are not case-folded like the
  site's `stam`-based styling). `register` = `bruklighetskommentar`,
  examples = `syntex`. `underbetydelser` become extra glosses (definition =
  `typ` + formkommentar suffix, examples from their `syntex`).
- **Idioms**: each `idiom[]` with a non-empty `idiombetydelser` (pure
  `hänvisning` crossref ghosts are dropped) is one `Word.Idiom`; its gloss is
  `listOfNotNull(definitionsinledare, definition, definitionstillägg)`
  space-joined, examples from `exempel`, `register` from
  `bruklighetskommentar`.
- Entry pages synthesize a per-definition jsoup `element`
  (`div.gloss > span.definition` + `div.example` rows) so `Cards.kt` Anki
  card backs aren't empty.

Tests: `SoParserTest` + `SoIntegrationTest` (both `:core`, plain JUnit
goldens + MockWebServer), fixtures `testdata/so/hus.article.json` / `testdata/so/kutter.article.json`
(raw `/api/article/so/<l_nr>` responses), `testdata/so-search.json`
(`/api/autocomplete?q=kutter&size=10`), goldens `testdata/so/hus.json` /
`testdata/so/kutter.json`. CLI: `so hus --search` (lists entries) then `so
--url https://svenska.se/api/article/so/<l_nr>`; offline `--dict so --file
../testdata/so/hus.article.json --url <entry uri>` / `--search --file
../testdata/so-search.json`.

## DDO / SDO (Den Danske Ordbog / Svensk ordbok)

`DdoDictionary` (DDO, `dk`) and `SdoDictionary` (SDO, `se`/`sedk`) share
`DslDictionary` (`:core`), parameterized with `apiBaseUrl` (default
`https://ws.dsl.dk`) and `siteBaseUrl` (default `https://ordnet.dk`), so one
MockWebServer can serve both hosts in tests (`DdoIntegrationTest`,
`SdoIntegrationTest`). `DslDictionary.get()` routes on host *and* last path
segment (`ordbog` → main-site article, `query` → API page) and re-hosts a
site-hosted `query` URL onto the `apiBaseUrl` (SDO synonym hrefs resolve
`query?q=...` against the word's page uri, which may be the main site); a
`parsePage` hook lets each subclass pick its parser. `search()` hits the API's
`getInflectedResults` (`/<short>/query`) plus the
`/<short>/livesearch` autocomplete JSON (a plain array of headword strings,
decoded by `DdoParser.parseSearch` — shared with the CLI) and resolves
homographs from the API page's `.short-result ul li a`.

`DdoParser.parse` reads one `div.artikel` per page and returns JSON-rendered
`Word`s:

- **Header**: `div.definitionBoxTop`'s `span.match` (ownText, so the `.super`
  homograph number is dropped) is the headword; `span.tekstmedium` is the POS
  label (e.g. "substantiv, intetkøn") mapped by `normalizePos` to `Pos`
  (`substantiv`→NOUN, `verbum`→VERB, ...). `#id-udt span.lydskrift` is the
  `pronunciation`, `#id-boj .allow-glossing` the `conjugation` (each "-" is
  the headword stem), `#id-ety .allow-glossing` the `etymology`. Audio comes
  from the speaker.gif `onclick="playSound('<id>')"` → `static.ordnet.dk/mp3/`.
- **Definitions**: `#content-betydninger`'s `div.definitionNumber` +
  `div.definitionIndent` pairs. A `definitionIndent` wrapping a nested
  `definitionNumber` + `definitionIndent` is a sub-sense shell ("1.a", "2.a")
  — the inner indent holds the body. Each sense: `senseNumber` from the
  number, `.stempelNoBorder` (e.g. "FYSIK") → `domain`, `span.definition`
  → gloss, "Eksempler" `.details .inlineList` text nodes + `.citat` quotes →
  examples.
- **Idioms**: `#content-faste-udtryk`, each headed by a `div.definitionBox`
  with a `span.match` title; sub-sense shells become extra idiom entries.
  Idioms carry no senseNumber, so the renderer keeps the separate locutions
  section below the senses.
- **Synonyms**: `div.definitionBox.onym` `.inlineList a` links attach to the
  definition; relative `?entry_id=...` hrefs resolve against the page uri
  (`ordbog?entry_id=...&query=...`).
- Stand-alone idiom pages (e.g. "klappe hesten") with no numbered-sense
  containers fall back to parsing the `artikel` itself.

The SDO layout is **span-based**, not div-based, so SDO got its own parser;
`SdoDictionary.parsePage` delegates to `SdoParser.parse`. It reads one
`span.artikel` per homograph (checked for `.iddel .match` headwords, several
articles become `mHomonymEntry`-linked words):

- **Header**: `span.match` ownText (drops the `.homnr` homograph number) is
  the headword; `span.lemklas` is the POS label ("sb.", "vb.", "adj.",
  "adv.", "konj.", "pron.", "præp.") mapped to `Pos`; `span.bøjdel
  .bøjning .txt` → `conjugation` ("-" and "=" are the headword stem,
  "-r, -de, -t" → "skaffar, skaffade, skaffat"); `span.fondel .fon`
  variants → `pronunciation` (joined with " / "). No audio markup in SDO.
- **Definitions**: each `span.semdel` container's `.semem` numbered senses
  (`.betnr`) plus their `.subsem` sub-senses (`.subbetnr`, usually flat
  siblings or nested in the parent), numbered "<parent>.<letter>". The gloss
  is the `.denbet` text with the `.spec` marker strip and `.tryk` stress mark
  dropped. A `.spec` block maps `.fag` → `domain`, `.valør`/`.kron`/
  `.semspec` → `register`, `.geo` → `geo`. Each sense's direct `.rel`
  children become examples: the Swedish `.txt1` plus the Danish `.txt`
  rendition joined with " — ".
- **Idioms**: each `span.sulesem` (`span.txt2` phrase, `.tryk` dropped) whose
  `.sulesemdel` senses become one `Word.Idiom` per `.semem`/`.subsem` — the
  same fixed expression repeating with `a`/`b` sense numbers, mirroring DDO's
  sub-sense-idiom behavior. Registers (e.g. "dagl.", "bibelsk") attach here.
- **Synonyms**: `span.onym` `.syn`/`.ordfelt .txt1` links attach to the
  nearest preceding definition (`.onym` inside an idiom body is dropped);
  relative `query?q=...`/`?entry_id=...` hrefs resolve against the page uri.

Tests: `DdoParserTest`/`SdoParserTest` (golden via `Goldens.assertGolden`) +
`DdoIntegrationTest`/`SdoIntegrationTest` (MockWebServer, plain JUnit),
fixtures `testdata/ddo/arbejde.{html,json}` (a real archived ordnet.dk page)
and `testdata/sdo/skaffa.{html,json}`/`testdata/sdo/hus.{html,json}` (the
ws.dsl.dk `<span class="artikel">` API layout) plus `testdata/ddo-search.json`/
`testdata/ddo-query.html`/`testdata/sdo-search.json`. CLI: `ddo arbejde
--search` / `sdo hus --search`; offline `--dict ddo|sdo --file
../testdata/<dict>/<word>.html --url <entry uri>`.

## Le Robert (ROB, French)

`LeRobertDictionary` (`:core`) is baseUrl-parameterized (default
`https://dictionnaire.lerobert.com`, tests use a MockWebServer). `search()`
hits `/autocomplete.json?q=<query>&t=def` with a JSON `Accept` header and
delegates decoding to `LeRobertParser.parseSearch` (the shared RAE-style
mapping the CLI uses too); a `/conjugaison/` page is rewritten to
`/definition/`.

`LeRobertParser.parse` isolates `div.ws-c`, and each POS group in
`section.def > div.b` becomes one `Word` (`span.d_cat` is the POS label, e.g.
`nom féminin`). Audio URLs come from `audio source[src]` relative paths.
Definitions/idioms live in the `div.d_ptma` tree:

- `div.d_dvr` = a sense group (optional `span.d_dtr` "(topic)" is its `domain`),
  `div.d_dvn`/`div.d_dvl` = nested containers. The parser **recurses** through
  all three and flattens every `span.d_dfn` it finds into a
  `Word.Definition`. `span.d_mta` markers (e.g. `spécialement`,
  `(dans quelques emplois)`) that *precede* a `d_dfn` become its `register`;
  `span.d_xpl` siblings become examples.
- `div.d_dvt` blocks whose `span.d_mtb` contains "locution" become
  `Word.Idiom`s (headword `span.d_lca`, gloss `span.d_gls`); other `d_dvt`s
  with a `d_dfn` are treated as definitions and their examples attach to the
  current one.
- `section.syn > div.b``s `span.s_cat` (lowercased) maps to synonym lists from
  `span.s_syni a`/`span.s_syn a`; the matching `s_cat` list attaches to the
  first definition of the same POS group.

`genderOf` maps `féminin`/`feminin` → `Genders.FEMININE`, `masculin` →
`Genders.MASCULINE`. Tests: `LeRobertParserTest` + `LeRobertIntegrationTest`
(both `:core`, plain JUnit), fixtures `testdata/rob/table.{html,json}` and
`testdata/rob-search.json`.

## Linguee (LINGPT, Portuguese-English)

`LingueeDictionary` (`:core`) is baseUrl-parameterized (default
`https://www.linguee.pt`, tests use a MockWebServer). `search()` hits the
`/portugues-ingles/search?qe=<query>` endpoint — which returns an HTML
fragment of `.main_item` suggested matches, **not** JSON — and delegates
decoding to `LingueeParser.parseSearch(body) { uriOf }`; each `.main_item`'s
relative `/portugues-ingles/traducao/<word>.html` href becomes the result's
word-page URL. `get(uri)` fetches that traducao page and resolves the `__ref`
homographs like the other JSON dictionaries. Linguee serves pages latin-1
(ISO-8859-15) with CRLF line endings, so the test fixtures are read with
`readText(Charsets.ISO_8859_1)` (a UTF-8 read would replace the accented
bytes with U+FFFD).

`LingueeParser.parse` iterates `div.exact div.lemma` and emits one `Word` per
exact match (a headword page usually carries two — "mesa" plus the
spelling-variant "mês" on the `mesa.html` page). Grammar/gender come from
`span.tag_wordtype` ("substantivo, feminino" → `Genders.FEMININE`,
"substantivo, masculino" → `Genders.MASCULINE`). Each featured translation
(`div.translation.sortablemg.featured`) becomes one `Word.Definition`; the
English headword is its gloss, the Portuguese POS label its `pos`/`grammar`,
and the translation's `.example` lines become bilingual examples
("Portuguese sentence — English sentence"). Pronunciation keeps only the
European-Portuguese clip (`a.audio` ids starting `PT_PT`, resolved to
`<baseUrl>mp3/<id>.mp3`; the `PT_BR` Brazilian clip is dropped).

Tests: `LingueeParserTest` + `LingueeIntegrationTest` (both `:core`, plain
JUnit goldens + MockWebServer), fixtures `testdata/lingpt/mesa.{html,json}`
and `testdata/lingpt-search.json` (latin-1). `tools/download.py` supports
`LINGPT` (`https://www.linguee.pt/portugues-ingles/traducao/<word>`) and
writes the fixture back in latin-1.

## Infopédia (INFOPEDIA, Portuguese)

`InfopediaDictionary` (`:core`) is baseUrl-parameterized (default
`https://www.infopedia.pt`, tests use a MockWebServer). `search()` hits the
`sugestao-pesquisa/<query>` autocomplete endpoint — which returns JSON
`{"html": "<li title=\"…\">…"}` — unwraps the `html` field and delegates to
`InfopediaParser.parseSearch(body) { title -> … }`, where each `<li title>`
is the headword that becomes the result's word-page URL
(`/dicionarios/lingua-portuguesa/<title>`). `get(uri)` fetches that page and
resolves the `__ref` homographs like the other JSON dictionaries. The live
site serves a Cloudflare JS challenge to datacenter IPs, so capture fixtures
from the `.pt` web archive (arquivo.pt) and strip the `/wayback/<ts><flag>_/`
URL prefixes, `<base>`, and Insert scripts back to live-site form.

`InfopediaParser.parse` isolates the single `.dolEntradaVverbete` article
(the browsable `testdata/infopedia/mesa.html` fixture is `mesa` — 12 senses,
8 locuções) and emits one JSON-rendered `Word`:

- **Header**: `h1.dolEntrinfoEntrada` headword; pronunciation is the
  `.dolSilab` syllabification (me.sa) + `.dolRegfonFonet` transcription (ˈmezɐ)
  + the `.dolEntrinfoOrtoep` orthoepy note (/ê/), space-joined. Audio comes from
  `audio.audio-player-word-tts` (the word's TTS clip, e.g.
  `/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0`).
- **Etymology**: `.dolVverbeteEtim` text after the `.dolVverbeteEtim-corpo`
  descendant with the leading "Etimologia:" label stripped (mesa: "Do latim
  mensa-, «idem»"). This section and the `.dolRelacoes` boxes sit *after* the
  article, so they're read at the document level.
- **Definitions**: each `.dolDivisaoCatgram` POS group (`.dolCatgramTbcat`
  label, e.g. "nome feminino") whose `.dolAcepsRow` senses become
  `Word.Definition`s: `senseNumber` from `.dolAcepsNum`, `domain`/`register`/
  `geo` from the `.dolSubacepTbdom`/`.dolSubacepTbreg`/`.dolSubacepTbvar`
  markers, and the gloss from the joined `.dolSubacepTraduz .dolTraduzTrad`
  translations (alternative translations of one sense become a comma-joined
  gloss, e.g. "alimentação, comida, passadio"), with `.dolAcepsExplica` /
  `.dolSubacepContex` notes folded in front. `genderOf` maps "…feminino"/
  "…masculino" → `Genders.FEMININE`/`MASCULINE`.
- **Locuções**: inside `.dolVverbeteLexeger`, each `.dolLexegerExeger` heads
  one `Word.Idiom` (`.dolExegerLexpress` headword, `.dolExegerTbdom`/
  `.dolExegerTbreg` markers) whose senses are the rows of the *sibling*
  `.dolTable` — the two element types strictly alternate (E-T-E-T-…).
- **Synonyms/antonyms**: the page's `.dolRelacoes` boxes labelled *sinónimos*
  / *antónimos* (current pages mark the former with
  `#relacoesSinonimosContainer`; older pages use a `.title` heading like
  "SINÓNIMOS") attach to the first definition — synonyms as linked
  `Word.Synonym`s (hrefs resolved to absolute `/dicionarios/lingua-portuguesa/`
  URLs), antonyms as plain strings. Ellipsis "see-more" anchors are skipped.

Tests: `InfopediaParserTest` + `InfopediaIntegrationTest` (both `:core`, plain
JUnit goldens + MockWebServer), fixtures `testdata/infopedia/mesa.{html,json}`
and `testdata/infopedia-search.json`. `tools/download.py` supports `INFOPEDIA`
(`https://www.infopedia.pt/dicionarios/lingua-portuguesa/<word>`).

## Wiktionary (WFR, French)

`Wiktionary` (`:core`) is the abstract base class for Wiktionary language
variants, parameterized by `shortName` and an optional `baseUrl` (default
`https://<shortName>.m.wiktionary.org`, tests use a MockWebServer).
`FrWiktionary` is the concrete French implementation (`WFR`, `lang`/`flagCode`
`fr`). `get(uri)` validates the host against the base host and the
`<shortName>.m.wiktionary.org`/`.wiktionary.org` domains, strips `__ref`, and
delegates to `WiktionaryParser.parse`.

`search()` hits the Wiktionary REST API
`/w/rest.php/v1/search/title?q=<query>&limit=10` (the mobile `.m.` host is
rewritten to the desktop `.wiktionary.org` one) and decodes the
`{"pages":[{"title","id"}]}` shape through the shared
`WiktionaryParser.parseSearch(body, shortName) { id, title -> … }`; each
result's `uri` is the `?curid=<id>` deep-link. `fullSearch` aliases `search`.

`WiktionaryParser.parse` isolates the target language's `<section>` (the one
with a `#<shortName>` span) inside `.mw-parser-output`, strips other
languages and the `Traductions` section, de-lazies images, and reads the
pronunciation (`span.titrepron`), etymology (`span.titreetym`), and
definition (`span.titredef`) sub-sections. Each `span.titredef` heading (one
POS group, e.g. "Nom commun", "Forme de verbe") becomes one JSON-rendered
`Word` whose `titredef` id is its `__ref` xref (e.g. `fr-nom-1`); gender comes
from `span.ligne-de-forme` (`genderOf` maps "féminin"/"masculin" to
`Genders`). Definitions/idioms parse each `<ol><li>`: `span.term`/`span.emploi`
markers become `domain`/`register` (and their "(…)" prefixes are stripped from
the gloss), nested example `<li><q>` lines become gloss examples, and
pronunciation `<audio><source>` mp3s (transcodes only, ogg/wav dropped) fill
`audio`. Multi-lemma pages get `mHomograph`s + `mHomonymEntries`. A
`parseLegacy` fallback handles older HTML layouts where h3 headings hang
directly off the first `<section>`.

Tests: `WiktionaryParserTest` + `WiktionaryIntegrationTest` (both `:core`,
plain JUnit goldens + MockWebServer — search endpoint must NOT be the mobile
host), fixtures `testdata/wfr/table.{html,json}` and `testdata/wfr-search.json`.
CLI: `wfr table` / `wfr table --search` (or `--dict wfr --file …` offline).

## Conventions / gotchas

- All Kotlin source is in one package, `se.whitchurch.nordict`, in both
  `main` and `test`.
- Unit tests use Robolectric (`@RunWith(RobolectricTestRunner::class)`,
  `@Config(sdk = [28])`) when Android classes (e.g. `Uri`) are involved.
  The shared `:core` tests — `DleParserTest`, `EstParserTest`,
  `CollinsParserTest`, `DiccionariParserTest`, `LeRobertParserTest`,
  `LingueeParserTest`, `WiktionaryParserTest`, and the moved
  `{Est,Dle,Collins,Didac,Diccionari,LeRobert,Linguee,Infopedia,Wiktionary}IntegrationTest` suites
  (MockWebServer) — are plain JUnit and run on a desktop JVM.
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
  RAE-style ordinals) and bolding the one that follows the row. In
  `WordScreen` the WebView sits inside a `BoxWithConstraints` that always pins
  it to the viewport (internal WebView scroll, reliable `#hom-N` anchors).
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

## Instructions

When asked to implement something, in the todo steps always include these:

- Extend agent interface if applicable for easier agentic test
- Add automated tests (core and/or UI)
- Deploy and verify on an emulator if running (android-cli / adb)
- Commit 
- Deploy (for manual verification by user) on any device if connected to android-cli / adb
