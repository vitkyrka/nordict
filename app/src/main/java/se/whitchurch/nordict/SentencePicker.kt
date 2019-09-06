package se.whitchurch.nordict

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SentencePicker : AppCompatActivity() {
    private lateinit var korp: Korp
    private lateinit var word: String
    private lateinit var listView: ListView
    private var sentences = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sentence_picker)

        val intentWord = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val word = intentWord ?: "lumpen"
        val pos = Pos.valueOf(intent?.getStringExtra("pos") ?: "ADJECTIVE")

        this.word = word

        listView = findViewById(android.R.id.list)

        korp = Korp(Ordboken.getInstance(this).client)
        korp.getSentences(word, pos) {
            initList(it)
        }

        findViewById<Button>(R.id.ok_button).setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().apply {
                putStringArrayListExtra("sentences", getSelected())
            })
            finish()
        }
    }

    private fun initList(sentences: ArrayList<String>) {
        if (sentences.isEmpty()) {
            Toast.makeText(this, "No results", Toast.LENGTH_SHORT).show()
            return
        }

        val spanneds = ArrayList<Spanned>()

        for (sentence in sentences) {
            spanneds.add(Html.fromHtml(sentence, Html.FROM_HTML_MODE_LEGACY))
        }

        val adapter = ArrayAdapter(this,
                R.layout.simple_list_item_multiple_choice_left,
                spanneds)

        this.sentences = sentences

        val listView = findViewById<ListView>(android.R.id.list)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
    }

    fun getSelected(): ArrayList<String> {
        val selected = ArrayList<String>()
        val items = listView.checkedItemPositions ?: return selected

        for (i in sentences.indices) {
            if (!items.get(i)) {
                continue
            }

            selected.add(sentences[i].replace("strong>", "em>"))
        }

        return selected
    }
}
