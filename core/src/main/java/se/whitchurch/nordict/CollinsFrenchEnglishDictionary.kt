package se.whitchurch.nordict

import okhttp3.OkHttpClient

class CollinsFrenchEnglishDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://www.collinsdictionary.com",
    pageFetcher: PageFetcher? = null
) : CollinsDictionary(client, baseUrl, pageFetcher) {
    override val dictCode: String = "french-english"
    override val tag: String = "COLFREN"
    override val lang: String = "fr"
    override val flagCode: String = "fr"
}