package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DleParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            var doc = Jsoup.parse(page)

            val element = doc.selectFirst("#resultados")

            element.selectFirst(".o-container")?.remove()
            element.selectFirst("#conjugacionfYMCdHV")?.remove()
            // Remove this since they end up before the definitions.  The same information
            // is also present separately for each definition which is better.
            element.selectFirst("#sinonimosDgIqVCc")?.remove()

            val content = element.outerHtml()
            val cleanPage = doc.head().html() + "<body>" + content
            doc = Jsoup.parse(cleanPage)


            var first = true
            var ref = 0

            doc.select("abbr").forEach { el ->
                val title = el.attr("title")
                if (title.isNotEmpty()) {
                    el.text(title)
                }
            }

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

                lemma.select("ol.c-definitions > li").forEach { meaning ->
                    var definition: Word.Definition? = null

                    val genderEl = meaning.selectFirst("abbr")
                    if (genderEl != null) {
                        val gender = genderEl.attr("title")
                        if (gender == "nombre femenino" || gender == "nombre femenino plural") {
                            genderEl.addClass("feminine")
                            genderEl.addClass("rae")
                        } else if (gender == "nombre masculino" || gender == "nombre masculino plural") {
                            genderEl.addClass("masculine")
                            genderEl.addClass("rae")
                        }
                    }

                    //val tmp = Element("div")
                    //tmp.appendChild(meaning)
                    definition = Word.Definition(meaning.text(), meaning)

                    meaning.select(".h").forEach { example ->
                        definition.examples.add(example.text())
                    }

                    headword.definitions.add(definition)
                    meaning.remove()
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