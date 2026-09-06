package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class EstParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String, baseUrl: String = "https://www.rae.es/diccionario-estudiante/"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            var doc = Jsoup.parse(page)

            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val element = doc.selectFirst("#resultados") ?: return emptyList()
            element.selectFirst(".verDLE")?.remove()

            val content = element.outerHtml()
            val cleanPage = doc.head().html() + "<body>" + content
            doc = Jsoup.parse(cleanPage)

            doc.select("abbr").forEach { el ->
                val title = el.attr("title")
                if (title.isNotEmpty()) {
                    el.text(title)
                }
            }

            var first = true
            var ref = 0

            doc.select("article").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val taglemma = lemma.selectFirst("header span.entrada") ?: return@forEach
                val word = taglemma.text().trim('"')
                val summary = StringBuilder(word)

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    finalBaseUrl,
                    doc,
                    "",
                    lemma,
                    renderAsJson = true
                )

                headword.xrefs.add(ref.toString())

                // Definitions
                lemma.select("> div.acep").forEach { meaning ->
                    val defText = meaning.selectFirst(".def")?.text() ?: meaning.text()
                    val definition = Word.Definition(defText, meaning.clone())
                    definition.grammar = meaning.selectFirst(".gram")?.text() ?: ""
                    val domainEl = meaning.selectFirst(".domain")
                    definition.domain = domainEl?.attr("title") ?: ""

                    meaning.select(".ejemplo").forEach { example ->
                        definition.examples.add(example.text())
                    }
                    headword.definitions.add(definition)
                    meaning.remove()
                }

                // Idioms
                lemma.select(".locs .fc").forEach { fc ->
                    val idiomName = fc.selectFirst(".headword-fc")?.text() ?: ""
                    fc.select(".acep").forEach { meaning ->
                        val defText = meaning.selectFirst(".def")?.text() ?: meaning.text()
                        val idiom = Word.Idiom(idiomName, defText)
                        meaning.select(".ejemplo").forEach { example ->
                            idiom.examples.add(example.text())
                        }
                        headword.idioms.add(idiom)
                    }
                }

                lemma.remove()
                words.add(headword)
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }

                for (word in words) {
                    word.mHomographs.addAll(homographs)
                }
            }

            return words
        }
    }
}
