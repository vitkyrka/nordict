package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DleParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page)

            doc.selectFirst(".compartir")?.remove()
            doc.selectFirst(".bloqueIn")?.remove()
            doc.select(".faldon_publi").forEach { it.remove() }
            doc.selectFirst("#ct")?.remove()
            doc.selectFirst("#block-superfish")?.remove()
            doc.selectFirst("#app")?.remove()
            doc.selectFirst("#patrocinio")?.remove()
            doc.select(".otras").forEach { it.remove() }
            doc.select("script").forEach { it.remove() }
            doc.selectFirst(".region-header")?.remove()
            doc.selectFirst("p.o")?.remove()
            doc.selectFirst("footer")?.remove()

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

                val taglemma = lemma.selectFirst("header") ?: return@forEach;
                val word = taglemma.text().trim('"')
                val summary = StringBuilder(word)

//                val type = lemma.selectFirst(".tag_wordtype")?.text() ?: ""
//                if (type.isNotEmpty()) {
//                    summary.append(" ($type)")
//
//                    if (type.contains("subst")) {
//                        if (type.contains("plural")) {
//                            lemma.addClass("plural")
//                        } else {
//                            lemma.addClass("singular")
//                        }
//                        if (typ e.contains("masculino")) {
//                            lemma.addClass("masculine")
//                        } else if (type.contains("feminino")) {
//                            lemma.addClass("feminine")
//                        }
//                    }
//                }

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    "https://dle.rae.es/",
                    doc,
                    "",
                    lemma
                )

                headword.xrefs.add(ref.toString())

                var title = headword.mTitle
                var wrapper: Element? = null

                lemma.select("p").forEach { meaning ->
                    if (meaning.className().startsWith("l")) {
                        // Links
                        meaning.remove()
                        return@forEach
                    }
                    if (meaning.className() == "k5") {
                        title = meaning.text()
                        wrapper = Element("div")
                        wrapper?.appendChild(meaning)
                        return@forEach
                    }
                    val w = wrapper?.clone()

                    if (w != null) {
                        w.appendChild(meaning)
                        val definition = Word.Definition(meaning.text(), w, title)
                        headword.definitions.add(definition)
                    } else {
                        val definition = Word.Definition(meaning.text(), meaning)
                        headword.definitions.add(definition)
                        meaning.remove()
                    }
                }

//                lemma.select(".example .tag_s").forEach {
//                    definition.examples.add(it.text())
//                }
//
//                lemma.select("a.audio").forEach {
//                    val id = it.attr("id")
//
//                    if (!id.startsWith("PT_PT")) {
//                        return@forEach
//                    }
//
//                    headword.audio.add("https://www.linguee.pt/mp3/$id.mp3")
//                }

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