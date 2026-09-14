package se.whitchurch.nordict.cli

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import org.junit.Test
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentProtocol
import se.whitchurch.nordict.AgentResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Desktop session tests for the agent repl: a [Repl] fed a stream of JSON
 * commands produces exactly one ordered [AgentResult] per command (the wire
 * contract), backed by the shared parsers against the `testdata/` fixtures
 * instead of the network.
 */
class ReplSessionTest {

    /** Fixture-backed fetcher for the headless driver, keyed by host+path. */
    private fun fixtureFetch(url: HttpUrl): String {
        return when {
            url.host == "dle.rae.es" && url.encodedPath == "/srv/keys" ->
                File("../testdata/dle-search.json").readText()
            url.host == "dle.rae.es" && url.encodedPath == "/frente" ->
                File("../testdata/dle/frente.html").readText()
            url.host == "www.rae.es" && url.encodedPath == "/diccionario-estudiante/srv/keys" ->
                File("../testdata/est-search.json").readText()
            url.host == "www.rae.es" && url.encodedPath.startsWith("/diccionario-estudiante/muerte") ->
                File("../testdata/est/muerte.html").readText()
            url.host == "www.rae.es" && url.encodedPath.endsWith("/frente") ->
                File("../testdata/est.html").readText()
            url.host == "www.rae.es" && url.encodedPath.endsWith("/cagar") ->
                File("../testdata/est/cagar.html").readText()
            else -> throw IllegalArgumentException("unexpected fetch: $url")
        }
    }

    private fun driver(): HeadlessAgentDriver =
        HeadlessAgentDriver(Main().dictionaries, ::fixtureFetch)

    private fun runScript(driver: HeadlessAgentDriver, vararg lines: String): List<AgentResult> {
        val input = ByteArrayInputStream(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        val exit = Repl(driver).run(input, PrintStream(out, true, "UTF-8"))
        assertThat(exit).isEqualTo(0)

        val outLines = out.toString("UTF-8").trim().split("\n")
        assertThat(outLines).hasSize(lines.size)
        return outLines.map { AgentProtocol.gson.fromJson(it, AgentResult::class.java) }
    }

    private fun ok(result: AgentResult) {
        assertThat(result.ok).isTrue()
    }

    @Test
    fun persistentSessionRunsMultiCommandScriptInOrder() {
        val r = runScript(
            driver(),
            """{"op":"state"}""",
            """{"op":"search","query":"frente"}""",
            """{"op":"open","query":"frente"}""",
            """{"op":"open","query":"frentero"}""",
            """{"op":"setDict","tag":"est"}""",
            """{"op":"open","query":"frente"}"""
        )

        ok(r[0])
        assertThat(r[0].state?.activity).isEqualTo("MainActivity")
        assertThat(r[0].state?.dict).isEqualTo("DLE")
        assertThat(r[0].state?.lang).isEqualTo("es")

        ok(r[1])
        assertThat(r[1].results).hasSize(2)
        assertThat(r[1].results!![0].mTitle).isEqualTo("frente")
        assertThat(r[1].results!![0].uri).isEqualTo("https://dle.rae.es/frente")
        assertThat(r[1].results!![1].mTitle).isEqualTo("frentero")
        assertThat(r[1].state?.query).isEqualTo("frente")
        assertThat(r[1].state?.activity).isEqualTo("MainActivity")

        ok(r[2])
        assertThat(r[2].word?.word?.mTitle).isEqualTo("frente")
        assertThat(r[2].word?.homonyms).hasSize(1)
        assertThat(r[2].word?.selected).isEqualTo(0)
        assertThat(r[2].state?.activity).isEqualTo("WordActivity")

        assertThat(r[3].ok).isFalse()
        assertThat(r[3].error).contains("no unique exact match for 'frentero'")

        ok(r[4])
        assertThat(r[4].state?.dict).isEqualTo("EST")
        assertThat(r[4].state?.word).isNull()

        ok(r[5])
        assertThat(r[5].word?.word?.mTitle).isEqualTo("frente")
        assertThat(r[5].word?.word?.definitions).hasSize(6)
        assertThat(r[5].word?.word?.idioms).hasSize(14)
    }

    @Test
    fun openUriAndNextPageWalkHomographs() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tag":"est"}""",
            """{"op":"openUri","uri":"https://www.rae.es/diccionario-estudiante/muerte"}""",
            """{"op":"nextPage"}""",
            """{"op":"nextPage"}""",
            """{"op":"nextPage"}""",
            """{"op":"nextPage"}""",
            """{"op":"openUri","uri":"https://www.rae.es/diccionario-estudiante/muerte?__ref=3"}""",
            """{"op":"openUri","uri":"https://www.rae.es/diccionario-estudiante/cagar"}"""
        )

