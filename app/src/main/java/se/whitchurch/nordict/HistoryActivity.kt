package se.whitchurch.nordict

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.ActionBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class HistoryActivity : androidx.appcompat.app.AppCompatActivity() {
    private var mOrdboken: Ordboken? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val actionBar = supportActionBar

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        actionBar!!.displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM or ActionBar.DISPLAY_SHOW_HOME
        actionBar.setCustomView(R.layout.actionbar)


        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed; request the permission
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        1)

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            // Permission has already been granted
        }

        mOrdboken = Ordboken.getInstance(this)

        val pager = findViewById<View>(R.id.viewPager) as androidx.viewpager.widget.ViewPager
        pager.adapter = TabPagerAdapter(supportFragmentManager)
        pager.setOnPageChangeListener(
                object : androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                    override fun onPageSelected(position: Int) {
                        actionBar.setSelectedNavigationItem(position)
                    }
                }
        )

        actionBar.navigationMode = ActionBar.NAVIGATION_MODE_TABS
        val tabListener = object : ActionBar.TabListener {
            override fun onTabReselected(tab: ActionBar.Tab?, ft: androidx.fragment.app.FragmentTransaction?) {
            }

            override fun onTabUnselected(tab: ActionBar.Tab?, ft: androidx.fragment.app.FragmentTransaction?) {
            }

            override fun onTabSelected(tab: ActionBar.Tab?, ft: androidx.fragment.app.FragmentTransaction?) {
                pager.currentItem = tab!!.position
            }
        }

        actionBar.addTab(actionBar.newTab().setText(getString(R.string.history)).setTabListener(tabListener))
        actionBar.addTab(actionBar.newTab().setText(getString(R.string.bookmarks)).setTabListener(tabListener))
    }

    private inner class TabPagerAdapter(fm: androidx.fragment.app.FragmentManager) : androidx.fragment.app.FragmentPagerAdapter(fm) {

        override fun getItem(pos: Int): androidx.fragment.app.Fragment {
            when (pos) {
                1 -> return FavoritesFragment.newInstance()
                else -> return HistoryFragment.newInstance()
            }
        }

        override fun getCount(): Int {
            return 2
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        mOrdboken!!.initSearchView(this, menu, null, true)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (mOrdboken!!.onOptionsItemSelected(this, item)) {
            true
        } else super.onOptionsItemSelected(item)

    }

    override fun onPause() {
        super.onPause()

        mOrdboken!!.prefsEditor.commit()
    }

    override fun onResume() {
        super.onResume()

        mOrdboken!!.onResume(this)
    }
}
