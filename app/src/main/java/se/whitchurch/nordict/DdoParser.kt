package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class DdoParser {
    companion object {
        private val mp3Regex = "([0-9_]+)".toRegex()

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

                summary.append(" ")
                summary.append(span.text())
            }

            val headword = Word(tag, word, word, summary.toString(), cleanpage, uri, element,
                    doc.head().html() + "<body>")

            doc.select("img[src='speaker.gif']")?.forEach {
                val onClick = it.attr("onclick")
                val mp3id = mp3Regex.find(onClick)?.groupValues?.get(1) ?: return@forEach
                val url = "https://static.ordnet.dk/mp3/" + mp3id.substring(0, 5) + "/" + mp3id + ".mp3";

                headword.audio.add(url)
            }

            doc.selectFirst(".allow-glossing")?.let {
                val text = it.text() ?: return@let
                if (!text.contains("substantiv")) return@let

                headword.gender = if (text.contains("intetkøn")) {
                    "t"
                } else {
                    "n"
                }

            }

            if (headword.gender == "t") {
                doc.selectFirst(".match")?.addClass("neuter")
                element.addClass("neuter")
            }

            return headword
        }
    }
}