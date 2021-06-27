package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class WiktionaryParser {
    companion object {
        private fun parseDefinition(headword: Word, li: Element) {
            val clone = li.clone()

            clone.select("ul")?.forEach {
                it.remove()
            }

            val def = Word.Definition(clone.text(), li)
            headword.definitions.add(def)
            li.remove()
        }

        fun parse(page: String, uri: Uri, tag: String, shortName: String): Word? {
            val doc = Jsoup.parse(page, "https://${shortName}.m.wiktionary.org")

            val heading = doc.selectFirst("#section_0") ?: return null
            val word = heading.text()
            val element = doc.selectFirst("#content")
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content

            val summary = StringBuilder(word)

            val headword = Word(tag, word, word, summary.toString(), cleanpage, uri,
                    "https://${shortName}.m.wiktionary.org/",
                    element,
                    doc.head().html() + "<body>")

            // Remove other languages
            var preserve = false
            element.selectFirst(".mw-parser-output")?.children()?.forEach {
                if (preserve) {
                    it.addClass("open-block")
                } else {
                    it.remove()
                }

                preserve = false;

                // <span class="sectionlangue" id="fr">Français</span>
                it.selectFirst("#$shortName")?.let {
                    preserve = true
                }
            }

            // Remove translations
            preserve = true
            element.selectFirst("section")?.children()?.forEach {
                it.selectFirst("span.mw-headline")?.let { headline ->
                    Log.i("foo", headline.id())
                    if (headline.id().startsWith("Traductions")) {
                        it.remove()
                        preserve = false;
                        return@forEach
                    }
                }

                if (it.tagName() != "div") preserve = true
                if (!preserve) it.remove()
            }

            doc.select("audio")?.forEach audio@{ audio ->
                val source = audio.selectFirst("source") ?: return@audio
                val url = source.attr("src")

                headword.audio.add("https:$url")
            }

            element.selectFirst("ol")?.children()?.forEach {
                parseDefinition(headword, it)
            }

            return headword
        }
    }
}