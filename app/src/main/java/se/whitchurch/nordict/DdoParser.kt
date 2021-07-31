package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DdoParser {
    companion object {
        private val mp3Regex = "([0-9_]+)".toRegex()

        private fun parseDefinitions(headword: Word, betydninger: Element) {
            var defel = Element("div")
            var defText: String? = null
            var defExamples: ArrayList<String> = arrayListOf()

            for (el in betydninger.children()) {
                if (el.className() == "definitionNumber") {
                    if (defText != null) {
                        val def = Word.Definition(defText, defel)
                        def.examples.addAll(defExamples)
                        headword.definitions.add(def)

                        defel = Element("div")
                        defText = null
                        defExamples = arrayListOf()
                    }

                    defel.appendChild(el)
                } else if (el.className() == "definitionIndent") {
                    if (defText == null) defText = el.selectFirst(".definitionBox").text()
                    defel.appendChild(el)

                    el.select(".details").forEach { details ->
                        val label = details.selectFirst(".stempel")?.text() ?: return@forEach
                        if (label != "Eksempler") return@forEach

                        details.selectFirst(".inlineList")?.textNodes()?.forEach {
                            defExamples.add(it.toString().trim())
                        }
                    }

                    defExamples.addAll(el.select(".citat").map { it.text() })
                }
            }

            if (defText != null) {
                val def = Word.Definition(defText, defel)
                def.examples.addAll(defExamples)
                headword.definitions.add(def)
            }
        }

        fun parse(page: String, uri: Uri, tag: String = "foo"): Word? {
            val doc = Jsoup.parse(page, "https://ordnet.dk/ddo/")

            val match = doc.selectFirst(".match") ?: return null
            val word = match.ownText()
            val element = doc.select("#content").first()
            val content = element.outerHtml()
            val cleanpage = doc.head().html() + "<body>" + content

            val summary = StringBuilder(word)

            doc.selectFirst("#id-udt")?.let {
                val span = it.selectFirst(".allow-glossing") ?: return@let

                summary.append(" ")
                summary.append(span.text().split(" ")[0])
            }

            doc.selectFirst("#id-boj")?.let {
                val span = it.selectFirst(".allow-glossing") ?: return@let

                span.html(span.html().replace("-", word))

                summary.append(" ")
                summary.append(span.text().replace("-", word))
            }

            val headword = Word(
                tag, word, word, summary.toString(), cleanpage, uri,
                "https://ordnet.dk/ddo/", element,
                doc.head().html() + "<body>"
            )

            doc.select("img[src='speaker.gif']")?.forEach {
                val onClick = it.attr("onclick")
                val mp3id = mp3Regex.find(onClick)?.groupValues?.get(1) ?: return@forEach
                val url =
                    "https://static.ordnet.dk/mp3/" + mp3id.substring(0, 5) + "/" + mp3id + ".mp3"

                headword.audio.add(url)
            }

            doc.selectFirst(".allow-glossing")?.let {
                val text = it.text() ?: return@let
                if (!text.contains("substantiv")) return@let

                val neutral = text.indexOf("intetkøn")
                val common = text.indexOf("fælleskøn")

                // Compare cirkus and fond
                headword.gender = if (neutral >= 0 && (common < 0 || neutral < common)) {
                    "t"
                } else {
                    "n"
                }
            }

            if (headword.gender == "t") {
                doc.selectFirst(".match")?.addClass("neuter")
                element.addClass("neuter")
            }

            doc.selectFirst("#content-betydninger")?.let { betydninger ->
                parseDefinitions(headword, betydninger)
            }

            doc.selectFirst("#content-faste-udtryk")?.let { udtryk ->
                var defText: String? = null
                var defExamples: ArrayList<String> = arrayListOf()
                var idiomTitle = ""

                for (el in udtryk.children()) {
                    if (el.className() == "definitionIndent") {
                        if (defText == null) defText = el.selectFirst(".definitionBox").text()

                        el.select(".details").forEach { details ->
                            val label = details.selectFirst(".stempel")?.text() ?: return@forEach
                            if (label != "Eksempler") return@forEach

                            details.selectFirst(".inlineList")?.textNodes()?.forEach {
                                defExamples.add(it.toString().trim())
                            }
                        }

                        defExamples.addAll(el.select(".citat").map { it.text() })
                    } else if (el.className() == "definitionBox") {
                        if (defText != null) {
                            val def = Word.Idiom(idiomTitle, defText)
                            def.examples.addAll(defExamples)
                            headword.idioms.add(def)

                            defText = null
                            defExamples = arrayListOf()
                        }

                        idiomTitle = el.selectFirst(".match").text()
                    }
                }

                if (defText != null) {
                    val def = Word.Idiom(idiomTitle, defText)
                    def.examples.addAll(defExamples)
                    headword.idioms.add(def)
                }
            }

            if (headword.definitions.isEmpty() and headword.idioms.isEmpty()) {
                // Separate page for idiom, eg. klappe hesten
                doc.selectFirst(".artikel")?.let { parseDefinitions(headword, it) }
            }

            return headword
        }
    }
}