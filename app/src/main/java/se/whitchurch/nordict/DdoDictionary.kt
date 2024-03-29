package se.whitchurch.nordict

import okhttp3.OkHttpClient

class DdoDictionary(client: OkHttpClient) : DslDictionary(client) {
    override val shortName: String = "ddo"
    override val tag: String = "DDO"
    override val lang: String = "dk"
    override val flag: Int = R.drawable.flag_dk
}