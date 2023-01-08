package se.whitchurch.nordict

import okhttp3.OkHttpClient

class CollinsFrenchEnglishDictionary(client: OkHttpClient) : CollinsDictionary(client) {
    override val dictCode: String = "french-english"
    override val tag: String = "COLFREN"
    override val lang: String = "fr"
    override val flag: Int = R.drawable.flag_fr
}