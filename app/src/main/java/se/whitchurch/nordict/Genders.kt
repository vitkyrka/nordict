package se.whitchurch.nordict

// Gender values serialized into the Word JSON (`gender` field on
// Word.Definition / Word.Idiom). The same values are mirrored in
// app/src/main/assets/renderer.js (GENDERS). Keep both in sync.
//
// GRAMMAR_* are the source grammar strings EST/DLE read from the RAE HTML
// `<abbr class="gram" title="...">` markers.
object Genders {
    const val FEMININE = "femenino"
    const val MASCULINE = "masculino"

    const val GRAMMAR_FEMININE = "nombre femenino"
    const val GRAMMAR_FEMININE_PLURAL = "nombre femenino plural"
    const val GRAMMAR_MASCULINE = "nombre masculino"
    const val GRAMMAR_MASCULINE_PLURAL = "nombre masculino plural"
}