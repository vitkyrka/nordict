package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import se.whitchurch.nordict.DleParser
import se.whitchurch.nordict.WordJson
import java.io.File
import kotlin.system.exitProcess

/**
 * Desktop CLI that parses arbitrary DLE pages and dumps the shared JSON schema
 * (the same golden JSON the app's WebView renderer consumes).
 *
 * The parsing and serialization logic lives in the shared `:core` module, so a
 * parser fix/feature benefits the app, these tests, and this CLI at once.
 */
fun main(args: Array<String>) {
    exitProcess(Main().run(args))
}

class Main {

    private val baseUrl = "https://dle.rae.es"

    fun run(args: Array<String>): Int {
        var url: HttpUrl? = null
        var filePath: String? = null
        var outputPath: String? = null
        val positional = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
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

        if (positional.size > 1) return error("expected exactly one word or URL, got: ${positional.joinToString(" ")}")
        if (url != null && positional.isNotEmpty()) return error("give either a positional word/URL or --url, not both")
        val word = positional.firstOrNull()

        if (filePath == null && url == null && word == null) {
            return error("usage: dle <word> | --url <dle url> | --file <page.html> [-o out.json]")
        }

        try {
            val (page, uri) = if (filePath != null) {
                if (url != null) {
                    pageFromFile(filePath, url)
                } else {
                    pageFromFile(filePath, word?.let { wordUrl(it) } ?: fallbackUrl(filePath))
                }
            } else {
                val target = url ?: wordUrl(word!!)
                fetch(target) to target
            }

            val words = DleParser.parse(page, uri, "DLE")
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
                        "${words.sumOf { it.idioms.size }} idiom(s) from $uri"
                )
            }
            return 0
        } catch (e: Exception) {
            return error(e.message ?: e.toString())
        }
    }

    private fun wordUrl(word: String): HttpUrl {
        return "$baseUrl/$word".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("cannot build a DLE URL from word '$word'")
    }

    private fun fallbackUrl(filePath: String): HttpUrl {
        val name = File(filePath).name.removeSuffix(".html").removeSuffix(".htm")
        return wordUrl(name)
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
            nordict dle parser CLI

            usage:
              <word>            fetch https://dle.rae.es/<word> and dump JSON
              --url <url>       parse any DLE page URL (homographs, deep links)
              --file <page.html> parse a local HTML page (no network)

            options:
              -o, --output <path>  write JSON to a file (default: stdout)
              -h, --help           show this help

            The JSON is the shared golden schema (see testdata/dle/*.json); pipe it to
            'app/src/test/js/cli.js' for a browser preview of the rendered entry.
            """.trimIndent()
        )
    }
}