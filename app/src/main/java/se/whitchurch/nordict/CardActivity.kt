package se.whitchurch.nordict

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import se.whitchurch.nordict.ui.theme.NordictTheme
import java.io.ByteArrayOutputStream
import kotlin.math.max

class CardActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var ordboken: Ordboken
    private var mWord: Word? by mutableStateOf(null)
    private lateinit var anki: AnkiClient
    private val imagesMap = mutableStateMapOf<String, List<String>>()
    private var currentCardId: String? = null
    private var mAudio: List<String> by mutableStateOf(emptyList())
    private var mDictImages: ArrayList<String> = ArrayList()
    // Merge selection, keyed by stable hide-keys (see Cards.hideKey) rather
    // than Definition object identity or per-card remember state: LazyColumn
    // disposes off-screen cards (scrolling a merged entry out of view drops
    // its remember), and proposals() mints fresh Definition copies for split
    // (Collins) definitions on every call, so neither would survive a scroll
    // or recomposition. The map value is the Definition instance at selection
    // time, used to build the merged Back/examples.
    private val selectedDefinitions = mutableStateMapOf<Any, Word.Definition>()
    private var saveDeckName = true
    private var mLeftCards = 0
    private var deckName: String by mutableStateOf("")
    // Hide-keys (see Cards.hideKey) of the proposals whose cards were created
    // successfully. Keyed on the stable underlying model objects, not the
    // proposal wrappers: proposals() mints fresh Definition copies for split
    // (Collins) definitions on every call, so object identity would never
    // match across recompositions and created entries would stay visible.
    private var hiddenCards by mutableStateOf(setOf<Any>())
    private var preview: Cards.CardPreview? by mutableStateOf(null)
    private var previewTitle: String by mutableStateOf("")

    private val cardImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentCardId?.let { cardId ->
                    val existing = imagesMap[cardId].orEmpty().toMutableList()
                    existing.addAll(ordboken.images)
                    imagesMap[cardId] = existing
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(
                this,
                AddContentApi.READ_WRITE_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    AddContentApi.READ_WRITE_PERMISSION
                )
            ) {
                // Show an explanation to the user asynchronously -- don't block
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(AddContentApi.READ_WRITE_PERMISSION),
                    1
                )
            }
        }

        anki = AnkiClient(debugAnkiApi ?: AnkiDroidApi(this))
        debugAnkiApi = null

        deckName =
            getPreferences(Context.MODE_PRIVATE)?.getString("deckName", "Nordict") ?: "Nordict"

        intent?.getStringExtra("deckName")?.let {
            saveDeckName = false
            deckName = it
        }

        title = deckName

        ordboken = Ordboken.getInstance(this)
        ordboken.images = ArrayList()
        val word = ordboken.currentWord ?: run {
            finish()
            return
        }

        setContent {
            NordictTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CardScreen()
                }
            }
        }

        lifecycleScope.launch {
            val audio = withContext(Dispatchers.IO) { urlsToData(word.audio) }
            val images = withContext(Dispatchers.IO) { urlsToData(word.images) }
            mAudio = audio
            mDictImages = images
            mWord = word
            mLeftCards = Cards.proposals(word).size
        }
    }

    @Composable
    fun CardScreen() {
        val word = mWord
        val deck = deckName
        val audio = mAudio

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(deck, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                }
            }

            OutlinedTextField(
                value = deck,
                onValueChange = { deckName = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                singleLine = true,
                label = { Text("Deck") }
            )

            if (word == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                return
            }

            val proposalCards = Cards.visibleProposals(word, hiddenCards)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp)
            ) {
                // Key by the stable proposal id (see CardProposal.id), not by
                // position: without a key the per-card `remember` state
                // (extra examples, audio index) is reused by position, so
                // after created entries are removed the entry sliding into
                // their slot inherits it. (Merge selection lives in
                // selectedDefinitions keyed by stable hide-keys, so it
                // survives both slot reuse and scroll-out disposal.)
                // The key must be Bundle-storable (String): the hide-key
                // objects (Word.Gloss/Definition/Idiom) crash LazyColumn's
                // saveable-state provider on device.
                items(proposalCards, key = { it.id }) { proposal ->
                    when (proposal) {
                        is CardProposal.Definition -> DefinitionCard(
                            word = word,
                            proposal = proposal,
                            audio = audio,
                            onCreate = { created ->
                                val createdKeys = created.map { Cards.hideKey(it) }.toSet() +
                                    Cards.hideKey(proposal.definition)
                                hiddenCards = hiddenCards + createdKeys
                                createdKeys.forEach { selectedDefinitions.remove(it) }
                            }
                        )
                        is CardProposal.Idiom -> IdiomCard(
                            word = word,
                            proposal = proposal,
                            onCreate = { hiddenCards = hiddenCards + Cards.hideKey(proposal.idiom) }
                        )
                    }
                }
            }

            preview?.let { cardPreview ->
                CardPreviewDialog(
                    title = previewTitle,
                    preview = cardPreview,
                    onDismiss = { preview = null }
                )
            }
        }
    }

    /**
     * The card preview dialog: renders the exact Front and Back the card
     * would show in Anki (see [Cards.preview]) in a WebView, so card layout
     * can be debugged without leaving the app.
     */
    @Composable
    fun CardPreviewDialog(
        title: String,
        preview: Cards.CardPreview,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            },
            title = { Text(title) },
            text = {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            loadDataWithBaseURL(null, preview.html, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                )
            }
        )
    }

    @Composable
    fun DefinitionCard(
        word: Word,
        proposal: CardProposal.Definition,
        audio: List<String>,
        onCreate: (List<Word.Definition>) -> Unit
    ) {
        val definition = proposal.definition
        val title = proposal.title
        val extraExamples = remember { mutableStateListOf<String>() }
        val audioIdx = remember { mutableIntStateOf(0) }
        // Derived from the hoisted merge set (keyed by stable hide-key), not
        // per-card remember: remember is dropped when the card scrolls out of
        // view, which used to silently unmerge the entry on scroll-back.
        val mergeKey = Cards.hideKey(definition)
        val isMerged = selectedDefinitions.containsKey(mergeKey)
        val images = imagesMap[proposal.id].orEmpty()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            shape = RoundedCornerShape(2.dp),
            colors = if (isMerged) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            } else {
                CardDefaults.cardColors()
            }
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "$title: ${Cards.definitionText(definition)}",
                    fontWeight = FontWeight.Bold
                )
                val examplesText = definition.glosses.flatMap { it.examples }
                    .ifEmpty { definition.examples } + extraExamples
                if (examplesText.isNotEmpty()) {
                    Text(
                        text = "• " + examplesText.joinToString(separator = "\n• ") { Cards.plainText(it) },
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 6.dp)
                    ) {
                        images.forEach { dataUrl ->
                            val bmp = remember(dataUrl) { decodeImageDataUrl(dataUrl) }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(72.dp)
                                )
                            }
                        }
                    }
                }

                val toggleMerge: (Boolean) -> Unit = { checked ->
                    if (checked) {
                        selectedDefinitions[mergeKey] = definition
                    } else {
                        selectedDefinitions.remove(mergeKey)
                    }
                }

                if (audio.size > 1) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AudioIndexPicker(
                            audio = audio,
                            audioIdx = audioIdx
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        Intent(this@CardActivity, ImagePicker::class.java).also {
                            it.putExtra(Intent.EXTRA_TEXT, title)
                            it.putExtra("dictionaryImages", mDictImages)
                            currentCardId = proposal.id
                            ordboken.images = ArrayList()
                            cardImageLauncher.launch(it)
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_add_image), contentDescription = "Add image")
                    }

                    IconButton(onClick = {
                        Intent(this@CardActivity, CameraActivity::class.java).also {
                            it.putExtra(Intent.EXTRA_TEXT, title)
                            currentCardId = proposal.id
                            ordboken.images = ArrayList()
                            cardImageLauncher.launch(it)
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_add_camera), contentDescription = "Take photo")
                    }

                    IconButton(onClick = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val item = clipboard.primaryClip?.getItemAt(0)
                        val pasteData = item?.text

                        if (pasteData != null) {
                            extraExamples.add(pasteData.toString())
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_add_clipboard), contentDescription = "Paste from clipboard")
                    }

                    IconButton(onClick = {
                        imagesMap[proposal.id] = emptyList()
                        extraExamples.clear()
                    }) {
                        Icon(painterResource(R.drawable.ic_clear), contentDescription = "Clear")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    // No text label: the switch alone fits on the button row
                    // next to Preview/Create. The content description names it
                    // for accessibility. Checked state is derived from the
                    // hoisted merge set, so it survives scrolling out of view.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = isMerged,
                            onCheckedChange = toggleMerge,
                            modifier = Modifier.semantics {
                                contentDescription = "Merge"
                            }
                        )
                    }
                    OutlinedButton(onClick = {
                        val effectiveDefs =
                            if (selectedDefinitions.isEmpty()) listOf(definition)
                            else selectedDefinitions.values.toList()
                        val cardWord = Cards.buildCardWord(word, effectiveDefs, emptyList())
                        renderCardBack(cardWord) { back ->
                            if (back == null) {
                                android.widget.Toast.makeText(
                                    this@CardActivity, "Fail",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@renderCardBack
                            }
                            previewTitle = title
                            preview = Cards.preview(
                                back,
                                Cards.examples(word, effectiveDefs, extraExamples),
                                imagesMap[proposal.id].orEmpty(),
                                audio.elementAtOrElse(audioIdx.intValue) { _ -> "" }
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Preview")
                    }

                    Button(onClick = {
                        val effectiveDefs =
                            if (selectedDefinitions.isEmpty()) listOf(definition)
                            else selectedDefinitions.values.toList()
                        val imagesForCard = imagesMap[proposal.id].orEmpty()
                        val cardExamples = Cards.examples(word, effectiveDefs, extraExamples)
                        val cardAudio = audio.elementAtOrElse(audioIdx.intValue) { _ -> "" }
                        renderCardBack(
                            Cards.buildCardWord(word, effectiveDefs, emptyList())
                        ) { back ->
                            if (back == null) {
                                android.widget.Toast.makeText(
                                    this@CardActivity, "Fail",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@renderCardBack
                            }
                            val noteId = createCard(back, cardExamples, imagesForCard, cardAudio)
                            if (noteId != null) onCreate(effectiveDefs)
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_done),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Create")
                    }
                }
            }
        }
    }

    @Composable
    fun AudioIndexPicker(
        audio: List<String>,
        audioIdx: MutableIntState
    ) {
        var expanded by remember { mutableStateOf(false) }

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text((audioIdx.intValue + 1).toString())
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (1..audio.size).forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n.toString()) },
                        onClick = {
                            audioIdx.intValue = n - 1
                            expanded = false
                            mWord?.audio?.let { audioUrls ->
                                playAudio(audioUrls[n - 1])
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Renders the Back for a synthetic card word through `renderer.js` and
     * delivers the self-contained static HTML (or null on capture failure).
     */
    private fun renderCardBack(cardWord: Word, onDone: (String?) -> Unit) {
        CardBackRenderer.render(this, cardWord, onDone)
    }

    @Composable
    fun IdiomCard(
        word: Word,
        proposal: CardProposal.Idiom,
        onCreate: () -> Unit
    ) {
        val idiom = proposal.idiom
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            shape = RoundedCornerShape(2.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = "${idiom.idiom}: ${Cards.plainText(idiom.definition)}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (idiom.examples.isNotEmpty()) {
                    Text(
                        text = "• " + idiom.examples.joinToString(separator = "\n• ") { Cards.plainText(it) },
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = {
                        renderCardBack(
                            Cards.buildCardWord(word, emptyList(), listOf(idiom))
                        ) { back ->
                            if (back == null) {
                                android.widget.Toast.makeText(
                                    this@CardActivity, "Fail",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@renderCardBack
                            }
                            previewTitle = proposal.title
                            preview = Cards.preview(
                                back,
                                Cards.idiomExamples(idiom),
                                emptyList(),
                                ""
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Preview")
                    }
                    Button(onClick = {
                        renderCardBack(
                            Cards.buildCardWord(word, emptyList(), listOf(idiom))
                        ) { back ->
                            if (back == null) {
                                android.widget.Toast.makeText(
                                    this@CardActivity, "Fail",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                return@renderCardBack
                            }
                            val noteId = createCard(
                                back,
                                Cards.idiomExamples(idiom),
                                ArrayList(),
                                ""
                            )
                            if (noteId != null) onCreate()
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_done),
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Create")
                    }
                }
            }
        }
    }

    private fun decodeImageDataUrl(dataUrl: String): android.graphics.Bitmap? {
        return try {
            val base64 = dataUrl.split(",")[1]
            val decoded = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun createCard(
        text: String,
        examples: List<String>,
        images: List<String>,
        audio: String
    ): Long? {
        val deck = deckName
        val id = anki.createCard(deck, text, examples, images, audio, ::downscaleImageDataUrl)

        android.widget.Toast.makeText(
            this, if (id == null) "Fail" else "Card added to $deck",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        if (id != null) {
            mLeftCards -= 1
        }

        if (saveDeckName) {
            getPreferences(Context.MODE_PRIVATE)?.let { pref ->
                with(pref.edit()) {
                    putString("deckName", deck)
                    commit()
                }
            }
        }

        if (mLeftCards <= 0) {
            finish()
        }

        return id
    }

    /**
     * True once the async word/audio load task has finished populating the
     * card screen ([mWord] is set, so proposals are available). Used by the
     * agent driver to wait out the load before creating a card.
     */
    fun isCardReady(): Boolean = mWord != null

    /**
     * The card screen's currently visible (not yet created) proposals, in page
     * order. Used by the agent driver to verify that a created card's entry
     * left the card view.
     */
    fun visibleProposals(): List<CardProposal> {
        val word = mWord ?: return emptyList()
        return Cards.visibleProposals(word, hiddenCards)
    }

    /**
     * Creates the card for visible proposal [index] (default the first
     * remaining entry) through the same pipeline the Create button drives,
     * delivering the new Anki note id (null on failure) to [cb]. On success
     * the entry is hidden from the card view, exactly like the Create button.
     * Asynchronous: the Back renders through the hidden WebView first. Used by
     * the agent REPL and tests.
     */
    fun agentCreateCard(index: Int?, cb: (Long?) -> Unit) {
        val word = mWord ?: return cb(null)
        val proposal = visibleProposals().getOrNull(index ?: 0) ?: return cb(null)
        val audio = mAudio.elementAtOrElse(0) { _ -> "" }
        val (cardWord, examples, cardAudio) = when (proposal) {
            is CardProposal.Definition -> Triple(
                Cards.buildCardWord(word, listOf(proposal.definition), emptyList()),
                Cards.examples(word, listOf(proposal.definition), emptyList()),
                audio
            )
            is CardProposal.Idiom -> Triple(
                Cards.buildCardWord(word, emptyList(), listOf(proposal.idiom)),
                Cards.idiomExamples(proposal.idiom),
                ""
            )
        }
        renderCardBack(cardWord) { back ->
            if (back == null) return@renderCardBack cb(null)
            val noteId = createCard(back, examples, emptyList(), cardAudio)
            if (noteId != null) {
                hiddenCards = hiddenCards + Cards.proposalHideKey(proposal)
            }
            cb(noteId)
        }
    }

    /**
     * Builds the front/back preview for proposal [index] (default the first
     * definition) through the same pipeline the Preview button drives, without
     * touching Anki, delivering it to [cb] (null on failure). Used by the
     * agent REPL and tests.
     */
    fun agentPreviewCard(index: Int?, cb: (Cards.CardPreview?) -> Unit) {
        val word = mWord ?: return cb(null)
        val proposal = Cards.proposals(word).getOrNull(index ?: 0) ?: return cb(null)
        val audio = mAudio.elementAtOrElse(0) { _ -> "" }
        val (cardWord, examples, cardAudio) = when (proposal) {
            is CardProposal.Definition -> Triple(
                Cards.buildCardWord(word, listOf(proposal.definition), emptyList()),
                Cards.examples(word, listOf(proposal.definition), emptyList()),
                audio
            )
            is CardProposal.Idiom -> Triple(
                Cards.buildCardWord(word, emptyList(), listOf(proposal.idiom)),
                Cards.idiomExamples(proposal.idiom),
                ""
            )
        }
        renderCardBack(cardWord) { back ->
            cb(back?.let { Cards.preview(it, examples, emptyList(), cardAudio) })
        }
    }

    // The clip a WebView-bytes fallback already recovered in this card
    // session: its replay must toast instead of refetching in a loop.
    private var audioFallbackDoneForUrl: String? = null

    // Cache-file path -> clip URL, so a failed file replay still resolves to
    // its source URL and refetches instead of sticking on a corrupt file.
    private val fallbackFileToUrl = HashMap<String, String>()

    private fun playAudio(url: String) {
        // A challenged-host clip fetched earlier replays straight from its
        // (shared) cache file: no 403, no slow fallback fetch again.
        val file = audioFallbackFile(cacheDir, url)
        if (isChallengedAudioHost(url) && isUsableFallbackFile(file)) {
            fallbackFileToUrl[file.absolutePath] = url
            playAudioSource(file.absolutePath, isFallbackFile = true)
        } else {
            playAudioSource(url, isFallbackFile = false)
        }
    }

    // Plays a clip URL, or a cache file a previous fallback wrote. A failed
    // challenged-host clip gets one silent recovery per URL — bytes through
    // the hidden challenge WebView (the Chromium stack the site's own player
    // uses), replayed from a cache file — because only the toast remains
    // otherwise.
    private fun playAudioSource(url: String, isFallbackFile: Boolean) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )

        try {
            if (isFallbackFile) {
                mediaPlayer.setDataSource(url)
            } else {
                // Challenged-host clips (Infopedia TTS, Collins sounds) need
                // the synced cf_clearance cookie or the request 403s, and
                // Infopedia's TTS endpoint additionally needs the word page as
                // Referer — see audioRequestHeaders.
                val referer = mWord?.uri?.toString()
                mediaPlayer.setDataSource(applicationContext, Uri.parse(url), audioRequestHeaders(url, referer))
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(applicationContext, R.string.error_audio, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }

        mediaPlayer.setOnPreparedListener { mp ->
            mp.start()
        }

        mediaPlayer.setOnErrorListener { mp, what, extra ->
            val httpUrl = fallbackFileToUrl[url] ?: url.takeIf { it.startsWith("http") }
            if (httpUrl != null && audioFallbackDoneForUrl != httpUrl &&
                isChallengedAudioHost(httpUrl)
            ) {
                audioFallbackDoneForUrl = httpUrl
                // A stale cache file resolves back to its clip and refetches.
                fallbackFileToUrl[url]?.let { audioFallbackFile(cacheDir, it).delete() }
                fetchClipBytesAndPlay(httpUrl)
            } else {
                android.widget.Toast.makeText(applicationContext, R.string.error_audio, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
            false
        }

        mediaPlayer.prepareAsync()
    }

    private fun fetchClipBytesAndPlay(url: String) {
        val referer = mWord?.uri?.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            // Shared clip fetcher (see CollinsFetch.kt): the same cache and
            // silent WebView-bytes recovery the word view uses, so a clip the
            // word view already solved replays here with no second challenge.
            if (fetchAudioBytes(url, referer, cacheDir, ordboken.client) == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext, R.string.error_audio,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            val file = audioFallbackFile(cacheDir, url)
            if (!isUsableFallbackFile(file)) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext, R.string.error_audio,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                fallbackFileToUrl[file.absolutePath] = url
                playAudioSource(file.absolutePath, isFallbackFile = true)
            }
        }
    }

    /**
     * Downloads [urls] (word audio or images) as `data:` URLs for Anki card
     * fields. Challenged-host clips (Collins sounds, Infopedia TTS) go
     * through the shared clip fetcher ([fetchAudioBytes]): shared cache hit,
     * clearance-carrying direct fetch, then silent WebView-bytes recovery —
     * reusing a challenge the word view already solved (shared cookies/cache)
     * and never prompting the user again (no `tapHost` here; total failure
     * just embeds silence for that clip). Must be called off the main thread
     * (the `onCreate` loader already is).
     */
    fun urlsToData(urls: ArrayList<String>, referer: String? = mWord?.uri?.toString()): ArrayList<String> {
        val data = ArrayList<String>()

        urls.forEach { url ->
            if (isChallengedAudioHost(url)) {
                val bytes = fetchAudioBytes(url, referer, cacheDir, ordboken.client)
                    ?: return@forEach
                // Challenged-host media are pronunciation clips (mp3 bytes).
                val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                data.add("data:audio/mpeg;base64,$base64")
                return@forEach
            }
            try {
                ordboken.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.i("NordictAudio", "media fetch $url -> ${response.code}")
                        return@forEach
                    }
                    val body = response.body ?: return@forEach
                    val type = if (url.endsWith(".mp3")) {
                        "audio/mpeg"
                    } else {
                        body.contentType()
                    }
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) return@forEach

                    val base64 = Base64.encodeToString(bytes, Base64.DEFAULT)

                    data.add("data:$type;base64,$base64")
                }
            } catch (e: Exception) {
                android.util.Log.i("NordictAudio", "media fetch failed for $url: ${e.message}")
            }
        }

        return data
    }

    companion object {
        // Test/debug seam: a fallback AnkiApi consumed (once) on the next
        // `onCreate` so Robolectric tests and the agent can drive card
        // creation against a fake instead of the AnkiDroid content provider.
        @Volatile
        var debugAnkiApi: AnkiApi? = null
    }
}

/**
 * Downscale floor for [downscaleImageDataUrl]: images at or below this size
 * on their longest side are returned as-is (null), since shrinking further
 * would only hurt legibility for negligible byte savings.
 */
internal const val MIN_IMAGE_DIMENSION = 512

/**
 * One shrink step for the card image fitter ([Cards.fitFields], wired in
 * through `CardActivity.createCard`): decodes a `data:image/...;base64,...`
 * URL, halves its longest side (down to [MIN_IMAGE_DIMENSION] px) and
 * re-encodes it as JPEG (the picker's canvas PNGs and the camera crop's PNG
 * output are what blow the Binder budget in the first place).
 *
 * Returns null for non-image data URLs, undecodable payloads, or images
 * already at the floor — the fitter then keeps or drops them.
 */
internal fun downscaleImageDataUrl(dataUrl: String): String? {
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:image/") || comma < 0) return null
    val bytes = try {
        Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        return null
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    try {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MIN_IMAGE_DIMENSION || longest <= 0) return null
        val targetLongest = max(longest / 2, MIN_IMAGE_DIMENSION)
        val scale = targetLongest.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)) return null
            return "data:image/jpeg;base64," +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}