        ok(r[0])
        ok(r[1])
        assertThat(r[1].word?.word?.mTitle).isEqualTo("muerte")
        assertThat(r[1].word?.homonyms?.map { it.mTitle })
            .containsExactly("muerte", "muerte natural", "muerte violenta").inOrder()
        assertThat(r[1].word?.homonyms?.map { it.ref }).containsExactly("1", "2", "3").inOrder()
        assertThat(r[1].word?.selected).isEqualTo(0)

        ok(r[2])
        assertThat(r[2].word?.word?.mTitle).isEqualTo("muerte natural")
        assertThat(r[2].word?.selected).isEqualTo(1)

        ok(r[3])
        assertThat(r[3].word?.word?.mTitle).isEqualTo("muerte violenta")
        assertThat(r[3].word?.selected).isEqualTo(2)

        ok(r[4])
        assertThat(r[4].word?.word?.mTitle).isEqualTo("muerte violenta")
        assertThat(r[4].word?.selected).isEqualTo(2)
        assertThat(r[4].message).contains("already at last entry (3 of 3)")

        ok(r[5])
        assertThat(r[5].word?.word?.mTitle).isEqualTo("muerte violenta")
        assertThat(r[5].word?.selected).isEqualTo(2)

        ok(r[6])
        assertThat(r[6].word?.word?.mTitle).isEqualTo("muerte violenta")
        assertThat(r[6].word?.selected).isEqualTo(2)

