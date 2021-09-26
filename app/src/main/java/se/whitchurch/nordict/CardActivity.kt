package se.whitchurch.nordict

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.AsyncTask
import android.os.Bundle
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
    private var currentDefinition: Word.Definition? = null
    private var currentCard: CardView? = null
    private var mAudio = ArrayList<String>()
    private var mDictImages = ArrayList<String>()
    private var selectedDefinitions = ArrayList<Word.Definition>()
    private var defToCard = HashMap<Word.Definition, View>()
    private var saveDeckName = true
    private var mLeftCards = 0

    private fun createCard(title: String, examples: List<String>): CardView {
        val card = layoutInflater.inflate(R.layout.card, cardHolder, false)

        val cardTitle = card.findViewById<TextView>(R.id.cardTitle)
        val cardExample = card.findViewById<TextView>(R.id.cardExample)
        cardTitle.text = title


        val examplesText = if (examples.isNotEmpty()) "• " else ""
        cardExample.text = examplesText + examples.joinToString(separator = "\n• ")

        mLeftCards += 1

        return card as CardView
    }

    private fun createCard(
        text: String,
        examples: List<String>,
        images: List<String>,
        audio: String
    ) {
        val deckName = findViewById<EditText>(R.id.deckName).text.toString()
        val id = anki.createCard(deckName, text, examples, images, audio)

        Toast.makeText(
            this, if (id == null) "Fail" else "Card added to $deckName",
            Toast.LENGTH_SHORT
        ).show()

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

    private fun playAudio(url: String) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)

        try {
            mediaPlayer.setDataSource(url)
        } catch (e: Exception) {
            setProgressBarIndeterminateVisibility(false)
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            return
        }

        mediaPlayer.setOnPreparedListener { mp ->
            setProgressBarIndeterminateVisibility(false)
            mp.start()
        }

        mediaPlayer.setOnErrorListener { mp, what, extra ->
            setProgressBarIndeterminateVisibility(false)
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            false
        }

        mediaPlayer.prepareAsync()
    }

    private fun loadWord(word: Word) {
        this.word = word

        for (definition in word.definitions) {
            val card = createCard("${word.mTitle}: ${definition.definition}", definition.examples)
            val createButton = card.findViewById<Button>(R.id.card_create_button)

            createButton.setOnClickListener {
                val images = imagesMap[definition.definition] ?: ArrayList()
                var examples = ArrayList<String>()

                val audioIdx = if (mAudio.size > 1) {
                    card.findViewById<Spinner>(R.id.card_audio_index).selectedItem.toString()
                        .toInt() - 1
                } else {
                    0
                }

                if (selectedDefinitions.isEmpty()) {
                    selectedDefinitions.add(definition)
                }

                selectedDefinitions.forEach {
                    examples.addAll(it.examples)
                }

                if (examples.isEmpty()) {
                    examples = arrayListOf(word.mTitle)
                }

                createCard(word.getPage(selectedDefinitions, ordboken.currentCss),
                    examples, images, mAudio.elementAtOrElse(audioIdx) { _ -> "" })
                card.visibility = View.GONE
                selectedDefinitions.forEach { defToCard[it]?.visibility = View.GONE }
                selectedDefinitions.clear()
            }

            if (mAudio.size > 1) {
                card.findViewById<Spinner>(R.id.card_audio_index).apply {
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_spinner_item,
                        (1..mAudio.size).toList()
                    ).also { adapter ->
                        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        this.adapter = adapter
                    }

                    visibility = View.VISIBLE
                    setSelection(0, false)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long
                        ) {
                            playAudio(word.audio[position])
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {
                        }
                    }
                }
            }

            card.findViewById<Button>(R.id.card_images_button).apply {
                setOnClickListener {
                    Intent(this@CardActivity, ImagePicker::class.java).also {
                        it.putExtra(Intent.EXTRA_TEXT, word.mTitle)
                        it.putExtra("dictionaryImages", mDictImages)
                        currentDefinition = definition
                        currentCard = card
                        ordboken.images = ArrayList()
                        startActivityForResult(it, IMAGE_PICKER_REQUEST)
                    }
                }
                visibility = View.VISIBLE
            }

            card.findViewById<Button>(R.id.card_photo_button).apply {
                setOnClickListener {
                    Intent(this@CardActivity, CameraActivity::class.java).also {
                        it.putExtra(Intent.EXTRA_TEXT, word.mTitle)
                        currentDefinition = definition
                        currentCard = card
                        ordboken.images = ArrayList()
                        startActivityForResult(it, CAMERA_REQUEST)
                    }
                }
                visibility = View.VISIBLE
            }

            card.findViewById<Button>(R.id.card_clear_button).apply {
                setOnClickListener {
                    Intent(this@CardActivity, ImagePicker::class.java).also {
                        val empty = arrayListOf<String>()

                        imagesMap[definition.definition] = empty

                        showImages(card.findViewById<LinearLayout>(R.id.card_images), empty)
                    }
                }
                visibility = View.VISIBLE
            }

            card.findViewById<CheckBox>(R.id.card_merge).apply {
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        card.setBackgroundColor(Color.YELLOW)
                        selectedDefinitions.add(definition)
                    } else {
                        card.setBackgroundColor(Color.WHITE)
                        selectedDefinitions.remove(definition)
                    }
                }
            }

            defToCard[definition] = card
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

        if (ContextCompat.checkSelfPermission(
                this,
                AddContentApi.READ_WRITE_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    AddContentApi.READ_WRITE_PERMISSION
                )
            ) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed; request the permission
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(AddContentApi.READ_WRITE_PERMISSION),
                    1
                )

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            // Permission has already been granted
        }

        anki = Anki(this)

        var deckName =
            getPreferences(Context.MODE_PRIVATE)?.getString("deckName", "Nordict") ?: "Nordict"

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            IMAGE_PICKER_REQUEST, CAMERA_REQUEST -> {
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

    fun urlsToData(urls: ArrayList<String>): ArrayList<String> {
        var data = ArrayList<String>()

        urls.forEach {
            val request = Request.Builder().url(it)
                .build()
            val response = ordboken.client.newCall(request).execute()
            if (!response.isSuccessful) return@forEach

            response.body?.let { body ->
                val type = body.contentType()
                val base64 = Base64.encodeToString(body.bytes(), Base64.DEFAULT)

                data.add("data:$type;base64,$base64")
            }
        }

        return data
    }

    private inner class WordAudioTask :
        AsyncTask<Word, Void, Triple<Word, ArrayList<String>, ArrayList<String>>>() {
        override fun doInBackground(vararg params: Word): Triple<Word, ArrayList<String>, ArrayList<String>> {
            val word = params[0]

            return Triple(word, urlsToData(word.audio), urlsToData(word.images))
        }

        override fun onPostExecute(result: Triple<Word, ArrayList<String>, ArrayList<String>>) {
            mAudio.addAll(result.second)
            mDictImages.addAll(result.third)
            loadWord(result.first)
        }
    }

    companion object {
        private const val IMAGE_PICKER_REQUEST = 0
        private const val CAMERA_REQUEST = 2
    }
}
