package se.whitchurch.nordict.cli

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import se.whitchurch.nordict.CollinsParser
import se.whitchurch.nordict.DdoParser
import se.whitchurch.nordict.DiccionariParser
import se.whitchurch.nordict.DidacParser
import se.whitchurch.nordict.DleParser
import se.whitchurch.nordict.EstParser
import se.whitchurch.nordict.InfopediaParser
import se.whitchurch.nordict.LeRobertParser
import se.whitchurch.nordict.LingueeParser
import se.whitchurch.nordict.SearchResult
import se.whitchurch.nordict.SoParser
import se.whitchurch.nordict.WiktionaryParser
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentProtocol
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.CombSource
import se.whitchurch.nordict.MultiDict
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordJson
import se.whitchurch.nordict.toWordData
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop CLI that fetches or reads dictionary pages/searches and dumps the
 * shared JSON schemas (the same golden JSON the app's WebView renderer and the
 * search listings consume).
 *
 * Every registered dictionary shares one parser shape — `parse(page, uri, tag)`
 * — and one output schema (`WordJson`), plus one per-dictionary search-response
 * parser (`DleParser.parseSearch`/`EstParser.parseSearch`/`CollinsParser.parseSearch`)
 * so a parser fix/feature benefits the app, these tests, and this CLI at once.
 */
fun main(args: Array<String>) {
    exitProcess(Main().run(args))
}

class Main {

    private fun dleUrl(word: String): HttpUrl = "https://dle.rae.es/$word".toHttpUrlOrNull()!!

    private fun estUrl(word: String): HttpUrl =
        "https://www.rae.es/diccionario-estudiante/$word".toHttpUrlOrNull()!!

    private fun collinsUrl(dictCode: String, word: String): HttpUrl =
        "https://www.collinsdictionary.com/dictionary/$dictCode/${word.replace(" ", "-").lowercase()}"
            .toHttpUrlOrNull()!!

    private fun collinsAutocomplete(dictCode: String, query: String): HttpUrl =
        "https://www.collinsdictionary.com/autocomplete/?q=$query&dictCode=$dictCode"
            .toHttpUrlOrNull()!!

    private fun lingueeUrl(word: String): HttpUrl =
        "https://www.linguee.pt/portugues-ingles/search"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("qe", word)
            .addQueryParameter("source", "auto")
            .addQueryParameter("cw", "703")
            .addQueryParameter("ch", "1332")
            .build()

    private fun infopediaUrl(word: String): HttpUrl =
        "https://www.infopedia.pt/dicionarios/lingua-portuguesa/$word".toHttpUrlOrNull()!!

    private fun infopediaSuggestions(query: String): HttpUrl =
        "https://www.infopedia.pt/dicionarios/lingua-portuguesa/sugestao-pesquisa/$query"
            .toHttpUrlOrNull()!!

    private fun lerobertUrl(word: String): HttpUrl =
        "https://dictionnaire.lerobert.com/definition/$word".toHttpUrlOrNull()!!

    private fun lerobertAutocomplete(query: String): HttpUrl =
        "https://dictionnaire.lerobert.com/autocomplete.json"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("t", "def")
            .build()

    private fun wiktionaryRest(short: String, query: String): HttpUrl =
        "https://$short.wiktionary.org/w/rest.php/v1/search/title"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()

    private fun wiktionaryUrl(short: String, word: String): HttpUrl =
        "https://$short.m.wiktionary.org/wiki/$word".toHttpUrlOrNull()!!

    private fun soAutocomplete(query: String): HttpUrl =
        "https://svenska.se/wp-admin/admin-ajax.php"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("action", "tri_autocomplete")
            .addQueryParameter("term", query)
            .build()

    private fun dslLiveSearch(short: String, query: String): HttpUrl =
        "https://ws.dsl.dk/$short/livesearch"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("text", query)
            .addQueryParameter("size", "50")
            .build()

