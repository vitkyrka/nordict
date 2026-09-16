package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SdoDictionary(
    client: OkHttpClient,
    apiBaseUrl: HttpUrl = "https://ws.dsl.dk".toHttpUrlOrNull()!!,
    siteBaseUrl: HttpUrl = "https://ordnet.dk".toHttpUrlOrNull()!!
) : DslDictionary(client, apiBaseUrl, siteBaseUrl) {
    override val shortName: String = "sdo"
    override val tag: String = "SDO"
    override val lang: String = "se"
    override val flagCode: String = "sedk"

    override fun parsePage(page: String, uri: HttpUrl, baseUrl: String): List<Word> =
        SdoParser.parse(page, uri, tag, baseUrl)
}