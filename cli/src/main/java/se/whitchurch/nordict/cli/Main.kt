package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import se.whitchurch.nordict.CollinsParser
import se.whitchurch.nordict.DidacParser
import se.whitchurch.nordict.DleParser
import se.whitchurch.nordict.EstParser
import se.whitchurch.nordict.SearchResult
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordJson
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

    private fun collinsUrl(word: String): HttpUrl =
        "https://www.collinsdictionary.com/dictionary/spanish-english/${word.replace(" ", "-").lowercase()}"
            .toHttpUrlOrNull()!!

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

    private val dictionaries = listOf(
        Dict(
            aliases = listOf("dle"),
            tag = "DLE",
            wordUrl = { word -> dleUrl(word) },
            searchUrl = { query -> "https://dle.rae.es/srv/keys?q=$query".toHttpUrlOrNull()!! },
            parse = { page, uri -> DleParser.parse(page, uri, "DLE") },
            searchResults = { body -> DleParser.parseSearch(body) { word -> dleUrl(word) } }
        ),
        Dict(
            aliases = listOf("est"),
            tag = "EST",
            wordUrl = { word -> estUrl(word) },
            searchUrl = { query -> "https://www.rae.es/diccionario-estudiante/srv/keys?q=$query".toHttpUrlOrNull()!! },
            parse = { page, uri -> EstParser.parse(page, uri, "EST") },
            searchResults = { body -> EstParser.parseSearch(body) { word -> estUrl(word) } }
        ),
        Dict(
            aliases = listOf("colspan", "col"),
            tag = "COLSPAN",
            wordUrl = { word -> collinsUrl(word) },
            searchUrl = { query ->
                "https://www.collinsdictionary.com/autocomplete/?q=$query&dictCode=spanish-english"
                    .toHttpUrlOrNull()!!
            },
            parse = { page, uri -> CollinsParser.parse(page, uri, "COLSPAN", "spanish-english") },
            searchResults = { body -> CollinsParser.parseSearch(body) { title -> collinsUrl(title) } }
        ),
        Dict(
            aliases = listOf("didac"),
            tag = "DIDAC",
            wordUrl = { word -> didacUrl(word) },
            searchUrl = { query -> didacAutocomplete(query) },
            parse = { page, uri -> DidacParser.parse(page, uri, "DIDAC") },
            searchResults = { body ->
                DidacParser.parseSearch(body) { path -> "https://www.diccionari.cat$path".toHttpUrlOrNull()!! }
            }
        )
    )

    private data class Dict(
        val aliases: List<String>,
        val tag: String,
        val wordUrl: (String) -> HttpUrl,
        val searchUrl: (String) -> HttpUrl,
        val parse: (page: String, uri: HttpUrl) -> List<Word>,
        val searchResults: (body: String) -> List<SearchResult>
    )

    fun run(args: Array<String>): Int {
        var url: HttpUrl? = null
        var filePath: String? = null
        var outputPath: String? = null
        var dict: Dict = dictionaries.first()
        var search = false
        val positional = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--dict" -> {
                    val alias = args.getOrNull(++i)
                    if (alias == null) return error("--dict needs a dictionary name: ${aliasesString()}")
                    if (alias.startsWith("-")) return error("--dict needs a dictionary name, got '$alias'")
                    dict = dictionaries.firstOrNull { alias in it.aliases }
                        ?: return error("unknown dictionary '$alias' (choose from: ${aliasesString()})")
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
        // means EST, word "frente". "frente" alone still defaults to DLE.
        val firstPos = positional.firstOrNull()?.takeIf { !it.startsWith("-") }
        if (firstPos != null) {
            val named = dictionaries.firstOrNull { firstPos in it.aliases }
            if (named != null) {
                if (dict !== dictionaries.first()) {
                    return error("give the dictionary once, either as --dict or as the first argument")
                }
                positional.removeAt(0)
                dict = named
            }
        }

        if (positional.size > 1) return error("expected exactly one word or query, got: ${positional.joinToString(" ")}")
        if (url != null && positional.isNotEmpty()) return error("give either a positional word/query or --url, not both")
        val word = positional.firstOrNull()

        if (filePath == null && url == null && word == null) {
            return error(
                "usage: nordict [<dict>] <word> | [<dict>] <query> --search | " +
                    "[--dict <dict>] --url <url> | [--dict <dict>] --file <page.html|search.json> | -o out.json"
            )
        }

        try {
            // Words and search both resolve (url | ?? ) to a single target; the
            // body source is --file, --url, or a live fetch of that target.
            val target = when {
                url != null -> url
                search -> dict.searchUrl(word ?: fallbackWord(filePath!!))
                else -> dict.wordUrl(word ?: fallbackWord(filePath!!))
            }
            val body = if (filePath != null) pageFromFile(filePath, target).first else fetch(target)

            val output = if (search) {
                val results = dict.searchResults(body)
                if (results.isEmpty()) {
                    System.err.println("no search results from $target (${dict.tag})")
                    return 1
                }
                Output(
                    json = WordJson.searchJson(results),
                    summary = "${results.size} result(s) from $target (${dict.tag})"
                )
            } else {
                val words = dict.parse(body, target)
                if (words.isEmpty()) {
                    System.err.println("no words parsed from $target (${dict.tag})")
                    return 1
                }
                Output(
                    json = WordJson.toJson(words),
                    summary = "parsed ${words.size} word(s), " +
                        "${words.sumOf { it.definitions.size }} definition(s), " +
                        "${words.sumOf { it.idioms.size }} idiom(s) from $target (${dict.tag})"
                )
            }

            if (outputPath != null) {
                File(outputPath).writeText(output.json)
                System.err.println("wrote $outputPath")
            } else {
                print(output.json)
                System.err.println(output.summary)
            }
            return 0
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
    }

    private data class Output(val json: String, val summary: String)

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