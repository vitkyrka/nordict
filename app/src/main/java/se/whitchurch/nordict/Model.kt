package se.whitchurch.nordict

import android.content.Context

internal object Model {
    val NAME = "se.whitchurch.nordict.model8"
    val FIELDS = arrayOf("Images", "Sentences", "Audio", "Back")
    val CARD_NAMES = arrayOf("Card 1")
    val AFMT = arrayOf(
        "{{FrontSide}}\n\n"
                + "{{#Audio}}<audio autoplay controls src=\"{{Audio}}\" />{{/Audio}}"
                + "<hr id=answer>\n\n\n"
                + "{{Back}}"
    )

    fun getQuestionFormats(context: Context): Array<String> {
        try {
            val am = context.assets
            val `is` = am.open("model.question.html")
            return arrayOf(Utils.inputStreamToString(`is`))
        } catch (e: java.io.IOException) {
            return arrayOf("Error")
        }
    }
}