        ok(r[7])
        assertThat(r[7].word?.word?.mTitle).isEqualTo("cagar")
        assertThat(r[7].word?.word?.definitions).hasSize(4)
        assertThat(r[7].word?.word?.idioms).hasSize(3)
        assertThat(r[7].word?.word?.idioms!![0].plev).isEqualTo("malsonante")
    }

    @Test
    fun subEntryUrlResolvesByLastPathSegment() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tag":"est"}""",
            """{"op":"openUri","uri":"https://www.rae.es/diccionario-estudiante/muerte%20natural"}"""
        )

        ok(r[1])
        assertThat(r[1].word?.word?.mTitle).isEqualTo("muerte natural")
        assertThat(r[1].word?.selected).isEqualTo(1)
    }

    @Test
    fun setLangPicksFirstDictOfLanguage() {
        val r = runScript(
            driver(),
            """{"op":"setLang","lang":"ca"}""",
            """{"op":"state"}"""
        )

        ok(r[0])
        assertThat(r[0].state?.dict).isEqualTo("DIDAC")
        assertThat(r[0].state?.lang).isEqualTo("ca")
        assertThat(r[1].state?.dict).isEqualTo("DIDAC")
    }

    @Test
    fun swapLangReturnsToThePreviousLanguage() {
        val r = runScript(
            driver(),
            """{"op":"setLang","lang":"ca"}""",
            """{"op":"swapLang"}""",
            """{"op":"swapLang"}"""
        )

        ok(r[0])
        assertThat(r[0].state?.dict).isEqualTo("DIDAC")

        ok(r[1])
        assertThat(r[1].state?.dict).isEqualTo("DLE")
        assertThat(r[1].state?.lang).isEqualTo("es")

        ok(r[2])
        assertThat(r[2].state?.dict).isEqualTo("DIDAC")
        assertThat(r[2].state?.lang).isEqualTo("ca")
    }

    @Test
    fun swapLangWithoutHistoryErrors() {
        val r = runScript(
            driver(),
            """{"op":"swapLang"}"""
        )
        assertThat(r[0].ok).isFalse()
        assertThat(r[0].error).contains("no last language to swap to")
    }

    @Test
    fun unknownDictAndSecretOpsError() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tag":"bogus"}""",
            """{"op":"setLang","lang":"xx"}""",
            """{"op":"frobnicate"}""",
            """{"op":"nextPage"}"""
        )

        assertThat(r[0].ok).isFalse()
        assertThat(r[0].error).contains("unknown dictionary 'bogus'")
        assertThat(r[1].ok).isFalse()
        assertThat(r[1].error).contains("unknown language 'xx'")
        assertThat(r[2].ok).isFalse()
        assertThat(r[2].error).contains("unknown op 'frobnicate'")
        assertThat(r[3].ok).isFalse()
        assertThat(r[3].error).contains("no word loaded")
    }

    @Test
    fun malformedLineYieldsProtocolErrorAndSessionContinues() {
        val driver = driver()
        val input = ByteArrayInputStream(
            """{"op":""".plus("\n").plus("""{"op":"state"}""".plus("\n")).toByteArray(Charsets.UTF_8)
        )
        val out = ByteArrayOutputStream()
        val exit = Repl(driver).run(input, PrintStream(out, true, "UTF-8"))
        assertThat(exit).isEqualTo(1)

        val lines = out.toString("UTF-8").trim().split("\n")
        assertThat(lines).hasSize(2)
        val first = AgentProtocol.gson.fromJson(lines[0], AgentResult::class.java)
        assertThat(first.ok).isFalse()
        assertThat(first.error).contains("protocol error")
        val second = AgentProtocol.gson.fromJson(lines[1], AgentResult::class.java)
        assertThat(second.ok).isTrue()
    }

    @Test
    fun combinedSelectionSearchesAndOpensAcrossDictionaries() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tag":"DLE,EST"}""",
            """{"op":"state"}""",
            """{"op":"search","query":"frente"}""",
            """{"op":"open","query":"frente"}""",
            """{"op":"nextPage"}""",
            """{"op":"nextPage"}""",
            """{"op":"back"}"""
        )

        ok(r[0])
        assertThat(r[0].state?.dict).isEqualTo("DLE,EST")
        assertThat(r[0].state?.dicts).containsExactly("DLE", "EST").inOrder()
        assertThat(r[0].state?.lang).isEqualTo("es")

        ok(r[1])
        assertThat(r[1].state?.dict).isEqualTo("DLE,EST")

        ok(r[2])
        assertThat(r[2].results).isNotEmpty()
        assertThat(r[2].results!![0].mTitle).isEqualTo("frente")
        assertThat(r[2].results!![0].dicts).containsExactly("DLE", "EST").inOrder()
        assertThat(r[2].results!![0].uri).isEqualTo("https://dle.rae.es/frente")

        ok(r[3])
        assertThat(r[3].word?.word?.mTitle).isEqualTo("frente")
        assertThat(r[3].word?.homonyms?.map { it.ref })
            .containsExactly("DLE::1", "EST::1").inOrder()
        assertThat(r[3].word?.selected).isEqualTo(0)
        assertThat(r[3].state?.dict).isEqualTo("DLE,EST")

        // Combined nextPage walks every entry across both dictionaries in memory.
        ok(r[4])
        assertThat(r[4].word?.selected).isEqualTo(1)
        ok(r[5])
        assertThat(r[5].message).contains("already at last entry (2 of 2)")

        ok(r[6])
    }

    @Test
    fun setDictCollapsesCombinedSelectionBackToSingle() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tags":["DLE","EST"]}""",
            """{"op":"setDict","tag":"est"}""",
            """{"op":"open","query":"frente"}""",
            """{"op":"nextPage"}"""
        )

        ok(r[0])
        assertThat(r[0].state?.dicts).containsExactly("DLE", "EST").inOrder()

        ok(r[1])
        assertThat(r[1].state?.dict).isEqualTo("EST")
        assertThat(r[1].state?.dicts).isNull()

        // Now a single-dictionary selection: EST alone, plain page.
        ok(r[2])
        assertThat(r[2].word?.word?.mTitle).isEqualTo("frente")
        assertThat(r[2].word?.homonyms?.map { it.ref }).containsExactly("1").inOrder()
        assertThat(r[2].word?.selected).isEqualTo(0)

        ok(r[3])
        assertThat(r[3].message).contains("already at last entry (1 of 1)")
        assertThat(r[3].word?.selected).isEqualTo(0)
    }

    @Test
    fun combinedSelectionRejectsMixedSupportsAndLanguages() {
        val r = runScript(
            driver(),
            """{"op":"setDict","tags":["DLE","LINGPT"]}""",
            """{"op":"setDict","tags":["DLE","SO"]}""",
            """{"op":"setDict","tags":["DLE","COLFREN"]}""",
            """{"op":"setLang","lang":"es"}""",
            """{"op":"state"}"""
        )

        // LINGPT/COLFREN are not combining-capable; SO is the same-language but
        // legacy. Every mixed selection is rejected with a clear message.
        assertThat(r[0].ok).isFalse()
        assertThat(r[0].error).contains("does not support combining")
        assertThat(r[1].ok).isFalse()
        assertThat(r[1].error).contains("does not support combining")
        assertThat(r[2].ok).isFalse()
        assertThat(r[2].error).contains("does not support combining")

        // A failed selection leaves the previous selection intact.
        ok(r[3])
        assertThat(r[3].state?.dict).isEqualTo("DLE")
        ok(r[4])
        assertThat(r[4].state?.dict).isEqualTo("DLE")
    }

    @Test
    fun quitClosesTheSession() {
        val driver = driver()
        val input = ByteArrayInputStream(
            listOf(
                """{"op":"state"}""",
                """{"op":"quit"}""",
                """{"op":"state"}""" // must be ignored
            ).joinToString("\n").toByteArray(Charsets.UTF_8)
        )
        val out = ByteArrayOutputStream()
        val exit = Repl(driver).run(input, PrintStream(out, true, "UTF-8"))
        assertThat(exit).isEqualTo(0)

        val lines = out.toString("UTF-8").trim().split("\n")
        assertThat(lines).hasSize(2)
    }
}