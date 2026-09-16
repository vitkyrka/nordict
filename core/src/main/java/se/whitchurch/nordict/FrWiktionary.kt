package se.whitchurch.nordict

import okhttp3.OkHttpClient

class FrWiktionary(client: OkHttpClient, baseUrl: String = "") : Wiktionary(client, baseUrl) {
    override val shortName: String = "fr"
    override val tag: String = "WFR"
    override val lang: String = "fr"
    override val flagCode: String = "fr"
}