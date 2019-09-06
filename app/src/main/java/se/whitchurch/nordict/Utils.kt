package se.whitchurch.nordict

import java.io.IOException
import java.io.InputStream
import java.util.*

object Utils {
    @Throws(IOException::class)
    fun inputStreamToString(input: InputStream): String {
        val scanner = Scanner(input).useDelimiter("\\A")
        val str = if (scanner.hasNext()) scanner.next() else ""

        input.close()

        return str
    }
}
