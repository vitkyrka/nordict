package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class SoParser {
    companion object {
        private val mp3Regex = """\('(.*)'\)""".toRegex()

        private fun normalizePos(pos: String): Pos = when (pos) {
            "adjektiv" -> Pos.ADJECTIVE
            "adverb" -> Pos.ADVERB
            "konjunktion" -> Pos.CONJUNCTION
            "interjektion" -> Pos.INTERJECTION
            "pronomen" -> Pos.PRONOUN
            "substantiv" -> Pos.NOUN
            "verb" -> Pos.VERB
            else -> Pos.UNKNOWN
        }

        private fun parseGrammar(headword: Word, lemma: Element) {
            lemma.selectFirst(".ordklass")?.let {
                headword.pos = normalizePos(it.text().trim())
            }

            if (headword.pos != Pos.NOUN) {
                return
            }

            val bojning = lemma.selectFirst(".bojning")?.text() ?: return
            val definite = bojning.split(" ")[0].replace(",", "")

            // vidkommande has "ingen böjning, neutr."
            // exodus has "ingen böjning, n-genus el. neutr."
            // underscore has "ingen böjning, neutr. äv n-genus"
            val neutr = bojning.indexOf("neutr.")
            val ngenus = bojning.indexOf("n-genus")

            headword.gender = if (definite.endsWith("t") || neutr >= 0 && (ngenus < 0 || neutr < ngenus)) {
                "t"
            } else {
                "n"
            }
        }

        fun parse(page: String, tag: String = "foo"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page, "https://svenska.se/so/")

            val element = doc.select(".artikel").first()
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content
            val header = doc.head().html() + "<body>"

            val selfUrl = doc.select(".gold").first().parent().attr("href")
                    ?: return words
            val uri = Uri.parse(selfUrl)
            val id = uri.getQueryParameter("id") ?: return words

            doc.select(".lemma")?.forEach lemma@{ lemma ->
                val xrefs = ArrayList<String>()

                xrefs.add(lemma.attr("id"))

                val grundform = lemma.select("span.grundform").first()?.text()
                        ?: return words
                val word = grundform.replace("`", "").replace("´", "")

                val summary = StringBuilder(grundform)

                lemma.select("span.uttal").first()?.let {
                    summary.append(" ")
                    summary.append(it.text())
                }

                lemma.select("span.bojning").first()?.let {
                    val text = it.text()

                    // ingen böjning
                    if (text.contains("ingen")) {
                        return@let
                    }

                    if (summary.isNotEmpty()) {
                        summary.append(" ")
                    }
                    summary.append(text)
                }

                lemma.select("a.ljudfil")?.forEach {
                    val onclick = it.attr("onclick")?.toString() ?: return@forEach
                    val mp3 = mp3Regex.find(onclick)?.groupValues?.get(1) ?: return@forEach

                    it.attr("href", "https://isolve-so-service.appspot.com/pronounce?id=$mp3")
                    it.removeAttr("onclick")
                }

                val headword = Word(tag, word, word, summary.toString(), cleanpage,
                        Uri.parse("https://svenska.se/so/?id=$id&ref=${xrefs[0]}"),
                        element, header, lemma)

                parseGrammar(headword, lemma)

                if (headword.gender == "t") {
                    lemma.addClass("neuter")
                }

                lemma.select(".lexem")?.forEach lexem@{ lexem ->
                    xrefs.add(lexem.attr("id"))
                    xrefs.add(lexem.selectFirst(".kernel").attr("id"))

                    lexem.select(".idiom")?.forEach idiom@{ idiom ->
                        val fras = idiom.selectFirst(".fras").text()
                        val def = idiom.selectFirst(".idiomdef")?.text() ?: return@idiom
                        val obj = Word.Idiom(fras, def)

                        obj.examples.addAll(idiom.select(".idiomex").map { it.text() })
                        headword.idioms.add(obj)
                    }

                    val def = lexem.selectFirst(".def")?.text() ?: return@lexem
                    val definition = Word.Definition(def, lexem)

                    lexem.select(".exempelblock").forEach { exempelBlock ->
                        exempelBlock.children().forEach {
                            definition.examples.addAll(it.children().map { it.text() })
                        }
                    }

                    headword.definitions.add(definition)
                    lexem.remove()
                }

                headword.xrefs.addAll(xrefs)

                lemma.remove()
                words.add(headword)
            }

            element.select("br")?.remove()

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