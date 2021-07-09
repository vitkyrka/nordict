package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi
import kotlinx.android.synthetic.main.activity_card.*
import okhttp3.Request


class CardActivity : AppCompatActivity() {
    private lateinit var ordboken: Ordboken
    private var word: Word? = null
    private lateinit var anki: Anki
    private var imagesMap = HashMap<String, ArrayList<String>>()
    private var sentencesMap = HashMap<String, ArrayList<String>>()
    private var currentDefinition: Word.Definition? = null
    private var currentCard: CardView? = null
    private var mAudio: String = ""
    private var saveDeckName = true
    private var mLeftCards = 0

    private fun createCard(title: String, examples: List<String>): CardView {
        val card = layoutInflater.inflate(R.layout.card, cardHolder, false)

        val cardTitle = card.findViewById<TextView>(R.id.cardTitle)
        val cardExample = card.findViewById<TextView>(R.id.cardExample)
        cardTitle.text = title

        cardExample.text = examples.joinToString(separator = "; ")

        mLeftCards += 1

        return card as CardView
    }

    private fun createCard(text: String, examples: List<String>, images: List<String>, audio: String) {
        val deckName = findViewById<EditText>(R.id.deckName).text.toString()
        val id = anki.createCard(deckName, text, examples, images, audio)

        Toast.makeText(this, if (id == null) "Fail" else "Card added to $deckName",
                Toast.LENGTH_SHORT).show()

        if (id != null) {
            mLeftCards -= 1
        }

        if (saveDeckName) {
            getPreferences(Context.MODE_PRIVATE)?.let { pref ->
                with(pref.edit()) {
                    putString("deckName", deckName)
                    commit()
                }
            }
        }

        if (mLeftCards <= 0) {
            finish()
        }
    }

    private fun loadWord(word: Word) {
        this.word = word

        for (definition in word.definitions) {
            val card = createCard("${word.mTitle}: ${definition.definition}", definition.examples)
            val createButton = card.findViewById<Button>(R.id.card_create_button)

            createButton.setOnClickListener {
                val images = imagesMap[definition.definition] ?: ArrayList()
                val sentences = sentencesMap[definition.definition] ?: ArrayList()
                var examples = definition.examples + sentences

                if (examples.isEmpty()) {
                    examples = arrayListOf(word.mTitle)
                }

                createCard(word.getPage(listOf(definition), ordboken.currentCss),
                        examples, images, mAudio)
                card.visibility = View.GONE
            }

            card.findViewById<Button>(R.id.card_images_button).apply {
                setOnClickListener {
                    Intent(this@CardActivity, ImagePicker::class.java).also {
                        it.putExtra(Intent.EXTRA_TEXT, word.mTitle)
                        currentDefinition = definition
                        currentCard = card
                        startActivityForResult(it, IMAGE_PICKER_REQUEST)
                    }
                }
                visibility = View.VISIBLE
            }

            card.findViewById<Button>(R.id.card_clear_button).apply {
                setOnClickListener {
                    Intent(this@CardActivity, ImagePicker::class.java).also {
                        val empty = arrayListOf<String>()

                        imagesMap[definition.definition] = empty
                        sentencesMap[definition.definition] = empty

                        showImages(card.findViewById<LinearLayout>(R.id.card_images), empty)
                        showSentences(card.findViewById<LinearLayout>(R.id.card_sentences), empty)
                    }
                }
                visibility = View.VISIBLE
            }

            cardHolder.addView(card)
        }

        for (idiom in word.idioms) {
            val card = createCard("${idiom.idiom}: ${idiom.definition}", idiom.examples)
            val createButton = card.findViewById<Button>(R.id.card_create_button)

            createButton.setOnClickListener {
                val text = """<strong>${idiom.idiom}</strong><p>${idiom.definition}"""
                var examples = idiom.examples

                if (examples.isEmpty()) {
                    examples = arrayListOf(idiom.idiom)
                }

                createCard(text, examples, ArrayList(), "")
                card.visibility = View.GONE
            }

            cardHolder.addView(card)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (ContextCompat.checkSelfPermission(this,
                        AddContentApi.READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            AddContentApi.READ_WRITE_PERMISSION)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed; request the permission
                ActivityCompat.requestPermissions(this,
                        arrayOf(AddContentApi.READ_WRITE_PERMISSION),
                        1)

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            // Permission has already been granted
        }

        anki = Anki(this)

        var deckName = getPreferences(Context.MODE_PRIVATE)?.getString("deckName", "Nordict") ?: "Nordict"

        intent?.getStringExtra("deckName")?.let {
            saveDeckName = false
            deckName = it
        }

        findViewById<EditText>(R.id.deckName).setText(deckName)
        title = deckName

        ordboken = Ordboken.getInstance(this)
        ordboken.images = ArrayList()
        val word = ordboken.currentWord ?: run {
            finish()
            return
        }

        WordAudioTask().execute(word)
    }

    fun showImages(view: ViewGroup, images: ArrayList<String>) {
        view.removeAllViews()

        for (dataUrl in images) {
            val base64 = dataUrl.split(",")[1]
            val decoded = Base64.decode(base64, Base64.DEFAULT)
            val bytes = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)

            val imageView = ImageView(this)
            imageView.setImageBitmap(bytes)

            view.addView(imageView)
        }
    }

    fun showSentences(view: ViewGroup, sentences: ArrayList<String>) {
        view.removeAllViews()

        sentences.forEach {
            view.addView(TextView(this).apply {
                text = Html.fromHtml(it.replace("em>", "strong>"), Html.FROM_HTML_MODE_LEGACY)
            })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            IMAGE_PICKER_REQUEST -> {
                currentDefinition?.let {
                    imagesMap.putIfAbsent(it.definition, ArrayList())
                    imagesMap[it.definition]?.addAll(ordboken.images)

                    currentCard?.apply {
                        val imagesHolder = findViewById<LinearLayout>(R.id.card_images)
                        showImages(imagesHolder, imagesMap[it.definition]!!)
                    }
                }
            }
        }

    }

    fun getAudio(word: Word) : String {
        var audio = ""

        if (word.audio.isNotEmpty()) {
            val request = Request.Builder().url(word.audio[0])
                    .build()
            val response = ordboken.client.newCall(request).execute()
            if (response.isSuccessful) {
                audio = Base64.encodeToString(response.body?.bytes(), Base64.DEFAULT)
            }
        }

        return audio
    }

    private inner class WordAudioTask : AsyncTask<Word, Void, Pair<Word, String>>() {
        override fun doInBackground(vararg params: Word): Pair<Word, String> {
            return Pair(params[0], getAudio(params[0]))
        }

        override fun onPostExecute(result: Pair<Word, String>) {
            mAudio = result.second
            loadWord(result.first)
        }
    }

    companion object {
        private const val IMAGE_PICKER_REQUEST = 0
        private const val SENTENCE_PICKER_REQUEST = 1
    }
}
