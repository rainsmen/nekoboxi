package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], qualifiers = "w360dp-h800dp-mdpi", shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FixedServiceButtonTest {
    @Test fun stopButtonRemainsFixedClickableAndInsideInsetsWhenStatusBarScrollsAway() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        val controller = Robolectric.buildActivity(LayoutPreviewActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val root = activity.window.decorView
            val parent = activity.findViewById<CoordinatorLayout>(R.id.coordinator)
            val button = activity.findViewById<ServiceButton>(R.id.fab)
            val bar = activity.findViewById<StatsBar>(R.id.stats)
            val progress = activity.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.fabProgress)
            button.initProgress(progress)
            button.changeState(BaseService.State.Connected, BaseService.State.Stopped, false)
            var stopped = 0
            button.setOnClickListener { stopped++ }
            val content = activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 48)).build())
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 360, 800)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            }
            layout(); layout()
            val top = button.y
            val center = button.x + button.width / 2f
            assertEquals(parent.width / 2f, center, 1f)
            assertTrue(button.bottom <= parent.height - parent.paddingBottom)
            bar.hideOnScroll = true
            repeat(3) {
                bar.performHide()
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertTrue("status bar must actually move down", bar.translationY > 0)
                assertEquals(top, button.y, 1f)
                assertEquals(top, button.y, 1f)
                assertEquals(View.VISIBLE, button.visibility)
                assertTrue(button.isEnabled)
                assertTrue(button.performClick())
                bar.performShow()
                layout()
                assertEquals(top, button.y, 1f)
            }
            assertEquals(3, stopped)
            val position = IntArray(2)
            button.getLocationOnScreen(position)
            val rootPosition = IntArray(2)
            root.getLocationOnScreen(rootPosition)
            val x = position[0] - rootPosition[0] + button.width / 2f
            val y = position[1] - rootPosition[1] + button.height / 2f
            val time = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, time + 30, action, x, y, 0)
                root.dispatchTouchEvent(event); event.recycle()
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("fixed stop control must receive actual touch events", 4, stopped)
            assertEquals(button.x + button.width / 2f, progress.x + progress.width / 2f, 1f)
            assertEquals(button.y + button.height / 2f, progress.y + progress.height / 2f, 1f)
            assertEquals(activity.getString(R.string.stop), button.contentDescription)
            val shape = bar.background as com.google.android.material.shape.MaterialShapeDrawable
            val edge = shape.shapeAppearanceModel.topEdge as com.google.android.material.bottomappbar.BottomAppBarTopEdgeTreatment
            assertEquals(0f, edge.horizontalOffset, 1f)
            assertEquals(1f, shape.interpolation, 0.01f)
            bar.performHide(); layout()
            val bitmap = Bitmap.createBitmap(360, 800, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val target = File(requireNotNull(System.getProperty("thbox.uiSnapshots")), "fixed-stop-hidden-bar.png")
            target.parentFile!!.mkdirs()
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } finally { controller.close() }
    }
}
