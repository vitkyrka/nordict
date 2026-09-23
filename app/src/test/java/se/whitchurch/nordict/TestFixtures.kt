package se.whitchurch.nordict

import org.junit.Assume
import java.io.File
import java.nio.charset.Charset

/** Mirrors core's Goldens fixture helpers: skip (Assume) when testdata is absent. */
object TestFixtures {
    fun fixture(path: String): File {
        val file = File(path)
        Assume.assumeTrue(
            "testdata missing ($path): private fixtures repo not checked out — skipping",
            file.exists()
        )
        return file
    }
    fun fixtureText(path: String, charset: Charset = Charsets.UTF_8): String =
        fixture(path).readText(charset)
    fun requireTestdata(path: String = "../testdata"): File {
        val dir = File(path)
        Assume.assumeTrue(
            "testdata missing ($path): private fixtures repo not checked out — skipping",
            dir.isDirectory
        )
        return dir
    }
}
