package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import androidx.room.MultiInstanceInvalidationService
import com.google.android.material.chip.ChipGroup
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
class AppProxyModeTest {
    @Test fun clipboardImportKeepsApplyActionOnTheForegroundPage() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = Shadows.shadowOf(app)
        shadow.declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        DataStore.serviceMode = Key.MODE_PROXY
        DataStore.rulesFirstCreate = true
        val binder = FixtureBinder()
        shadow.setComponentNameAndServiceForBindServiceForIntent(
            android.content.Intent(app, ProxyService::class.java).setAction(Action.SERVICE),
            ComponentName(app, ProxyService::class.java), binder)
        shadow.setBindServiceCallsOnServiceConnectedDirectly(true)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        main.pause().stop()
        val controller = Robolectric.buildActivity(AppManagerActivity::class.java).setup().visible()
        try {
            DataStore.serviceState = BaseService.State.Connected
            val activity = controller.get()
            val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "false\nfixture.browser"))
            val menu = androidx.appcompat.widget.PopupMenu(activity, activity.findViewById(R.id.toolbar)).menu
            activity.onCreateOptionsMenu(menu)
            activity.onOptionsItemSelected(menu.findItem(R.id.action_import_clipboard))
            val looper = Shadows.shadowOf(android.os.Looper.getMainLooper())
            looper.idleFor(java.time.Duration.ofSeconds(1))
            val action = activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_action)
            assertNotNull(action)
            assertEquals(activity.getString(R.string.apply), action.text.toString())
            assertTrue(action.isShown)
            val intentsBefore = shadow.broadcastIntents.count { it.action == Action.RELOAD }
            action.performClick()
            looper.idle()
            assertEquals(intentsBefore + 1, shadow.broadcastIntents.count { it.action == Action.RELOAD })
            assertEquals(PerAppProxySettings(true, false, setOf("fixture.browser")), PerAppProxySettings.read())
        } finally {
            controller.close()
            main.destroy()
        }
    }

    @Test fun openingAndRecreatingDisabledSettingsDoesNotEnableThemButExplicitModesDo() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        DataStore.serviceState = BaseService.State.Stopped
        PerAppProxySettings.importSelection(true, "fixture.browser")
        PerAppProxySettings.setMode(false, true)
        val controller = Robolectric.buildActivity(AppManagerActivity::class.java).setup()
        try {
            fun chips() = controller.get().findViewById<ChipGroup>(R.id.bypassGroup)
            assertFalse(DataStore.proxyApps)
            assertEquals(R.id.appProxyModeDisable, chips().checkedChipId)
            controller.recreate()
            assertFalse(DataStore.proxyApps)
            assertEquals("fixture.browser", DataStore.individual)
            chips().check(R.id.appProxyModeOn)
            assertEquals(PerAppProxySettings(true, false, setOf("fixture.browser")), PerAppProxySettings.read())
            chips().check(R.id.appProxyModeBypass)
            assertTrue(DataStore.proxyApps); assertTrue(DataStore.bypass)
            chips().check(R.id.appProxyModeDisable)
            assertFalse(DataStore.proxyApps)
            assertFalse(controller.get().isFinishing)
        } finally { controller.close() }
    }
}
