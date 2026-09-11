package se.whitchurch.nordict

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

class LockableNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : NestedScrollView(context, attrs, defStyle) {

    var scrollLocked: Boolean = false
        set(value) {
            field = value
            isNestedScrollingEnabled = !value
        }

    override fun canScrollVertically(direction: Int): Boolean =
        !scrollLocked && super.canScrollVertically(direction)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        !scrollLocked && super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        scrollLocked || super.onTouchEvent(ev)
}