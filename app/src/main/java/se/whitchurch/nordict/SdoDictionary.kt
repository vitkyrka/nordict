package se.whitchurch.nordict

import okhttp3.OkHttpClient

class SdoDictionary(client: OkHttpClient) : DslDictionary(client) {
    override val shortName: String = "sdo"
    override val tag: String = "SDO"
    override val flag: Int = R.drawable.flag_sedk
    override val filters: Map<String, String> = emptyMap()
}