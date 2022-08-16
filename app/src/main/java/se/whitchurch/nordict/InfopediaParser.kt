package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class InfopediaParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page)

            doc.selectFirst("nav")?.remove()
            doc.selectFirst(".nav-container")?.remove()
            doc.selectFirst(".favorites")?.remove()
            doc.selectFirst("#footer-body")?.remove()
            doc.selectFirst("#footer-header-arrow")?.remove()
            doc.selectFirst(".partilharReferenciarContainer")?.remove()
            doc.select("script").forEach { it.remove() }
            doc.select(".google-pub-container").forEach { it.remove() }
            doc.select(".col-widgets").forEach { it.remove() }
            doc.select("iframe").forEach { it.remove() }
            doc.select("ins").forEach { it.remove() }
            doc.selectFirst("#comments-and-suggestions-modal")?.remove()
            doc.selectFirst("#commentsContainer")?.remove()
            doc.selectFirst(".dolGestualBaseContainer")?.remove()
            doc.selectFirst("#support-articles-info")?.remove()

            var first = true
            var ref = 0

            doc.select(".dolEntradaVverbete").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val word = lemma.selectFirst(".dolEntrinfoEntrada")?.text() ?: return@forEach
                val summary = StringBuilder(word)

//                val taglemma = lemma.selectFirst("span.tag_lemma") ?: return@forEach;
//                val word = taglemma.select("a.dictLink").map { it.text() }.joinToString(" ")
//                val summary = StringBuilder(word)
//
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
//                        if (type.contains("masculino")) {
//                            lemma.addClass("masculine")
//                        } else if (type.contains("feminino")) {
//                            lemma.addClass("feminine")
//                        }
//                    }
//                }
//
//                val meanings =
//                    lemma.select("div.translation.featured span.tag_trans a.dictLink")
//                        .joinToString("; ") { it.text() }
//
//                summary.append(" $meanings")

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    "https://www.infopedia.pt/",
                    doc,
                    "",
                )

                headword.xrefs.add(ref.toString())
                val meanings = word
                val definition = Word.Definition(meanings, lemma)
                headword.definitions.add(definition)
//
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

        fun parseSearch(page: String): List<SearchResult> {
            val doc = Jsoup.parse(page)
            val base = Uri.parse("https://www.infopedia.pt/dicionarios/lingua-portuguesa/")

            return doc.select("li").map {
                val title = it.attr("title")
                SearchResult(title, base.buildUpon().appendEncodedPath(title).build())
            }
        }
    }
}