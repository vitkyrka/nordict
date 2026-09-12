package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import se.whitchurch.nordict.CollinsParser
import se.whitchurch.nordict.DleParser
import se.whitchurch.nordict.EstParser
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordJson
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop CLI that fetches or reads dictionary pages and dumps the shared JSON
 * schema (the same golden JSON the app's WebView renderer consumes).
 *
 * Every registered dictionary shares one parser shape — `parse(page, uri, tag)`
 * — and one output schema (`WordJson`), so a parser fix/feature benefits the
 * app, these tests, and this CLI at once.
 */
fun main(args: Array<String>) {
    exitProcess(Main().run(args))
}

class Main {

    private val dictionaries = listOf(
        Dict(
            aliases = listOf("dle"),
            tag = "DLE",
            wordUrl = { word -> "https://dle.rae.es/$word".toHttpUrlOrNull()!! },
            parse = { page, uri -> DleParser.parse(page, uri, "DLE") }
        ),
        Dict(
            aliases = listOf("est"),
            tag = "EST",
            wordUrl = { word -> "https://www.rae.es/diccionario-estudiante/$word".toHttpUrlOrNull()!! },
            parse = { page, uri -> EstParser.parse(page, uri, "EST") }
        ),
        Dict(
            aliases = listOf("colspan", "col"),
            tag = "COLSPAN",
            wordUrl = { word ->
                "https://www.collinsdictionary.com/dictionary/spanish-english/${word.replace(" ", "-").lowercase()}"
                    .toHttpUrlOrNull()!!
            },
            parse = { page, uri -> CollinsParser.parse(page, uri, "COLSPAN", "spanish-english") }
        )
    )

    private data class Dict(
        val aliases: List<String>,
        val tag: String,
        val wordUrl: (String) -> HttpUrl,
        val parse: (page: String, uri: HttpUrl) -> List<Word>
    )

    fun run(args: Array<String>): Int {
        var url: HttpUrl? = null
        var filePath: String? = null
        var outputPath: String? = null
        var dict: Dict = dictionaries.first()
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

        if (positional.size > 1) return error("expected exactly one word or URL, got: ${positional.joinToString(" ")}")
        if (url != null && positional.isNotEmpty()) return error("give either a positional word/URL or --url, not both")
        val word = positional.firstOrNull()

        if (filePath == null && url == null && word == null) {
            return error("usage: nordict [<dict>] <word> | [--dict <dict>] --url <url> | [--dict <dict>] --file <page.html> [-o out.json]")
        }

        try {
            val (page, uri) = if (filePath != null) {
                val target = url ?: dict.wordUrl(word ?: fallbackWord(filePath))
                pageFromFile(filePath, target)
            } else {
                val target = url ?: dict.wordUrl(word!!)
                fetch(target) to target
            }

            val words = dict.parse(page, uri)
            if (words.isEmpty()) {
                System.err.println("no words parsed from $uri")
                return 1
            }

            val json = WordJson.toJson(words)
            if (outputPath != null) {
                File(outputPath).writeText(json)
                System.err.println("wrote $outputPath")
            } else {
                print(json)
            }
            if (outputPath == null) {
                System.err.println(
                    "parsed ${words.size} word(s), " +
                        "${words.sumOf { it.definitions.size }} definition(s), " +
                        "${words.sumOf { it.idioms.size }} idiom(s) from $uri (${dict.tag})"
                )
            }
            return 0
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
    }

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
              <word>                  fetch the default dictionary (DLE) and dump JSON
              <dict> <word>           fetch a specific dictionary, e.g. "est frente"
                                      or "colspan frente" (spaces become hyphens)
              --url <url>             parse any dictionary page URL (homographs, deep links)
              --file <page.html>      parse a local HTML page (no network)

            options:
              --dict <name>           dictionary to use (default: ${dictionaries.first().aliases.first()})
              -o, --output <path>     write JSON to a file (default: stdout)
              -h, --help              show this help

            The JSON is the shared golden schema (see testdata/{dle,est,colspan}/*.json);
            pipe it to 'app/src/test/js/cli.js' for a browser preview of the rendered entry.
            """.trimIndent()
        )
    }
}