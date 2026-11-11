package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.widget.EditText
import android.view.ViewGroup
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SettingsInputTest {
    @Test fun invalidLogSizeKeepsDialogAndPreviousValueUntilCorrected() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        val controller = Robolectric.buildActivity(RouteToggleHostActivity::class.java).setup()
        try {
            val host = controller.get()
            DataStore.logBufSize = 100
            var saved = 0
            val dialog = showLogBufferSizeDialog(host) { saved++ }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            fun findEdit(view: android.view.View): EditText? {
                if (view is EditText) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    findEdit(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val input = findEdit(dialog.window!!.decorView)!!
            val confirm = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            for (value in listOf("", "2147483648", "0", "65537")) {
                input.setText(value)
                confirm.performClick()
                assertTrue(dialog.isShowing)
                assertNotNull(input.error)
                assertEquals(100, DataStore.logBufSize)
            }
            input.setText("512")
            confirm.performClick()
            assertEquals(512, DataStore.logBufSize)
            assertFalse(dialog.isShowing)
            assertEquals(1, saved)
        } finally { controller.close() }
    }
}
