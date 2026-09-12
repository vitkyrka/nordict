package se.whitchurch.nordict

import okhttp3.OkHttpClient

/** Diccionari català-castellà (bilingual). */
class CatalaCastellaDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://www.diccionari.cat"
) : DiccionariDictionary(
    client, "CA-ES", "diccionari-ca-es", true,
    "diccionari_ca_es_", "diccionari-catala-castella", baseUrl
)