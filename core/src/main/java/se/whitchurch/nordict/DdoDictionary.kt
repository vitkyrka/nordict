package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class DdoDictionary(
    client: OkHttpClient,
    apiBaseUrl: HttpUrl = "https://ws.dsl.dk".toHttpUrlOrNull()!!,
    siteBaseUrl: HttpUrl = "https://ordnet.dk".toHttpUrlOrNull()!!
) : DslDictionary(client, apiBaseUrl, siteBaseUrl) {
    override val shortName: String = "ddo"
    override val tag: String = "DDO"
    override val lang: String = "dk"
    override val flagCode: String = "dk"
}