package se.whitchurch.nordict

import okhttp3.OkHttpClient

/** Gran Diccionari de la Llengua Catalana (monolingual). */
class GdlcDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://www.diccionari.cat"
) : DiccionariDictionary(
    client, "GDLC", "diccionari-gdlc", false,
    "diccionari_gdlc", "gran-diccionari-de-la-llengua-catalana", "GDLC", baseUrl
)