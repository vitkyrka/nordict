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
                val example = it.text()
                if (example.contains("Exemple d’utilisation manquant")) return@forEach

                def.examples.add(example)
            }

            headword.definitions.add(def)
            li.remove()
        }

        fun parse(page: String, uri: Uri, tag: String, shortName: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page, "https://${shortName}.m.wiktionary.org")

            val heading = doc.selectFirst("#section_0") ?: return words
            val word = heading.text()
            val element = doc.selectFirst("#bodyContent")
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

                preserve = false

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
                        preserve = false
                        return@forEach
                    }
                }

                if (it.tagName() != "div") preserve = true
                if (!preserve) it.remove()
            }

            val images = ArrayList<String>()

            // Make image loading non-lazy since the lazy stuff doesn't seem to always work
            // <noscript><img ...></noscript><span class="lazy-image-placeholder" ...>
            element.select("span.lazy-image-placeholder")?.forEach {
                it.remove()
            }
            element.select("noscript")?.forEach {
                val outside = it.parent()

                it.select("img").forEach img@{ img ->
                    val src = "https:${img.attr("src")}"

                    // Ignore CentralAutoLogin web beacon
                    if (!src.contains("upload.wikimedia.org")) {
                        return@img
                    }

                    // Add https: since Anki needs it
                    img.attr("src", src)
                    img.appendTo(outside)
                    images.add(src)
                }
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

                val pos = titledef.text()
                summary.append(pos)
                ref = titledef.id()

                titledef.html("$word <span class=\"pos\">${pos}</span>")

                lemma.selectFirst("p")?.let {
                    summary.append(" ")
                    summary.append(it.text())
                    it.addClass("lemma-heading")
                }

                if (pos.contains("Nom commun")) {
                    var masculine = false;

                    val masculin = summary.indexOf("masculin")
                    val feminin = summary.indexOf("féminin")

                    if (masculin >= 0 && (feminin < 0 || masculin < feminin)) {
                        masculine = true;
                    }

                    if (masculin >= 0 || feminin >= 0) {
                        val article = if (masculine) "un " else "une "
                        lemma.selectFirst("p")?.let {
                            it.prepend(article)
                        }

                        titledef.prepend("<span class=\"inserted-article\">$article</span>")
                        lemma.addClass(if (masculine) "masculine" else "feminine")
                    }
                }

                pronunciation?.let { lemma.appendChild(it.clone()) }
                etymology?.let { lemma.appendChild(it.clone()) }

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref).build()
                }

                first = false

                val headword = Word(
                    tag, word, word, summary.toString(), cleanpage, newUri,
                    "https://${shortName}.m.wiktionary.org/",
                    element,
                    doc.head().html() + "<body>",
                    lemma
                )

                headword.images.addAll(images)
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