package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

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
 */
object Goldens {
    private const val UPDATE_GOLDEN = "UPDATE_GOLDEN"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun shouldRegenerate(path: File): Boolean =
        System.getenv(UPDATE_GOLDEN) == "1" || !path.exists()

    /**
     * Serializes [actual] with the shared pretty Gson, regenerates [jsonPath]
     * when UPDATE_GOLDEN=1 (or the file is missing), then asserts it equals the
     * committed fixture read back as [arrayClass] (e.g. `Array<WordData>::class.java`).
     */
    fun <T> assertGolden(actual: List<T>, jsonPath: String, arrayClass: Class<Array<T>>) {
        val path = File(jsonPath)
        val json = gson.toJson(actual)
        if (shouldRegenerate(path)) {
            path.writeText(json)
        }

        val expected = gson.fromJson(path.readText(), arrayClass)
        assertThat(actual).isEqualTo(expected.toList())
    }
}