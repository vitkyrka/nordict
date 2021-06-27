package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class WiktionaryParser {
    companion object {
        private fun parseDefinition(headword: Word, li: Element) {
            val def = Word.Definition("", li)
            headword.definitions.add(def)
        }

        fun parse(page: String, uri: Uri, tag: String, shortName: String): Word? {
            val doc = Jsoup.parse(page, "https://${shortName}.m.wiktionary.org")

            val heading = doc.selectFirst("#section_0") ?: return null
            val word = heading.text()
            val element = doc.selectFirst("#bodyContent")
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content

            val summary = StringBuilder(word)

            val headword = Word(tag, word, word, summary.toString(), cleanpage, uri,
                    "https://${shortName}.m.wiktionary.org/",
                    element,
                    doc.head().html() + "<body>")

            doc.select("audio")?.forEach audio@{ audio ->
                val source = audio.selectFirst("source") ?: return@audio
                val url = source.attr("src")

                headword.audio.add("https:$url")
            }

            element.selectFirst("ol")?.select("li")?.forEach {
                parseDefinition(headword, it)
            }

            return headword
        }
    }
}