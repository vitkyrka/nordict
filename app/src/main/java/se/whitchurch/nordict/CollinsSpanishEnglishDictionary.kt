package se.whitchurch.nordict

import okhttp3.OkHttpClient

class CollinsSpanishEnglishDictionary(client: OkHttpClient) : CollinsDictionary(client) {
    override val dictCode: String = "spanish-english"
    override val tag: String = "COLSPAN"
    override val lang: String = "es"
    override val flag: Int = R.drawable.flag_es
}