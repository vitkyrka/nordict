package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class LingueeParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page)

            doc.selectFirst(".l_header")?.remove()
            doc.selectFirst(".footer")?.remove()
            doc.selectFirst(".lMainNavbar")?.remove()
            doc.selectFirst(".l_deepl_ad_container")?.remove()

            var first = true
            var ref = 0

            doc.select("div.exact div.lemma").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.buildUpon().appendQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val word = lemma.selectFirst("a.dictLink")?.text() ?: return@forEach
                val summary = StringBuilder(word)

                val type = lemma.selectFirst(".tag_wordtype")?.text() ?: ""
                if (type.isNotEmpty()) {
                    summary.append(" ($type)")

                    if (type.contains("plural")) {
                        lemma.addClass("plural")
                    } else {
                        lemma.addClass("singular")
                    }
                    if (type.contains("feminino")) {
                        lemma.addClass("feminine")
                    } else if (type.contains("masculino")) {
                        lemma.addClass("masculine")
                    }
                }

                val meanings =
                    lemma.select("div.translation.featured span.tag_trans a.dictLink")
                        .joinToString("; ") { it.text() }

                summary.append(" $meanings")

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    "https://www.linguee.pt/",
                    doc,
                    "",
                )

                headword.xrefs.add(ref.toString())

                val definition = Word.Definition(meanings, lemma)
                headword.definitions.add(definition)

                lemma.select(".example .tag_s").forEach {
                    definition.examples.add(it.text())
                }

                lemma.select("a.audio").forEach {
                    val id = it.attr("id")

                    if (!id.startsWith("PT_PT")) {
                        return@forEach
                    }

                    headword.audio.add("https://www.linguee.pt/mp3/$id.mp3")
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

        fun parseSearch(page: String, uri: Uri): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            val doc = Jsoup.parse(page, uri.toString())

            doc.select(".main_item").forEach {
                results.add(
                    SearchResult(
                        it.text(),
                        Uri.parse("https://www.linguee.pt" + it.attr("href"))
                    )
                )
            }

            return results;
        }
    }
}