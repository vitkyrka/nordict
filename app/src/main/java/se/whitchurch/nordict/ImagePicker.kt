package se.whitchurch.nordict

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONException
import java.util.*
import se.whitchurch.nordict.ui.theme.NordictTheme

internal const val GSTATIC_SERVER = "https://encrypted-tbn0.gstatic.com/"

class ImagePicker : AppCompatActivity() {
    private lateinit var ordboken: Ordboken
    private var selected = ArrayList<String>()
    internal var timer: Timer = Timer()
    private var imagePickerJs: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ordboken = Ordboken.getInstance(this)

        val initialWord = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: "spritsa"
        val dictImages = intent?.getStringArrayListExtra("dictionaryImages") ?: arrayListOf()

        setContent {
            NordictTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImagePickerScreen(
                        initialWord = initialWord,
                        dictImages = dictImages,
                        onOk = {
                            ordboken.images = selected
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    )
                }
            }
        }
    }

    @Composable
    fun ImagePickerScreen(
        initialWord: String,
        dictImages: ArrayList<String>,
        onOk: () -> Unit
    ) {
        var query by remember { mutableStateOf(initialWord) }
        val lang = ordboken.currentDictionary.lang
        val arg = dictImages.joinToString(",") { "\"$it\"" }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    ImagePickerWebViewHolder.current?.loadUrl(
                        "https://www.google.$lang/search?tbm=isch&q=" + Uri.encode(query)
                    )
                }) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Search")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ImagePickerWebView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                lang = lang,
                arg = arg,
                initialWord = initialWord
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onOk, modifier = Modifier.align(Alignment.End)) {
                Text("OK")
            }
        }
    }

    internal inner class WcmJsObject {
        @JavascriptInterface
        fun pushSelected(json: String) {
            runOnUiThread {
                try {
                    val array = JSONArray(json)
                    selected.clear()
                    for (i in 0 until array.length()) {
                        selected.add(array.getString(i))
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }

        @JavascriptInterface
        fun pushPickerHtml(html: String) {
            // Not needed - handled by onPageFinished
        }
    }

    internal fun getImagePickerJs(): String {
        imagePickerJs?.let { return it }
        val js = try {
            val input = assets.open("imagepicker.js")
            Utils.inputStreamToString(input)
        } catch (e: java.io.IOException) {
            "document.body.innerHtml='Error';"
        }
        imagePickerJs = js
        return js
    }
}

object ImagePickerWebViewHolder {
    var current: WebView? = null
}

@Composable
fun ImagePickerWebView(
    modifier: Modifier = Modifier,
    lang: String,
    arg: String,
    initialWord: String
) {
    val context = LocalContext.current
    val activity = context as ImagePicker

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                ImagePickerWebViewHolder.current = this

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d("Webview", consoleMessage!!.message())
                        return true
                    }
                }
                settings.apply {
                    builtInZoomControls = true
                    displayZoomControls = false
                    javaScriptEnabled = true
                    blockNetworkImage = true
                }
                setInitialScale(100)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        Log.i("webview onPageFinished", url)
                        if (url == GSTATIC_SERVER) {
                            settings.blockNetworkImage = false
                        } else {
                            activity.timer.cancel()
                            activity.timer = Timer()
                            activity.timer.schedule(object : TimerTask() {
                                override fun run() {
                                    activity.runOnUiThread {
                                        view.loadUrl("javascript:" + activity.getImagePickerJs() + "getPickerHtml($arg);")
                                    }
                                }
                            }, 1000)
                        }
                    }
                }
                addJavascriptInterface(activity.WcmJsObject(), "wcm")
                loadUrl("https://www.google.$lang/search?tbm=isch&q=" + Uri.encode(initialWord))
            }
        }
    )
}