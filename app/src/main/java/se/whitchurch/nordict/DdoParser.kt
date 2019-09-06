package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup

class DdoParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String = "foo"): Word {
            val doc = Jsoup.parse(page, "https://ordnet.dk/ddo/")

            val word = doc.selectFirst(".match").ownText()
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