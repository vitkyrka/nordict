package se.whitchurch.nordict

/**
 * The Anki note model used for Nordict cards. Pure data so the app's
 * AnkiDroid adapter and the desktop CLI share one note-type definition.
 *
 * `QUESTION_FORMAT` is the front template: a script that reads the `Images`
 * and `Sentences` JSON arrays (rendered inline by Anki as `{{Images}}` /
 * `{{Sentences}}`) and writes 1-2 random images and 1-2 random sentences.
 */
object CardModel {
    const val NAME = "se.whitchurch.nordict.model8"
    val FIELDS = arrayOf("Images", "Sentences", "Audio", "Back")
    val CARD_NAMES = arrayOf("Card 1")
    val AFMT = arrayOf(
        "{{FrontSide}}\n\n"
                + "{{#Audio}}<audio autoplay controls src=\"{{Audio}}\" />{{/Audio}}"
                + "<hr id=answer>\n\n\n"
                + "{{Back}}"
    )

    const val QUESTION_FORMAT = """<script type="text/javascript">
var images = {{Images}};
var sentences = {{Sentences}};

function getRandomInt(min, max) {
	return Math.floor(Math.random() * (max - min)) + min;
}

function insert(array, callback) {
    var idx = getRandomInt(0, array.length);

    callback(array[idx]);
    document.write('<p>');

    if (array.length > 1) {
        idx = (idx + 1) % array.length;
        callback(array[idx]);
        document.write('<p>');
    }
}

if (images.length) {
    // The < is separated to avoid getting caught in the regexp used in
    // com.ichi2.libanki.Media.escapeImages()
    insert(images, function(s) { document.write('<' + 'img src="' + s + '"/>'); });
}

if (sentences.length) {
    insert(sentences, function(s) { document.write(s); });
}
</script>"""
}