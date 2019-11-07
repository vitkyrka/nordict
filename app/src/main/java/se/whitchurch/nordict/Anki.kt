package se.whitchurch.nordict

import android.content.Context
import com.ichi2.anki.api.AddContentApi
import org.json.JSONArray

class Anki(val context: Context) {
    private var api: AddContentApi = AddContentApi(context)

    private fun getDeckId(): Long? {
        val deckList = api.deckList
        if (deckList != null) {
            for (entry in deckList.entries) {
                if (entry.value == DECK_NAME) {
                    return entry.key
                }
            }
        }

        return api.addNewDeck(DECK_NAME)
    }

    private fun getModelId(): Long? {
        val modelList = api.modelList
        if (modelList != null) {
            for (entry in modelList.entries) {
                if (entry.value == Model.NAME) {
                    return entry.key
                }
            }
        }

        return api.addNewCustomModel(Model.NAME, Model.FIELDS, Model.CARD_NAMES,
                Model.getQuestionFormats(context),
                Model.AFMT, null, null, null)
    }

    fun createCard(text: String, examples: List<String>, images: List<String>, audio: String): Long? {
        // Anki centers text by default, which is not optimal for large HTML pages.
        val back = "<div style=\"text-align: left\">$text</div>"

        val fields = arrayOf(
                JSONArray(images).toString(),
                JSONArray(examples).toString(),
                audio,
                back)

        return api.addNote(getModelId()!!, getDeckId()!!, fields, null)
    }

    companion object {
        const val DECK_NAME = "Nordict"
    }
}