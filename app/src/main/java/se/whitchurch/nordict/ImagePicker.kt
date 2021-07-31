package se.whitchurch.nordict

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONException
import java.util.*

internal const val GSTATIC_SERVER = "https://encrypted-tbn0.gstatic.com/"

class ImagePicker : AppCompatActivity() {
    private lateinit var ordboken: Ordboken
    private lateinit var webView: WebView
    private lateinit var searchText: EditText
    private var imagePickerJs: String? = null
    private val selected = ArrayList<String>()
    private var timer = Timer()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_picker)

        val intentWord = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val word = intentWord ?: "spritsa"

        ordboken = Ordboken.getInstance(this)

        findViewById<Button>(R.id.ok_button).setOnClickListener {
            ordboken.images = selected
            setResult(Activity.RESULT_OK)
            finish()
        }

        webView = findViewById(R.id.webView)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("Webview", consoleMessage!!.message())
                return true
            }
        }
        val settings = webView.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptEnabled = true

            // We replace the src urls in imagepicker.js::init(), so don't load
            // images twice.
            blockNetworkImage = true
        }

        webView.setInitialScale(100)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i("webview onPageFinished", url)
                if (url == GSTATIC_SERVER) {
                    settings.blockNetworkImage = false
                } else {
                    // onPageFinished gets called multiple times
                    timer.cancel()
                    timer = Timer()
                    timer.schedule(object : TimerTask() {
                        override fun run() {
                            runOnUiThread(Runnable {
                                view.loadUrl("javascript:" + getImagePickerJs() + "getPickerHtml();")
                            })
                        }
                    }, 1000)
                }
            }
        }

        webView.addJavascriptInterface(WcmJsObject(), "wcm")

        searchText = findViewById<EditText>(R.id.search_text).apply {
            setText(word)
        }

        val lang = ordboken.currentDictionary.lang

        findViewById<Button>(R.id.search_button).apply {
            setOnClickListener {
                val q = searchText.text.toString()
                webView.loadUrl("https://www.google.$lang/search?tbm=isch&q=" + Uri.encode(q))

            }
        }

        webView.loadUrl("https://www.google.$lang/search?tbm=isch&q=" + Uri.encode(word))
    }

    private inner class WcmJsObject {
        @JavascriptInterface
        fun pushSelected(json: String) {
            runOnUiThread(Runnable {
                val array: JSONArray
                try {
                    array = JSONArray(json)

                    selected.clear()

                    for (i in 0 until array.length()) {
                        selected.add(array.getString(i))
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            })
        }

        @JavascriptInterface
        fun pushPickerHtml(html: String) {
            runOnUiThread(Runnable {
                webView.loadDataWithBaseURL(
                    GSTATIC_SERVER,
                    html + "<script>" + getImagePickerJs() + "</script>",
                    "text/html", "UTF-8", null
                )
            })
        }
    }

    private fun getImagePickerJs(): String {
        imagePickerJs?.let { return it }

        val js = try {
            val am = assets
            val `is` = am.open("imagepicker.js")
            Utils.inputStreamToString(`is`)
        } catch (e: java.io.IOException) {
            "document.body.innerHtml='Error';"
        }

        imagePickerJs = js

        return js
    }
}
