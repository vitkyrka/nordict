package se.whitchurch.nordict

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var mRetryButton: Button? = null
    private var mProgressBar: ProgressBar? = null
    private var mStatusText: TextView? = null
    private var mLastQuery: String? = null
    private var mOrdboken: Ordboken? = null
    private var mSeenResults: Boolean = false
    private var mListView: ListView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val actionBar = supportActionBar
        actionBar!!.displayOptions = (ActionBar.DISPLAY_SHOW_CUSTOM or ActionBar.DISPLAY_SHOW_HOME
                or ActionBar.DISPLAY_HOME_AS_UP)
        actionBar.setCustomView(R.layout.actionbar)

        mOrdboken = Ordboken.getInstance(this)

        mProgressBar = findViewById<View>(R.id.main_progress) as ProgressBar
        mStatusText = findViewById<View>(R.id.main_status) as TextView
        mRetryButton = findViewById<View>(R.id.main_retry) as Button
        mListView = findViewById<View>(android.R.id.list) as ListView

        mListView?.setOnItemClickListener { parent, view, position, id ->
            val searchResult = (parent as ListView).getItemAtPosition(position) as SearchResult
            Ordboken.startWordActivity(this, searchResult.mTitle, searchResult.uri)
        }


        val intent = intent
        if (Intent.ACTION_SEARCH == intent.action || Intent.ACTION_VIEW == intent.action) {
            onNewIntent(intent)
        } else {
            restoreLastView()
        }
    }

    private fun restoreLastView() {
        val where = mOrdboken!!.lastWhere
        val what = mOrdboken!!.lastWhat

        if (where == Ordboken.Where.MAIN) {
            doSearch(what)
        } else if (where == Ordboken.Where.WORD) {
            Ordboken.startWordActivity(this, "", what!!)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()

        mOrdboken!!.setLastView(Ordboken.Where.MAIN, mLastQuery!!)
        mOrdboken!!.prefsEditor.commit()
    }

    override fun onResume() {
        super.onResume()

        mOrdboken!!.onResume(this)
    }

    fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {

    }

    private inner class SearchResultAdapter(context: Context, results: Array<SearchResult>) : ArrayAdapter<SearchResult>(context, android.R.layout.simple_list_item_2, android.R.id.text1, results) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val text1 = view.findViewById<View>(android.R.id.text1) as TextView
            val text2 = view.findViewById<View>(android.R.id.text2) as TextView
            val searchResult = getItem(position)

            text1.text = searchResult!!.mTitle
            text2.text = searchResult.mSummary

            return view
        }
    }

    private inner class SearchTask : AsyncTask<String, Void, Array<SearchResult>>() {
        override fun doInBackground(vararg params: String): Array<SearchResult>? {
            if (!mOrdboken!!.isOnline) {
                return null
            }

            try {
                return mOrdboken!!.currentDictionary.fullSearch(params[0]).toTypedArray()
            } catch (e: Exception) {
                return null
            }

        }

        override fun onPostExecute(results: Array<SearchResult>?) {
            mProgressBar!!.visibility = View.GONE

            if (results == null) {
                if (!mOrdboken!!.isOnline) {
                    mStatusText!!.setText(R.string.error_offline)
                } else {
                    mStatusText!!.setText(R.string.error_results)
                }
                mRetryButton!!.visibility = View.VISIBLE
                return
            }

            mSeenResults = true
            mStatusText!!.setText(R.string.no_results)
            findViewById<View>(R.id.list_empty).visibility = if (results.isEmpty()) {
                View.VISIBLE
            } else {
                View.GONE
            }
            mListView!!.adapter = SearchResultAdapter(this@MainActivity, results)
        }
    }

    private fun doSearch(query: String?) {
        mProgressBar!!.visibility = View.VISIBLE
        mStatusText!!.setText(R.string.loading)
        mRetryButton!!.visibility = View.GONE
        mLastQuery = query
        SearchTask().execute(query)
    }

    fun doSearchAgain() {
        doSearch(mLastQuery)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (Intent.ACTION_SEARCH == intent.action) {
            val query = intent.getStringExtra(SearchManager.QUERY)
            doSearch(query)
        } else if (Intent.ACTION_VIEW == intent.action) {
            val url = intent.dataString
            val word = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY)
            Ordboken.startWordActivity(this, word, url!!)

            if (!mSeenResults) {
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        mOrdboken!!.initSearchView(this, menu, mLastQuery, false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (mOrdboken!!.onOptionsItemSelected(this, item)) {
            true
        } else super.onOptionsItemSelected(item)

    }
}
