package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import org.junit.Test

/**
 * Round-trip tests for the agent wire protocol: every command/result shape
 * must survive a Gson round trip unchanged, and unknown fields must be
 * ignored so the protocol can evolve without breaking older agents.
 */
class AgentProtocolTest {

    private val gson = AgentProtocol.gson

    private fun httpUrl(url: String): HttpUrl = url.toHttpUrlOrNull()!!

    private fun newWord(title: String = "frente", ref: String = ""): Word {
        val word = Word(
            dict = "DLE",
            mTitle = title,
            mSlug = "frente",
            summary = "frente",
            uri = httpUrl("https://dle.rae.es/frente")
        )
        word.dictionary = "Diccionario de la lengua española"
        if (ref.isNotEmpty()) word.xrefs.add(ref)
        return word
    }

    @Test
    fun commandRoundTripMinimal() {
        val cmd = AgentCommand(op = AgentOps.STATE)
        assertThat(gson.toJson(cmd)).isEqualTo("{\"op\":\"state\"}")
        assertThat(gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)).isEqualTo(cmd)
    }

    @Test
    fun commandRoundTripFull() {
        val cmd = AgentCommand(
            op = AgentOps.OPEN,
            query = "frente",
            title = "Frente",
            tag = "dle",
            lang = "es"
        )
        assertThat(gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)).isEqualTo(cmd)
    }

    @Test
    fun commandIgnoresUnknownFields() {
        val parsed = gson.fromJson(
            """{"op":"search","query":"gato","bogus":42}""",
            AgentCommand::class.java
        )
        assertThat(parsed).isEqualTo(AgentCommand(op = AgentOps.SEARCH, query = "gato"))
    }

    @Test
    fun createCardCommandRoundTripsIndex() {
        val cmd = AgentCommand(op = AgentOps.CREATE_CARD, index = 2)
        assertThat(gson.toJson(cmd)).isEqualTo("{\"op\":\"createCard\",\"index\":2}")
        val parsed = gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)
        assertThat(parsed.index).isEqualTo(2)
        assertThat(parsed).isEqualTo(cmd)

        // No index field parses to null (first proposal).
        val noIndex = gson.fromJson("""{"op":"createCard"}""", AgentCommand::class.java)
        assertThat(noIndex.index).isNull()
        assertThat(noIndex).isEqualTo(AgentCommand(op = AgentOps.CREATE_CARD))
    }

    @Test
    fun openCardsCommandRoundTrips() {
        val cmd = AgentCommand(op = AgentOps.OPEN_CARDS)
        assertThat(gson.toJson(cmd)).isEqualTo("{\"op\":\"openCards\"}")
        assertThat(gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)).isEqualTo(cmd)
    }

    @Test
    fun audioCommandRoundTripsUrl() {
        val cmd = AgentCommand(op = AgentOps.AUDIO, url = "https://example.com/a.mp3")
        assertThat(gson.toJson(cmd)).isEqualTo("{\"op\":\"audio\",\"url\":\"https://example.com/a.mp3\"}")
        assertThat(gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)).isEqualTo(cmd)

        // Without a url the loaded word's own audio list is used.
        val noUrl = gson.fromJson("""{"op":"audio"}""", AgentCommand::class.java)
        assertThat(noUrl.url).isNull()
        assertThat(noUrl).isEqualTo(AgentCommand(op = AgentOps.AUDIO))
    }

    @Test
    fun selectionTagsPreferTagsOverCommaTag() {
        val cmd = AgentCommand(op = AgentOps.SET_DICT, tag = "DLE,SO", tags = listOf("DLE", "EST"))
        assertThat(cmd.selectionTags()).containsExactly("DLE", "EST").inOrder()
        val comma = AgentCommand(op = AgentOps.SET_DICT, tag = " DLE , est ")
        assertThat(comma.selectionTags()).containsExactly("DLE", "est").inOrder()
        assertThat(AgentCommand(op = AgentOps.SET_DICT).selectionTags()).isEmpty()

        val parsed = gson.fromJson(gson.toJson(cmd), AgentCommand::class.java)
        assertThat(parsed.tags).containsExactly("DLE", "EST").inOrder()
        assertThat(parsed.selectionTags()).containsExactly("DLE", "EST").inOrder()
    }

    @Test
    fun combinedStateRoundTrips() {
        val state = AgentState(
            activity = "word",
            lang = "es",
            dict = "DLE,EST",
            dicts = listOf("DLE", "EST")
        )
        assertThat(gson.fromJson(gson.toJson(state), AgentState::class.java)).isEqualTo(state)
    }

    @Test
    fun stateSoundTriStateRoundTrips() {
        // The `sound` flag is the word bar's pronunciation enablement: true
        // when the word has audio, false when it has none, null when no word
        // is loaded — all three must cross the wire unchanged.
        assertThat(gson.fromJson(gson.toJson(
            AgentState(activity = "word", lang = "es", dict = "DLE", sound = true)
        ), AgentState::class.java).sound).isTrue()

        assertThat(gson.fromJson(gson.toJson(
            AgentState(activity = "word", lang = "es", dict = "DLE", sound = false)
        ), AgentState::class.java).sound).isFalse()

        val parsed = gson.fromJson(gson.toJson(
            AgentState(activity = "home", lang = "es", dict = "DLE")
        ), AgentState::class.java)
        assertThat(parsed.sound).isNull()
        assertThat(parsed).isEqualTo(AgentState(activity = "home", lang = "es", dict = "DLE"))

        // Unknown fields on the wire (older agents) stay ignored.
        val older = gson.fromJson(
            """{"activity":"word","lang":"es","dict":"DLE"}""",
            AgentState::class.java
        )
        assertThat(older.sound).isNull()
    }

    @Test
    fun requireReturnsArgumentOrThrows() {
        val cmd = AgentCommand(op = AgentOps.SEARCH)
        assertThat(cmd.require("query", "frente")).isEqualTo("frente")
        try {
            cmd.require("query", null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected.message).contains("search")
            assertThat(expected.message).contains("query")
        }
    }

    @Test
    fun errorResultRoundTrip() {
        val result = AgentResult.error(AgentOps.OPEN, "no such word")
        assertThat(result.ok).isFalse()
        assertThat(gson.fromJson(gson.toJson(result), AgentResult::class.java)).isEqualTo(result)
    }

    @Test
    fun okResultWithSearchResultsRoundTrip() {
        val results = listOf(
            WordJson.SearchResultData("frente", "1", "https://dle.rae.es/frente"),
            WordJson.SearchResultData("frentero", "2", "https://dle.rae.es/?id=AbC123")
        )
        val state = AgentState(activity = "WordActivity", dict = "DLE", lang = "es")
        val result = AgentResult(ok = true, op = AgentOps.SEARCH, state = state, results = results)

        val parsed = gson.fromJson(gson.toJson(result), AgentResult::class.java)
        assertThat(parsed).isEqualTo(result)
        assertThat(parsed.results).containsExactlyElementsIn(results).inOrder()
    }

    @Test
    fun wordResultRoundTrip() {
        val word = newWord(ref = "2")
        val gloss = Word.Gloss()
        gloss.definition = "Parte anterior de la cabeza"
        gloss.headword = "frente"
        gloss.grammar = "nombre femenino"
        gloss.gender = Genders.FEMININE
        gloss.examples.add("Se golpeó la frente")
        val def = Word.Definition("", Element("li"))
        def.glosses.add(gloss)
        word.definitions.add(def)

        val pageWords = listOf(newWord("frente", ref = "1"), word, newWord("frente", ref = "3"))
        val result = wordResultOf(word, pageWords)

        assertThat(result.selected).isEqualTo(1)
        assertThat(result.homonyms).hasSize(3)
        assertThat(result.homonyms[0])
            .isEqualTo(HomonymData("frente", "1", "Diccionario de la lengua española"))
        assertThat(result.word.mTitle).isEqualTo("frente")
        assertThat(result.word.definitions[0].glosses[0].definition)
            .isEqualTo("Parte anterior de la cabeza")
        assertThat(result.word.definitions[0].glosses[0].examples)
            .containsExactly("Se golpeó la frente")

        val parsed = gson.fromJson(gson.toJson(result), WordResult::class.java)
        assertThat(parsed).isEqualTo(result)
        assertThat(parsed.homonyms[1].ref).isEqualTo("2")
    }

    @Test
    fun homonymListFallsBackToWordXrefs() {
        val word = newWord("frente", ref = "7")
        assertThat(homonymListOf(word))
            .containsExactly(HomonymData("frente", "7", "Diccionario de la lengua española"))
    }

    @Test
    fun selectedFallsBackToZeroWhenWordNotOnPage() {
        val word = newWord("frente", ref = "99")
        val pageWords = listOf(newWord("frente", ref = "1"), newWord("frente", ref = "2"))
        val result = wordResultOf(word, pageWords)
        assertThat(result.selected).isEqualTo(0)
        assertThat(result.word.mTitle).isEqualTo("frente")
    }
}