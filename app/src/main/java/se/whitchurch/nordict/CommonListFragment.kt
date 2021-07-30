package se.whitchurch.nordict

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.AsyncTask
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.*
import android.widget.AdapterView.AdapterContextMenuInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry

abstract class CommonListFragment(private val mTable: String) : Fragment() {
    private var mState: Parcelable? = null
    private var mListView: ListView? = null
    private var mEmpty: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_list, container, false)

        mEmpty = view.findViewById<View>(android.R.id.empty) as TextView

        mListView = view.findViewById<View>(android.R.id.list) as ListView
        mListView?.setOnItemClickListener { parent, view, position, id ->
            val c = (parent as ListView).getItemAtPosition(position) as Cursor
            val title = c
                    .getString(c.getColumnIndex(HistoryEntry.COLUMN_NAME_TITLE))
            val url = c.getString(c.getColumnIndex(HistoryEntry.COLUMN_NAME_URL))

            Ordboken.startWordActivity(activity as AppCompatActivity, title, url)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        HistoryTask().execute()
    }

    override fun onPause() {
        super.onPause()

        mState = mListView?.onSaveInstanceState()
    }

    protected abstract fun getCursor(db: SQLiteDatabase): Cursor

    private inner class HistoryTask : AsyncTask<Void, Void, ListAdapter>() {
        override fun doInBackground(vararg params: Void): ListAdapter {
            val dbHelper = OrdbokenDbHelper(activity!!)
            val db = dbHelper.readableDatabase
            val cursor = getCursor(db)
            val ordboken = Ordboken.getInstance(activity!!)

            return object : SimpleCursorAdapter(activity,
                    R.layout.word_list_item, cursor,
                    arrayOf(HistoryEntry.COLUMN_NAME_TITLE, HistoryEntry.COLUMN_NAME_SUMMARY,
                            HistoryEntry.COLUMN_NAME_DICT),
                    intArrayOf(R.id.text1, R.id.text2), 0) {

                override fun newView(context: Context?, cursor: Cursor?, parent: ViewGroup?): View {
                    val view = super.newView(context, cursor, parent)
                    val imageView = view.findViewById<ImageView>(R.id.word_list_flag)

                    view.tag = imageView

                    return view
                }

                override fun bindView(view: View?, context: Context?, cursor: Cursor?) {
                    super.bindView(view, context, cursor)

                    val imageView = view?.tag as ImageView
                    val dict = cursor?.getString(cursor.getColumnIndex(HistoryEntry.COLUMN_NAME_DICT))
                            ?: return
                    val flag = ordboken.dictMap[dict]?.flag ?: return

                    imageView.setImageResource(flag)
                }
            }
        }

        override fun onPostExecute(adapter: ListAdapter) {
            mEmpty?.visibility = View.GONE
            mListView?.adapter = adapter
            if (mState != null) {
                mListView?.onRestoreInstanceState(mState)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        registerForContextMenu(mListView!!)
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)

        val inflater = activity!!.menuInflater
        inflater.inflate(R.menu.list, menu)
    }

    private inner class DeleteTask : AsyncTask<String?, Void, Void>() {
        override fun doInBackground(vararg params: String?): Void? {
            val url = params[0]
            val dbHelper = OrdbokenDbHelper(activity!!)
            val db = dbHelper.writableDatabase

            if (url == null) {
                db.delete(mTable, null, null)
            } else {
                db.delete(mTable, HistoryEntry.COLUMN_NAME_URL + "=?", arrayOf(url))
            }

            db.close()
            return null
        }

        override fun onPostExecute(aVoid: Void?) {
            HistoryTask().execute()
        }
    }

    private fun deleteItem(cursor: Cursor): Boolean {
        val url = cursor.getString(cursor.getColumnIndex(HistoryEntry.COLUMN_NAME_URL))
        DeleteTask().execute(url)
        return true
    }

    private fun deleteAll(): Boolean {
        DeleteTask().execute(null as String?)
        return true
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        // This gets called on all fragments; this hack(?) makes us operate
        // on the correct one only.
        if (!userVisibleHint) {
            return false
        }

        val info = item.menuInfo as AdapterContextMenuInfo
        val c = mListView?.adapter!!.getItem(info.position) as Cursor

        when (item.itemId) {
            R.id.delete -> return deleteItem(c)
            R.id.delete_all -> return deleteAll()
            else -> return super.onContextItemSelected(item)
        }
    }
}
