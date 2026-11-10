package io.nekohasekai.sagernet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Uses production layouts and theme/inset handling; it does not start a VPN or a remote connection. */
class LayoutPreviewActivity : ThemedActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.layout_main)
        findViewById<View>(R.id.nav_view_black).visibility = View.GONE
        val container = findViewById<ViewGroup>(R.id.fragment_holder)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(column, ViewGroup.LayoutParams(-1, -1))
        layoutInflater.inflate(R.layout.layout_appbar, column, true)
        findViewById<MaterialToolbar>(R.id.toolbar).title = "ThBox"
        listOf("Singapore · Hysteria2", "Tokyo · Shadowsocks", "Home · WireGuard").forEachIndexed { index, name ->
            val card = layoutInflater.inflate(R.layout.layout_profile, column, false) as MaterialCardView
            card.findViewById<TextView>(R.id.profile_name).text = name
            card.findViewById<TextView>(R.id.profile_type).text = if (index == 0) "Hysteria2" else "Proxy"
            card.findViewById<TextView>(R.id.profile_status).text = "${32 + index * 18} ms"
            if (index == 0) {
                card.setCardBackgroundColor(getColorAttr(com.google.android.material.R.attr.colorSecondaryContainer))
                card.strokeColor = getColorAttr(com.google.android.material.R.attr.colorPrimary)
                card.findViewById<View>(R.id.selected_view).visibility = View.VISIBLE
            }
            column.addView(card)
        }
        findViewById<TextView>(R.id.status).text = "Connected"
        findViewById<TextView>(R.id.tx).text = "↑ 12.4 KB/s"
        findViewById<TextView>(R.id.rx).text = "↓ 128.0 KB/s"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], qualifiers = "w360dp-h800dp-mdpi", shadows = [QuietSagerNet::class, FixtureProcess::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiLayoutTest {
    @Before fun excludeRemoteRoomServiceFromSingleProcessFixture() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, androidx.room.MultiInstanceInvalidationService::class.java))
    }

    private fun screenshot(activity: android.app.Activity, name: String) {
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 800)
        val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File(requireNotNull(System.getProperty("thbox.uiSnapshots"))).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun render(night: Boolean) {
        DataStore.appTheme = Theme.LIGHT_BLUE
        DataStore.nightTheme = if (night) 1 else 2
        DataStore.dynamicColors = false
        Theme.currentNightMode = -1
        Theme.applyNightTheme()
        val controller = Robolectric.buildActivity(LayoutPreviewActivity::class.java).setup().visible()
        val activity = controller.get()
        val content = activity.findViewById<View>(android.R.id.content)
        val bars = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 24)).build()
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(content, bars) }
        assertEquals(24, activity.findViewById<View>(R.id.appbar).paddingTop)
        assertEquals(24, activity.findViewById<View>(R.id.nav_view).paddingTop)
        assertEquals(24, activity.findViewById<View>(R.id.nav_view_black).paddingTop)
        assertEquals(0, content.paddingTop)
        assertEquals(24, content.paddingBottom)
        assertEquals(night, (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val mode = if (night) "night" else "day"
        screenshot(activity, "nodes-$mode")
        activity.findViewById<DrawerLayout>(R.id.drawer_layout).openDrawer(activity.findViewById<View>(R.id.nav_view), false)
        screenshot(activity, "drawer-$mode")
        // API 29 represents the keyboard in the legacy bottom system inset.
        val ime = WindowInsetsCompat.toWindowInsetsCompat(android.view.WindowInsets.Builder()
            .setSystemWindowInsets(android.graphics.Insets.of(0, 24, 0, 240))
            .setStableInsets(android.graphics.Insets.of(0, 24, 0, 24)).build())
        ViewCompat.dispatchApplyWindowInsets(content, ime)
        assertEquals(240, content.paddingBottom)
        controller.pause().stop().destroy()
        DataStore.serverConfig = """{"route":{"rules":[{"domain_suffix":["example.com"],"outbound":"proxy"}]}}"""
        val editorController = Robolectric.buildActivity(io.nekohasekai.sagernet.ui.profile.ConfigEditActivity::class.java).setup().visible()
        val editor = editorController.get()
        val scheme = editor.binding.editor.colorScheme
        for (foreground in listOf(scheme.textColor, scheme.numberColor, scheme.operatorColor, scheme.keywordColor, scheme.stringColor, scheme.commentColor)) {
            assertTrue("Editor text contrast", androidx.core.graphics.ColorUtils.calculateContrast(foreground, scheme.backgroundColor) >= 4.5)
        }
        val editorContent = editor.findViewById<View>(android.R.id.content)
        ViewCompat.dispatchApplyWindowInsets(editorContent, bars)
        screenshot(editor, "editor-$mode")
        val keyboard = editor.findViewById<View>(R.id.keyboard_container)
        for (id in listOf(R.id.action_tab, R.id.action_undo, R.id.action_redo, R.id.action_format)) {
            val button = editor.findViewById<View>(id)
            assertTrue("Toolbar action must remain on screen", button.left >= 0 && button.right <= keyboard.width)
            assertTrue(button.width >= 48 && button.height >= 48)
            assertFalse(button.contentDescription.isNullOrBlank())
        }
        ViewCompat.dispatchApplyWindowInsets(editorContent, ime)
        assertEquals(240, editorContent.paddingBottom)
        assertEquals(0, (editor.findViewById<View>(R.id.keyboard_container).layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
        screenshot(editor, "editor-ime-$mode")
        editorController.pause().stop().destroy()
    }

    @Test fun lightLayoutKeepsDrawerInsetsAndRenders() = render(false)
    @Test @Config(qualifiers = "w360dp-h800dp-night-mdpi")
    fun darkLayoutKeepsDrawerInsetsAndRenders() = render(true)
}
