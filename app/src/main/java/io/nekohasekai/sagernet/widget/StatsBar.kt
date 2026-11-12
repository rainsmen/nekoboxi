package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
        }
    }


    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The fixed control is intentionally not a scroll-dependent FAB. Preserve the
        // existing cradle appearance without letting BottomAppBar move or hide it.
        val button = (parent as? android.view.ViewGroup)?.findViewById<ServiceButton>(R.id.fab) ?: return
        val shape = background as? com.google.android.material.shape.MaterialShapeDrawable ?: return
        val edge = shape.shapeAppearanceModel.topEdge as? com.google.android.material.bottomappbar.BottomAppBarTopEdgeTreatment ?: return
        val bounds = android.graphics.Rect()
        button.getMeasuredContentRect(bounds)
        edge.fabDiameter = bounds.height().toFloat()
        edge.setFabCornerSize(button.shapeAppearanceModel.topLeftCornerSize.getCornerSize(android.graphics.RectF(bounds)))
        shape.interpolation = if (button.visibility == View.VISIBLE) 1f else 0f
        shape.invalidateSelf()
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    private fun mainActivity(): MainActivity {
        var owner = context
        while (owner is ContextWrapper) {
            if (owner is MainActivity) return owner
            owner = owner.baseContext
        }
        error("StatsBar must be hosted by MainActivity")
    }

    private var stateRevision = 0L
    private var testRevision = 0L
    private var stateJob: Job? = null
    private var testJob: Job? = null

    fun changeState(state: BaseService.State) {
        val revision = ++stateRevision
        ++testRevision
        testJob?.cancel()
        stateJob?.cancel()
        isEnabled = state.connected
        val activity = mainActivity()
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { if (revision == stateRevision) what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            stateJob = postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
            }
        } else {
            stateJob = postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = mainActivity()
        val service = activity.connection.service ?: return
        if (!DataStore.serviceState.connected) return
        stateJob?.cancel()
        if (allowShow) performShow()
        val connectionGeneration = activity.connection.generation
        val revision = ++testRevision
        fun isCurrent() = revision == testRevision && !activity.isDestroyed &&
            activity.connection.generation == connectionGeneration && activity.connection.service === service &&
            DataStore.serviceState.connected
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        testJob?.cancel()
        testJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val elapsed = activity.urlTest(service)
                onMainDispatcher {
                    if (!isCurrent()) return@onMainDispatcher
                    isEnabled = true
                    setStatus(app.getString(if (DataStore.connectionTestURL.startsWith("https://"))
                        R.string.connection_test_available else R.string.connection_test_available_http, elapsed))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onMainDispatcher {
                    if (!isCurrent()) return@onMainDispatcher
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_fail))
                    activity.snackbar(app.getString(R.string.connection_test_error, error.readableMessage)).show()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        ++stateRevision; ++testRevision
        stateJob?.cancel(); testJob?.cancel()
        super.onDetachedFromWindow()
    }
}
