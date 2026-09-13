package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

class InfopediaParser {
    companion object {

        fun parse(page: String, uri: HttpUrl, tag: String): List<Word> {
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
                    uri.withQueryParam("__ref", ref.toString())
                }

                first = false

                val word = lemma.selectFirst(".dolEntrinfoEntrada")?.text() ?: return@forEach
                val summary = StringBuilder(word)

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    "https://www.infopedia.pt/",
                    doc,
                    "",
                    lemma,
                )

                headword.xrefs.add(ref.toString())

                lemma.select(".dolAcepsRow").forEach { el ->
                    val def = Word.Definition(el.text(), el)
                    headword.definitions.add(def)
                    el.remove()
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

        fun parseSearch(page: String): List<SearchResult> {
            val doc = Jsoup.parse(page)
            val base = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/"
                .toHttpUrlOrNull()!!

            return doc.select("li").mapNotNull {
                val title = it.attr("title")
                if (title.isEmpty()) null
                else SearchResult(title, base.newBuilder()!!.addPathSegment(title).build())
            }
        }
    }
}