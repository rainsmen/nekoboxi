package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.coordinatorlayout.widget.CoordinatorLayout
import io.nekohasekai.sagernet.R

/** Keep the control at the bar's resting position, independent of its scroll translation. */
class FixedServiceButtonBehavior(context: Context, attrs: AttributeSet?) :
    CoordinatorLayout.Behavior<ServiceButton>(context, attrs) {
    override fun onLayoutChild(parent: CoordinatorLayout, child: ServiceButton, layoutDirection: Int): Boolean {
        parent.onLayoutChild(child, layoutDirection)
        val bar = parent.findViewById<StatsBar>(R.id.stats)
        val bottom = parent.height - parent.paddingBottom
        val top = (bottom - bar.measuredHeight - child.measuredHeight / 2 - bar.cradleVerticalOffset.toInt())
            .coerceAtLeast(parent.paddingTop)
        child.offsetTopAndBottom(top - child.top)
        child.translationY = 0f
        return true
    }
}
