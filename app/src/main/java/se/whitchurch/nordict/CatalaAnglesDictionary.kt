package se.whitchurch.nordict

import okhttp3.OkHttpClient

/** Diccionari català-anglès (bilingual). */
class CatalaAnglesDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://www.diccionari.cat"
) : DiccionariDictionary(
    client, "CA-EN", "diccionari-ca-en", true,
    "diccionari_ca_en", "diccionari-catala-angles", baseUrl
)