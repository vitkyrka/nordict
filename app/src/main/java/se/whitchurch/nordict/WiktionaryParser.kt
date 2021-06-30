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

            li.select("span.sources")?.forEach {
                it.remove()
            }

            li.selectFirst("ul")?.select("li")?.forEach {
                def.examples.add(it.text())
            }

            headword.definitions.add(def)
            li.remove()
        }

        fun parse(page: String, uri: Uri, tag: String, shortName: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page, "https://${shortName}.m.wiktionary.org")

            val heading = doc.selectFirst("#section_0") ?: return words
            val word = heading.text()
            val element = doc.selectFirst("#content")
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content

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

            val lemmas = ArrayList<Element>()
            var current = Element("div")
            var etymology: Element? = null
            var pronunciation: Element? = null
            element.selectFirst("section")?.children()?.forEach {
                if (it.tagName() == "h3") {
                    current = Element("div")

                    when {
                        it.selectFirst("span.titreetym") != null -> {
                            etymology = current
                        }
                        it.selectFirst("span.titrepron") != null -> {
                            pronunciation = current
                        }
                        it.selectFirst("span.titredef") != null -> {
                            lemmas.add(current)
                        }
                    }
                }

                current.appendChild(it)
            }

            var first = true
            lemmas.forEach lemma@{ lemma ->
                val summary = StringBuilder()
                val ref: String

                val titledef = lemma.selectFirst(".titredef") ?: return@lemma

                summary.append(titledef.text())
                ref = titledef.id()

                lemma.selectFirst("p")?.let {
                    summary.append(" ")
                    summary.append(it.text())
                }

                pronunciation?.let { lemma.appendChild(it.clone()) }
                etymology?.let { lemma.appendChild(it.clone()) }

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref).build()
                }

                first = false

                val headword = Word(tag, word, word, summary.toString(), cleanpage, newUri,
                        "https://${shortName}.m.wiktionary.org/",
                        element,
                        doc.head().html() + "<body>",
                        lemma)

                headword.xrefs.add(ref)

                pronunciation?.select("audio")?.forEach audio@{ audio ->
                    val source = audio.selectFirst("source") ?: return@audio
                    val url = source.attr("src")

                    headword.audio.add("https:$url")
                }

                lemma.selectFirst("ol")?.children()?.forEach {
                    parseDefinition(headword, it)
                }

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