    private fun dslEntryUri(short: String, word: String): HttpUrl =
        "https://ws.dsl.dk/$short/query"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("app", "android")
            .addQueryParameter("version", "2.1.5")
            .addQueryParameter("q", word)
            .build()

    // ---- search-response decoders (mirror each Dictionary.search) ----

    private fun soSearchResults(body: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val words = try {
            JsonParser.parseString(body).asJsonArray
        } catch (e: Exception) {
            return results
        }
        for (el in words) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val link = obj.get("link")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            ("https://svenska.se/$link").toHttpUrlOrNull()?.let { results.add(SearchResult(label, it)) }
        }
        return results
    }

    private fun dslSearchResults(body: String, short: String): List<SearchResult> =
        try {
            JsonParser.parseString(body).asJsonArray.mapNotNull { el ->
                if (!el.isJsonPrimitive) null
                else SearchResult(el.asString, dslEntryUri(short, el.asString))
            }
        } catch (e: Exception) {
            emptyList()
        }

    private fun wiktionarySearchResults(body: String, short: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val pages = try {
            JsonParser.parseString(body).asJsonObject.getAsJsonArray("pages")
        } catch (e: Exception) {
            return results
        }
        for (el in pages) {
            if (!el.isJsonObject) continue
            val page = el.asJsonObject
            val title = page.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val id = page.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
            ("https://$short.m.wiktionary.org/?curid=$id").toHttpUrlOrNull()
                ?.let { results.add(SearchResult(title, it)) }
        }
        return results
    }

    private fun infopediaSearchResults(body: String): List<SearchResult> {
        val html = try {
            JsonParser.parseString(body).asJsonObject.get("html")?.asString
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return InfopediaParser.parseSearch(html)
    }

    private val lingueeBase = "https://www.linguee.pt/".toHttpUrlOrNull()!!

    private fun didacUrl(word: String): HttpUrl =
        "https://www.diccionari.cat/cerca/didac"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("search_api_fulltext_cust", word)
            .addQueryParameter("show", "title")
            .build()

    private fun didacAutocomplete(query: String): HttpUrl =
        "https://www.diccionari.cat/search_api_autocomplete/didac?display=page_1&&filter=search_api_fulltext_cust"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", query)
            .build()

    private fun diccionariCerca(cerca: String, word: String): HttpUrl =
        "https://www.diccionari.cat/cerca/$cerca"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("search_api_fulltext_cust", word)
            .addQueryParameter("show", "title")
            .build()

    private fun diccionariAutocomplete(key: String, query: String): HttpUrl =
        "https://www.diccionari.cat/search_api_autocomplete/$key?display=page_1&&filter=search_api_fulltext_cust"
            .toHttpUrlOrNull()!!
            .newBuilder()
            .addQueryParameter("q", query)
            .build()

    // One GDLC/CA-ES/CA-EN release: the cerca view name, the autocomplete
    // block key, the Drupal node class, the entry URL path prefix, and the
    // bilingual flag all differ.
    private fun diccionariDict(
        alias: String,
        dictTag: String,
        cerca: String,
        autocompleteKey: String,
        nodeClass: String,
        entryPath: String,
        bilingual: Boolean
    ) = Dict(
        aliases = listOf(alias),
        tag = dictTag,
        lang = "ca",
        wordUrl = { word -> diccionariCerca(cerca, word) },
        searchUrl = { query -> diccionariAutocomplete(autocompleteKey, query) },
        parse = { page, uri -> DiccionariParser.parse(page, uri, dictTag, nodeClass, bilingual) },
        searchResults = { body ->
            DiccionariParser.parseSearch(body, entryPath) { path -> "https://www.diccionari.cat$path".toHttpUrlOrNull()!! }
        }
    )

    val dictionaries = listOf(
        Dict(
            aliases = listOf("dle"),
            tag = "DLE",
            lang = "es",
            supportsCombining = true,
            wordUrl = { word -> dleUrl(word) },
            searchUrl = { query -> "https://dle.rae.es/srv/keys?q=$query".toHttpUrlOrNull()!! },
            parse = { page, uri -> DleParser.parse(page, uri, "DLE") },
            searchResults = { body -> DleParser.parseSearch(body) { word -> dleUrl(word) } }
        ),
        Dict(
            aliases = listOf("est"),
            tag = "EST",
            lang = "es",
            supportsCombining = true,
            wordUrl = { word -> estUrl(word) },
            searchUrl = { query -> "https://www.rae.es/diccionario-estudiante/srv/keys?q=$query".toHttpUrlOrNull()!! },
            parse = { page, uri -> EstParser.parse(page, uri, "EST") },
            searchResults = { body -> EstParser.parseSearch(body) { word -> estUrl(word) } }
        ),
        Dict(
            aliases = listOf("colspan", "col"),
            tag = "COLSPAN",
            lang = "es",
            supportsCombining = true,
            wordUrl = { word -> collinsUrl("spanish-english", word) },
            searchUrl = { query -> collinsAutocomplete("spanish-english", query) },
            parse = { page, uri -> CollinsParser.parse(page, uri, "COLSPAN", "spanish-english") },
            searchResults = { body -> CollinsParser.parseSearch(body) { title -> collinsUrl("spanish-english", title) } }
        ),
        Dict(
            aliases = listOf("colfren"),
            tag = "COLFREN",
            lang = "fr",
            wordUrl = { word -> collinsUrl("french-english", word) },
            searchUrl = { query -> collinsAutocomplete("french-english", query) },
            parse = { page, uri -> CollinsParser.parse(page, uri, "COLFREN", "french-english") },
            searchResults = { body -> CollinsParser.parseSearch(body) { title -> collinsUrl("french-english", title) } }
        ),
        Dict(
            aliases = listOf("so"),
            tag = "SO",
            lang = "se",
            wordUrl = null,
            searchUrl = { query -> soAutocomplete(query) },
            parse = { page, _ -> SoParser.parse(page, "SO") },
            searchResults = { body -> soSearchResults(body) }
        ),
        Dict(
            aliases = listOf("sdo"),
            tag = "SDO",
            lang = "se",
            wordUrl = null,
            searchUrl = { query -> dslLiveSearch("sdo", query) },
            parse = { page, uri -> listOfNotNull(DdoParser.parse(page, uri, "SDO")) },
            searchResults = { body -> dslSearchResults(body, "sdo") }
        ),
        Dict(
            aliases = listOf("ddo"),
            tag = "DDO",
            lang = "dk",
            wordUrl = null,
            searchUrl = { query -> dslLiveSearch("ddo", query) },
            parse = { page, uri -> listOfNotNull(DdoParser.parse(page, uri, "DDO")) },
            searchResults = { body -> dslSearchResults(body, "ddo") }
        ),
        Dict(
            aliases = listOf("lingpt"),
            tag = "LINGPT",
            lang = "pt",
            wordUrl = { word -> lingueeUrl(word) },
            searchUrl = { query -> lingueeUrl(query) },
            parse = { page, uri -> LingueeParser.parse(page, uri, "LINGPT") },
            searchResults = { body -> LingueeParser.parseSearch(body) { page -> lingueeBase.resolve(page)!! } }
        ),
        Dict(
            aliases = listOf("infopedia"),
            tag = "INFOPEDIA",
            lang = "pt",
            wordUrl = { word -> infopediaUrl(word) },
            searchUrl = { query -> infopediaSuggestions(query) },
            parse = { page, uri -> InfopediaParser.parse(page, uri, "INFOPEDIA") },
            searchResults = { body -> infopediaSearchResults(body) }
        ),
        Dict(
            aliases = listOf("rob"),
            tag = "ROB",
            lang = "fr",
            wordUrl = { word -> lerobertUrl(word) },
            searchUrl = { query -> lerobertAutocomplete(query) },
            parse = { page, uri -> LeRobertParser.parse(page, uri, "ROB") },
            searchResults = { body ->
                LeRobertParser.parseSearch(body) { page ->
                    ("https://dictionnaire.lerobert.com" + page).toHttpUrlOrNull()!!
                }
            }
        ),
        Dict(
            aliases = listOf("wfr"),
            tag = "WFR",
            lang = "fr",
            wordUrl = { word -> wiktionaryUrl("fr", word) },
            searchUrl = { query -> wiktionaryRest("fr", query) },
            parse = { page, uri -> WiktionaryParser.parse(page, uri, "WFR", "fr") },
            searchResults = { body -> wiktionarySearchResults(body, "fr") }
        ),
        Dict(
            aliases = listOf("didac"),
            tag = "DIDAC",
            lang = "ca",
            supportsCombining = true,
            wordUrl = { word -> didacUrl(word) },
            searchUrl = { query -> didacAutocomplete(query) },
            parse = { page, uri -> DidacParser.parse(page, uri, "DIDAC") },
            searchResults = { body ->
                DidacParser.parseSearch(body) { path -> "https://www.diccionari.cat$path".toHttpUrlOrNull()!! }
            }
        ),
        diccionariDict("gdlc", "GDLC", "gran-diccionari-de-la-llengua-catalana", "diccionari_gdlc", "diccionari-gdlc", "GDLC", false).withCombining(),
        diccionariDict("ca-es", "CA-ES", "diccionari-catala-castella", "diccionari_ca_es_", "diccionari-ca-es", "catala-castella", true).withCombining(),
        diccionariDict("ca-en", "CA-EN", "diccionari-catala-angles", "diccionari_ca_en", "diccionari-ca-en", "catala-angles", true).withCombining()
    )

    fun run(args: Array<String>): Int {
        if (args.isNotEmpty() && args[0] == "repl") {
            return runRepl(args.copyOfRange(1, args.size))
        }

        var url: HttpUrl? = null
        var filePath: String? = null
        var outputPath: String? = null
        val selectedDicts = mutableListOf<Dict>()
        var search = false
        val positional = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--dict" -> {
                    val alias = args.getOrNull(++i)
                    if (alias == null) return error("--dict needs a dictionary name: ${aliasesString()}")
                    if (alias.startsWith("-")) return error("--dict needs a dictionary name, got '$alias'")
                    for (name in alias.split(',')) {
                        val named = dictionaries.firstOrNull { it.tag == name || name in it.aliases }
                        if (named == null) return error("unknown dictionary '$name' (choose from: ${aliasesString()})")
                        selectedDicts.add(named)
                    }
                }
                "--url" -> {
                    val raw = args.getOrNull(++i)
                    url = raw?.toHttpUrlOrNull()
                    if (url == null) return error("--url needs a valid URL like https://dle.rae.es/frente")
                }
                "--file" -> {
                    filePath = args.getOrNull(++i)
                    if (filePath == null) return error("--file needs a path like ../testdata/dle/frente.html")
                }
                "--search" -> search = true
                "-o", "--output" -> {
                    outputPath = args.getOrNull(++i)
                    if (outputPath == null) return error("$arg needs a path")
                }
                "-h", "--help" -> {
                    usage()
                    return 0
                }
                else -> positional.add(arg)
            }
            i++
        }

        // A leading positional that names a dictionary selects it: "est frente"
        // means EST, word "frente". A comma list selects several: "dle,est frente".
        val firstPos = positional.firstOrNull()?.takeIf { !it.startsWith("-") }
        if (firstPos != null) {
            val names = firstPos.split(',')
            val named = if (names.all { n -> dictionaries.any { it.tag == n || n in it.aliases } }) {
                names.mapNotNull { n -> dictionaries.firstOrNull { it.tag == n || n in it.aliases } }
            } else {
                null
            }
            if (named != null) {
                if (selectedDicts.isNotEmpty()) {
                    return error("give the dictionary once, either as --dict or as the first argument")
                }
                positional.removeAt(0)
                selectedDicts.addAll(named)
            }
        }

        if (positional.size > 1) return error("expected exactly one word or query, got: ${positional.joinToString(" ")}")
        if (url != null && positional.isNotEmpty()) return error("give either a positional word/query or --url, not both")
        val word = positional.firstOrNull()

        if (filePath == null && url == null && word == null) {
            return error(
                "usage: nordict [<dict>[,<dict>...]] <word> | [<dict>[,<dict>...]] <query> --search | " +
                    "[--dict <dict>[,<dict>...]] --url <url> | [--dict <dict>] --file <page.html|search.json> | -o out.json"
            )
        }

        val selection: List<Dict> = selectedDicts.ifEmpty { listOf(dictionaries.first()) }
        if (selection.size > 1) {
            val unsupported = selection.filterNot { it.supportsCombining }
            if (unsupported.isNotEmpty()) {
                return error("dictionary ${unsupported.first().tag} does not support combining with other dictionaries")
            }
            if (selection.map { it.lang }.distinct().size != 1) {
                return error(
                    "combined lookup requires one language (got ${selection.map { it.lang }.distinct().joinToString(",")})"
                )
            }
        }

        try {
            val w = word ?: fallbackWord(filePath!!)
            if (url != null || filePath != null) {
                if (selection.size > 1) {
                    return error("--url/--file parse one dictionary page; combine directly with a word: '${selection.joinToString(",") { it.aliases.first() }} $w'")
                }
                val dict = selection.first()
                return emitWordOrSearch(dict, w, url, filePath, search, outputPath)
            }

            if (search) {
                return emitSearch(selection, w, outputPath)
            }

            if (selection.size == 1) {
                return emitWord(selection.first(), w, outputPath)
            }

            // Combined multi-dictionary word: fetch every selection dictionary in
            // parallel, aggregate their page entries into one renderable set, and
            // dump the entries as the flat per-entry JSON array the renderer
            // draws as a combined homonym page (each entry labeled with its
            // dictionary, xrefs namespaced "DICT::id").
            val lookups = selection.map { it.asLookup(::fetch) }
            val sources = selection.map { d -> CombSource(d.tag, d.wordUrl!!.invoke(w)) }
            val combined = MultiDict.fetch(lookups, sources, headword = w)
                ?: return error("no words parsed for '$w' from ${selection.joinToString(",") { it.tag }}")
            val entries = combined.mHomonymEntries
                .filter { it.mTitle.isNotEmpty() || it.definitions.isNotEmpty() || it.idioms.isNotEmpty() }
                .map { Word.withEntry(combined, it, w).toWordData() }
            if (entries.isEmpty()) return error("no words parsed for '$w' from ${selection.joinToString(",") { it.tag }}")
            return emit(
                WordJson.gson.toJson(entries),
                "${entries.size} entry/ies for '$w' from ${selection.joinToString(",") { it.tag }}",
                outputPath
            )
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
    }

    private fun emitWordOrSearch(
        dict: Dict,
        word: String,
        url: HttpUrl?,
        filePath: String?,
        search: Boolean,
        outputPath: String?
    ): Int {
        val target = when {
            url != null -> url
            search -> dict.searchUrl(word)
            else -> dict.wordUrl?.invoke(word) ?: return error(
                "dictionary ${dict.tag} has no word URL from a headword on the CLI — " +
                    "run '${dict.aliases.first()} $word --search' to list entries, then " +
                    "--url <result> (optionally with --file <page.html>) to parse a page"
            )
        }
        val body = if (filePath != null) pageFromFile(filePath, target).first else fetch(target)
        return if (search) {
            val results = dict.searchResults(body)
            if (results.isEmpty()) {
                System.err.println("no search results from $target (${dict.tag})")
                return 1
            }
            emit(
                WordJson.searchJson(results),
                "${results.size} result(s) from $target (${dict.tag})",
                outputPath
            )
        } else {
            val words = dict.parse(body, target)
            if (words.isEmpty()) {
                System.err.println("no words parsed from $target (${dict.tag})")
                return 1
            }
            emit(
                WordJson.toJson(words),
                "parsed ${words.size} word(s), " +
                    "${words.sumOf { it.definitions.size }} definition(s), " +
                    "${words.sumOf { it.idioms.size }} idiom(s) from $target (${dict.tag})",
                outputPath
            )
        }
    }

    /** Single-dictionary word lookup (the exact legacy path). */
    private fun emitWord(dict: Dict, word: String, outputPath: String?): Int {
        val target = dict.wordUrl?.invoke(word) ?: return error(
            "dictionary ${dict.tag} has no word URL from a headword on the CLI — " +
                "run '${dict.aliases.first()} $word --search' to list entries, then " +
                "--url <result> (optionally with --file <page.html>) to parse a page"
        )
        return emitWordOrSearch(dict, word, null, null, false, outputPath)
    }

    private fun emitSearch(selection: List<Dict>, query: String, outputPath: String?): Int {
        if (selection.size == 1) {
            return emitWordOrSearch(selection.first(), query, null, null, true, outputPath)
        }
        // Combined search: every selection dictionary's autocomplete runs in
        // parallel and merges by headword, tagging each result with its sources.
        val results = MultiDict.mergeSearch(
            selection.map { d -> d.tag to d.searchResults(fetch(d.searchUrl(query))) }
        )
        if (results.isEmpty()) {
            System.err.println("no search results for '$query' from ${selection.joinToString(",") { it.tag }}")
            return 1
        }
        return emit(
            WordJson.searchJson(results),
            "${results.size} result(s) for '$query' from ${selection.joinToString(",") { it.tag }}",
            outputPath
        )
    }

    private fun emit(json: String, summary: String, outputPath: String?): Int {
        if (outputPath != null) {
            File(outputPath).writeText(json)
            System.err.println("wrote $outputPath")
        } else {
            print(json)
            System.err.println(summary)
        }
        return 0
    }

    private fun runRepl(args: Array<String>): Int {
        var device: String? = null
        var singleCommand: String? = null

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--device" -> {
                    device = args.getOrNull(++i)
                    if (device == null) return error("repl --device needs a device serial")
                }
                "--command" -> {
                    singleCommand = args.getOrNull(++i)
                    if (singleCommand == null) return error("repl --command needs a JSON command")
                }
                "-h", "--help" -> {
                    replUsage()
                    return 0
                }
                else -> return error("repl: unknown option '$arg'")
            }
            i++
        }

        val backend: AgentBackend = if (device != null) {
            try {
                DeviceAgentBackend(device).also { it.activate() }
            } catch (e: Exception) {
                return error("repl: cannot reach device $device: ${e.message ?: e}")
            }
        } else {
            HeadlessAgentDriver(dictionaries)
        }

        return if (singleCommand != null) {
            val result = try {
                val command = AgentProtocol.gson.fromJson(singleCommand, AgentCommand::class.java)
                if (command.op == AgentOps.QUIT) {
                    AgentResult(ok = true, op = AgentOps.QUIT, message = "bye")
                } else {
                    backend.execute(command)
                }
            } catch (e: Exception) {
                AgentResult.error(null, "protocol error: ${e.message ?: e}")
            }
            println(AgentProtocol.gson.toJson(result))
            0
        } else {
            Repl(backend).run(System.`in`, System.out)
        }
    }

    private fun replUsage() {
        System.err.println(
            """
            nordict agent repl — a semantic command surface for AI agents

            Reads one JSON command per line from stdin and writes exactly one
            JSON result per command to stdout. Headless by default (searches and
            parses pages with the shared parsers); --device drives the debug
            build of the real app over adb reverse.

            commands ({"op":"search","query":"frente"}, ...):
              search   search the current dictionary, returning {mTitle,mSummary,uri} results
              open     query absent ->: open the unique exact match of `query` from a search
                       (no exact match errors; run search to pick). `uri` opens a URL directly.
              openUri  open a word page URL, selecting the `__ref`-tagged homograph if present
              nextPage move to the next homograph/sub-entry of the loaded page
              back     leave the word view (headless: clear the loaded word)
              setDict  tag = a dict alias or tag (dle/est/colspan/colfren/didac/gdlc/ca-es/ca-en/so/sdo/ddo/lingpt/infopedia/rob/wfr)
              setLang  lang = a language code (es/ca) — selects the first dict of that language
              state    snapshot of {activity, dict, lang, query, word}
              quit     close the session

            options:
              --device SERIAL    drive the app on a connected device (adb reverse on port 42837)
              --command 'json'   run one command and print its one-line result, then exit
              -h, --help         show this help

            example: echo '{"op":"search","query":"frente"}' | ./gradlew :cli:run --args="repl"
            """.trimIndent()
        )
    }

    /** Marks a registered dictionary as combining-capable (keepers of the 7). */
    private fun Dict.withCombining(): Dict = copy(supportsCombining = true)

    private fun aliasesString(): String = dictionaries.joinToString(", ") { it.aliases.joinToString("/") + " (" + it.tag + ")" }

    private fun fallbackWord(filePath: String): String {
        return File(filePath).name.removeSuffix(".html").removeSuffix(".htm")
    }

    private fun pageFromFile(filePath: String, uri: HttpUrl): Pair<String, HttpUrl> {
        val file = File(filePath)
        if (!file.exists()) throw IllegalArgumentException("file not found: ${file.absolutePath}")
        return file.readText() to uri
    }

    private fun fetch(url: HttpUrl): String {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} for $url")
            return response.body?.string() ?: ""
        }
    }

    private fun error(message: String): Int {
        System.err.println("error: $message")
        return 1
    }

    private fun usage() {
        System.err.println(
            """
            nordict dictionary CLI

            dictionaries:
              ${aliasesString()}

            usage:
              <word>                   fetch the default dictionary (DLE) and dump JSON
              <dict> <word>            fetch a specific dictionary, e.g. "est frente"
                                       or "colspan frente" (spaces become hyphens)
              <dict> <query> --search  dump search-result JSON, e.g. "est frente --search"
                                       or "colspan cagar --search"
              --url <url>              parse a word or --search endpoint URL
              --file <page.html|search.json>
                                       parse a local word page or --search response (no network)

            DIDAC maps a word to its search view (www.diccionari.cat/cerca/didac),
            which embeds every matching homograph/locution inline:
              didac cap                 all "cap" entries (cap1..cap4, cap-roig, ...)
              didac cap --search        autocomplete suggestions
              --url https://www.diccionari.cat/didac/cap1   single homograph page

            Search-first dictionaries (so/sdo/ddo — and rob routes through search)
            have no word URL from a headword: list entries with '--search', then
            open one via '--url <result>'. lingpt/infopedia/wfr/colfren fetch a
            word page directly like colspan.

            options:
              --dict <name>            dictionary to use (default: ${dictionaries.first().aliases.first()})
              --search                 fetch search results for the query instead of a word page
              -o, --output <path>      write JSON to a file (default: stdout)
              -h, --help               show this help

            The word JSON is the shared golden schema (see testdata/{dle,est,colspan}/*.json);
            pipe it to 'app/src/test/js/cli.js' for a browser preview of the rendered entry.
            Search result JSON is an array of {mTitle, mSummary, uri}.
            """.trimIndent()
        )
    }
}