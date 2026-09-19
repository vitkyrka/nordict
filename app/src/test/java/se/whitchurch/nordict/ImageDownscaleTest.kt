package se.whitchurch.nordict

import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Unit tests for [downscaleImageDataUrl], the Bitmap step behind the
 * card-image fitter ([Cards.fitFields]): oversized data URLs are shrunk
 * before the AnkiDroid insert so the note stays under the Binder ~1MB
 * transaction buffer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageDownscaleTest {

    private fun pngDataUrl(width: Int, height: Int): String {
        // Random pixels: solid fills compress to almost nothing as PNG,
        // which would make the JPEG re-encode look bigger and defeat the
        // shrink assertion. Noise is incompressible like a real photo.
        val random = java.util.Random(42)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, random.nextInt())
            }
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun decodeDimensions(dataUrl: String): Pair<Int, Int> {
        val bytes = android.util.Base64.decode(
            dataUrl.substringAfter(","),
            android.util.Base64.DEFAULT
        )
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun nonImageUrl_returnsNull() {
        assertThat(downscaleImageDataUrl("https://example.com/photo.jpg")).isNull()
    }

    @Test
    fun malformedBase64_returnsNull() {
        assertThat(downscaleImageDataUrl("data:image/png;base64,!!!not-base64!!!")).isNull()
    }

    @Test
    fun nonImageBytes_returnsNull() {
        val text = "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString("just some text".toByteArray())
        assertThat(downscaleImageDataUrl(text)).isNull()
    }

    @Test
    fun smallImage_returnsNull() {
        assertThat(downscaleImageDataUrl(pngDataUrl(4, 4))).isNull()
    }

    @Test
    fun largeImage_halvesLongestSideAndReencodesAsJpeg() {
        val big = pngDataUrl(1024, 768)

        val small = downscaleImageDataUrl(big)

        assertThat(small).isNotNull()
        assertThat(small!!.startsWith("data:image/jpeg;base64,")).isTrue()
        assertThat(small.length).isLessThan(big.length)
        assertThat(decodeDimensions(small)).isEqualTo(512 to 384)
    }

    @Test
    fun repeatedDownscaling_convergesAtTheFloor() {
        var current = pngDataUrl(2048, 1536)
        val seen = mutableListOf(current)
        while (true) {
            val next = downscaleImageDataUrl(current) ?: break
            seen.add(next)
            current = next
        }

        // 2048 -> 1024 -> 512, then the floor refuses to shrink further.
        assertThat(seen).hasSize(3)
        assertThat(decodeDimensions(seen.last())).isEqualTo(512 to 384)
    }
}
