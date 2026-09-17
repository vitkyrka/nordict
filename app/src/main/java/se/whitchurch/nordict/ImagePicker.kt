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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ImagePickerScreen(
        initialWord: String,
        dictImages: ArrayList<String>,
        onOk: () -> Unit
    ) {
        var query by remember { mutableStateOf(initialWord) }
        val lang = ordboken.currentDictionary.lang
        val arg = dictImages.joinToString(",") { "\"$it\"" }

        fun submitSearch() {
            ImagePickerWebViewHolder.current?.loadUrl(
                "https://www.google.$lang/search?tbm=isch&q=" + Uri.encode(query)
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose image") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        FilledTonalButton(onClick = onOk) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Save")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search images") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                            IconButton(onClick = { submitSearch() }) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search images"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                ImagePickerWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    lang = lang,
                    arg = arg,
                    initialWord = initialWord
                )
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
            runOnUiThread {
                // Render the picker at the gstatic origin: the thumbnails were
                // extracted as host-relative /images?q=tbn:... URLs, so with the
                // GSTATIC_SERVER base they resolve back onto google's thumbnail
                // server and stay same-origin, keeping toDataURL()'s canvas from
                // tainting. The navigation to GSTATIC_SERVER also flips
                // blockNetworkImage off in onPageFinished.
                ImagePickerWebViewHolder.current?.loadDataWithBaseURL(
                    GSTATIC_SERVER,
                    html + "<script>" + getImagePickerJs() + "</script>",
                    "text/html", "UTF-8", null
                )
            }
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