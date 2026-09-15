package se.whitchurch.nordict

import android.content.Context
import com.ichi2.anki.api.AddContentApi

/**
 * The Android adapter that talks to AnkiDroid's content provider. The card
 * pipeline lives in :core ([AnkiClient], [Cards]); this class is the only
 * place the Android-only AnkiDroid library is touched.
 */
class AnkiDroidApi(private val api: AddContentApi) : AnkiApi {

    constructor(context: Context) : this(AddContentApi(context))

    override fun deckList(): Map<Long, String>? = api.deckList
    override fun addNewDeck(name: String): Long? = api.addNewDeck(name)
    override fun modelList(): Map<Long, String>? = api.modelList
    override fun addNewCustomModel(
        name: String,
        fields: Array<String>,
        cardNames: Array<String>,
        questionFormats: Array<String>,
        answerFormats: Array<String>,
        css: String?,
        did: Long?,
        usn: Int?
    ): Long? = api.addNewCustomModel(name, fields, cardNames, questionFormats, answerFormats, css, did, usn)

    override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>?): Long? =
        api.addNote(modelId, deckId, fields, tags)
}