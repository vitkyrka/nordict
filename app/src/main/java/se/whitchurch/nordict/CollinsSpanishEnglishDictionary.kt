package se.whitchurch.nordict

import okhttp3.OkHttpClient

class CollinsSpanishEnglishDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://www.collinsdictionary.com"
) : CollinsDictionary(client, baseUrl) {
    override val dictCode: String = "spanish-english"
    override val tag: String = "COLSPAN"
    override val lang: String = "es"
    override val flag: Int = R.drawable.flag_es
}