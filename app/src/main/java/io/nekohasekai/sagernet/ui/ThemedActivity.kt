package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        if (!isDialog) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !Theme.usingNightMode()
                isAppearanceLightNavigationBars = !Theme.usingNightMode()
            }
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { content, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                val appbar = findViewById<AppBarLayout>(R.id.appbar)
                appbar?.updatePadding(top = bars.top)
                // Drawers are siblings of the appbar; consumed child insets cannot pad them.
                for (id in intArrayOf(R.id.nav_view, R.id.nav_view_black)) {
                    findViewById<android.view.View>(id)?.updatePadding(top = if (appbar == null) 0 else bars.top)
                }
                content.updatePadding(left = bars.left, right = bars.right,
                    top = if (appbar == null) bars.top else 0, bottom = maxOf(bars.bottom, ime.bottom))
                // Insets are handled here once. Lists keep their own spacing and IME pages do not double-pad.
                WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), Insets.NONE)
                    .build()
            }
            ViewCompat.requestApplyInsets(findViewById(android.R.id.content))
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}