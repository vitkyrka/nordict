package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Assume
import java.io.File
import java.nio.charset.Charset

/**
 * Shared golden-file helper for the parser tests.
 *
 * A golden test compares the parsed output against a committed JSON fixture in
 * `testdata/`. In a normal run the fixture is compared (so unintended parser
 * drift fails the test); to regenerate the fixtures after an intentional parser
 * change run with `UPDATE_GOLDEN=1` set, e.g.
 *
 * ```
 * UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests 'se.whitchurch.nordict.CollinsParserTest'
 * ```
 *
 * and review the resulting git diff. A missing fixture is auto-generated the
 * first time so a fresh checkout can bootstrap itself.
 *
 * `testdata/` is a private fixtures repo (copyrighted dictionary pages) and is
 * gitignored here, so a public checkout has no fixtures at all. Every read of
 * a fixture must go through [fixture]/[fixtureText] (and goldens through
 * [assertGolden]), which skip the test via JUnit `Assume` when the file is
 * absent instead of failing with `FileNotFoundException`. A missing `testdata/`
 * dir therefore reports tests as skipped and the build stays green.
 */
object Goldens {
    private const val UPDATE_GOLDEN = "UPDATE_GOLDEN"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun shouldRegenerate(path: File): Boolean =
        System.getenv(UPDATE_GOLDEN) == "1" || !path.exists()

    /**
     * Returns the fixture file at [path], skipping the test (JUnit `Assume`)
     * when the private `testdata/` checkout is absent.
     */
    fun fixture(path: String): File {
        val file = File(path)
        Assume.assumeTrue(
            "testdata missing ($path): private fixtures repo not checked out — skipping",
            file.exists()
        )
        return file
    }

    /** Reads the fixture at [path] as text, skipping the test when absent. */
    fun fixtureText(path: String, charset: Charset = Charsets.UTF_8): String =
        fixture(path).readText(charset)

    /**
     * Requires the `testdata/` dir itself. Use in `@Before`/dispatcher setups
     * where fixtures are read lazily (an `Assume` thrown on a MockWebServer
     * dispatcher thread would not mark the test skipped).
     */
    fun requireTestdata(path: String = "../testdata"): File {
        val dir = File(path)
        Assume.assumeTrue(
            "testdata missing ($path): private fixtures repo not checked out — skipping",
            dir.isDirectory
        )
        return dir
    }

    /**
     * Serializes [actual] with the shared pretty Gson, regenerates [jsonPath]
     * when UPDATE_GOLDEN=1 (or the file is missing), then asserts it equals the
     * committed fixture read back as [arrayClass] (e.g. `Array<WordData>::class.java`).
     *
     * Skips the test when the `testdata/` checkout is absent instead of
     * failing or writing fixtures into the public repo.
     */
    fun <T> assertGolden(actual: List<T>, jsonPath: String, arrayClass: Class<Array<T>>) {
        requireTestdata()
        val path = File(jsonPath)
        if (!path.exists() && System.getenv(UPDATE_GOLDEN) != "1") {
            Assume.assumeTrue(
                "missing golden ($jsonPath): private fixtures repo not checked out — skipping",
                false
            )
        }
        val json = gson.toJson(actual)
        if (shouldRegenerate(path)) {
            path.writeText(json)
        }

        val expected = gson.fromJson(path.readText(), arrayClass)
        assertThat(actual).isEqualTo(expected.toList())
    }
}