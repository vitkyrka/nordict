package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.math.max

class LeRobertParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val doc = Jsoup.parse(page, "https://dictionnaire.lerobert.com/")


            var main = doc.selectFirst("div.ws-c") ?: return words
            val word = main.selectFirst("h1")?.text() ?: return words
            val cleanpage = doc.head().html() + "<body>" + main

            main.selectFirst("div.ws-a")?.remove()

            Log.i("foo", cleanpage);

            val headword = Word(
                tag, word, word, word.toString(), cleanpage, uri,
                "https://dictionnaire.lerobert.com/",
                main,
                doc.head().html() + "<body>"
            )

            words.add(headword)
            return words
        }
    }
}