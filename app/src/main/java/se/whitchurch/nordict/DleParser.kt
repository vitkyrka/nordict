package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DleParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String, baseUrl: String = "https://dle.rae.es/"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            var doc = Jsoup.parse(page)

            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

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
                    finalBaseUrl,
                    doc,
                    "",
                    lemma
                )

                headword.xrefs.add(ref.toString())

                var currentIdiom: String? = null
                lemma.select("h3.k5, h3.k6, ol.c-definitions").forEach { child ->
                    if (child.tagName() == "h3") {
                        currentIdiom = child.text()
                    } else if (child.tagName() == "ol") {
                        child.select("> li").forEach { meaning ->
                            val genderEl = meaning.selectFirst("abbr")
                            if (genderEl != null) {
                                val gender = genderEl.attr("title")
                                if (gender == Genders.GRAMMAR_FEMININE || gender == Genders.GRAMMAR_FEMININE_PLURAL) {
                                    genderEl.addClass("feminine")
                                    genderEl.addClass("rae")
                                } else if (gender == Genders.GRAMMAR_MASCULINE || gender == Genders.GRAMMAR_MASCULINE_PLURAL) {
                                    genderEl.addClass("masculine")
                                    genderEl.addClass("rae")
                                }
                            }

                            if (currentIdiom == null) {
                                val definition = Word.Definition(meaning.text(), meaning.clone())

                                meaning.select(".h").forEach { example ->
                                    definition.examples.add(example.text())
                                }

                                headword.definitions.add(definition)
                                meaning.remove()
                            } else {
                                val idiom = Word.Idiom(currentIdiom!!, meaning.text())

                                meaning.select(".h").forEach { example ->
                                    idiom.examples.add(example.text())
                                }

                                headword.idioms.add(idiom)
                            }
                        }
                        currentIdiom = null
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