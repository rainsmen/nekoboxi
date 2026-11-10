package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object ListListener : OnApplyWindowInsetsListener {
    private val originalBottom = java.util.WeakHashMap<View, Int>()
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat) = insets.apply {
        val base = originalBottom.getOrPut(view) { view.paddingBottom }
        view.updatePadding(bottom = base + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
    }
}
