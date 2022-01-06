package se.whitchurch.nordict

import okhttp3.OkHttpClient

class FrWiktionary(client: OkHttpClient) : Wiktionary(client) {
    override val shortName: String = "fr"
    override val tag: String = "WFR"
    override val lang: String = "fr"
    override val flag: Int = R.drawable.flag_wfr
}