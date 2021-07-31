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

            headword.gender =
                if (definite.endsWith("t") || neutr >= 0 && (ngenus < 0 || neutr < ngenus)) {
                    "t"
                } else {
                    "n"
                }

            // Highlight the plural for common gender but the definite form singular for neuter
            // nouns, to help in applying the technique described in https://bit.ly/EN-ETT-in-Swedish.
            lemma.selectFirst("span.bojning")?.let {
                var html = it.html()
                val parts = it.text().split("[ ,]".toRegex())
                var plural: String = ""
                var addPlural: Boolean = false

                if (ngenus < 0 && neutr < 0) {
                    if (parts.size == 2) {
                        plural = parts[1]
                        addPlural = true
                    } else if (parts.size > 2 && parts[1] !in arrayOf("", "äv.", "el.", "plur.")) {
                        plural = parts[1]
                        addPlural = true
                    } else if (parts.size > 2) {
                        for ((i, word) in parts.withIndex()) {
                            if (word == "plur.") {
                                plural = parts[i + 1]
                                break
                            }
                        }
                    }
                }

                if (plural.isNotEmpty()) {
                    if (addPlural) {
                        html =
                            html.replaceFirst(plural, "<span class=\"tempmm\">plur.</span> $plural")
                    }

                    if (headword.gender == "n") {
                        html = html.replaceFirst(
                            "<span class=\"tempmm\">plur.</span> $plural",
                            "<span class=\"tempmm\">plur.</span> <strong>$plural</strong>"
                        )
                    }
                }

                if (headword.gender == "t") {
                    html = html.replace(parts[0], "<strong>${parts[0]}</strong>")

                    if (plural.isNotEmpty()) {
                        // Third declension neuter nouns
                        if (!headword.mTitle.endsWith("er") && plural.endsWith("er")) {
                            html = html.replaceFirst(
                                "<span class=\"tempmm\">plur.</span> $plural",
                                "<span class=\"tempmm\">plur.</span> <strong>$plural</strong>"
                            )
                        }
                    }
                }

                it.html(html)
            }
        }

        private fun makeIdiom(fras: String, lexblock: Element): Word.Idiom? {
            var def = lexblock.selectFirst(".idiomdef")?.text() ?: return null

            lexblock.selectFirst(".idiomdeft")?.let {
                def += " (${it.text()})"
            }

            lexblock.selectFirst(".ikom")?.let {
                def += " [${it.text()}]"
            }

            lexblock.selectFirst(".idiomxnr")?.let {
                def = "${it.text()}. $def"
            }

            val obj = Word.Idiom(fras, def)

            obj.examples.addAll(lexblock.select(".idiomex").map { it.text() })

            return obj
        }

        fun parseDisambiguation(page: String): List<Uri> {
            val urls: ArrayList<Uri> = ArrayList()
            val doc = Jsoup.parse(page, "https://svenska.se/so/")
            val element = doc.select(".artikel").first()

            element.select("a.slank")?.forEach {
                urls.add(Uri.parse("https://svenska.se" + it.attr("href")))
            }

            return urls
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

            doc.select(".superlemma")?.forEach lemma@{ lemma ->
                val xrefs = ArrayList<String>()

                xrefs.add(lemma.attr("id"))

                val grundform = lemma.select("span.orto").first()?.text()
                    ?: return words
                val word = grundform.replace("`", "").replace("´", "")

                val summary = StringBuilder(grundform)

                lemma.select("span.uttal").first()?.let {
                    summary.append(" ")
                    summary.append(it.text())
                }

                lemma.select("span.expansion")?.forEach {
                    it.removeClass("collapsed")
                }

                lemma.select("span.bojning").first()?.let {
                    it.html(it.html().replace("~", word))

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

                val audio = arrayListOf<String>()

                lemma.select("a.ljudfil")?.forEach {
                    val onclick = it.attr("onclick")?.toString() ?: return@forEach
                    val mp3 = mp3Regex.find(onclick)?.groupValues?.get(1) ?: return@forEach
                    val url = "https://isolve-so-service.appspot.com/pronounce?id=$mp3.mp3"

                    it.attr("href", url)
                    it.removeAttr("onclick")

                    audio.add(url)
                }

                val headword = Word(
                    tag, word, word, summary.toString(), cleanpage,
                    Uri.parse("https://svenska.se/so/?id=$id&ref=${xrefs[0]}"),
                    // Required to avoid CORS errors in getCSS
                    "file://",
                    element, header, lemma
                )

                parseGrammar(headword, lemma)

                if (headword.gender == "t") {
                    lemma.addClass("neuter")
                }

                headword.audio.addAll(audio)

                lemma.select(".lexem")?.forEach lexem@{ lexem ->
                    xrefs.add(lexem.attr("id"))
                    xrefs.add(lexem.selectFirst(".kernel").attr("id"))

                    lexem.select(".idiom")?.forEach idiom@{ idiom ->
                        val fras = idiom.selectFirst(".fras").text()

                        if (idiom.selectFirst(".idiomlexblock") == null) {
                            makeIdiom(fras, idiom)?.let {
                                headword.idioms.add(it)
                            }
                        } else {
                            // E.g. det står/är skrivet i stjärnorna
                            idiom.select(".idiomlexblock").forEach { lexblock ->
                                makeIdiom(fras, lexblock)?.let {
                                    headword.idioms.add(it)
                                }
                            }
                        }
                    }

                    val def = lexem.selectFirst(".def")?.text() ?: return@lexem
                    val definition = Word.Definition(def, lexem)

                    lexem.select(".syntex").forEach { exempelBlock ->
                        definition.examples.add(exempelBlock.text())
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