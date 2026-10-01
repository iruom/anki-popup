package io.github.iruom.ankipopup

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class FloatingGestureTest {
    private val commands = mutableListOf<String>()
    private lateinit var floating: FloatingCard
    private lateinit var surface: LinearLayout
    private var downTime = 0L
    @Before fun create() {
        val app = RuntimeEnvironment.getApplication()
        ShadowSettings.setCanDrawOverlays(true)
        floating = FloatingCard(app) { commands.add(it) }
        floating.show(StudyCard(1, "Hello", "こんにちは", listOf("Hello there.")))
        val windows = Shadow.extract<ShadowWindowManagerImpl>(app.getSystemService(WindowManager::class.java))
        surface = windows.views.single().findViewWithTag("floating_card")
        idle(250)
    }
    @After fun destroy() { floating.hide() }
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun event(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        surface.dispatchTouchEvent(e); e.recycle()
    }
    private fun swipe(x: Float, y: Float) {
        event(MotionEvent.ACTION_DOWN, 20f, 20f); idle(16)
        event(MotionEvent.ACTION_MOVE, x, y); idle(16)
        event(MotionEvent.ACTION_UP, x, y); idle(250)
    }
    @Test fun compactCardHasNoButtonsAndExamplesStartHidden() {
        assertEquals(3, surface.childCount)
        assertEquals(View.GONE, surface.getChildAt(2).visibility)
        val density = surface.resources.displayMetrics.density
        assertTrue(surface.measuredHeight <= 120 * density)
        assertTrue((0 until surface.childCount).none { surface.getChildAt(it) is android.widget.Button })
    }
    @Test fun tapPlaysAudioOnce() {
        event(MotionEvent.ACTION_DOWN, 20f, 20f); idle(30)
        event(MotionEvent.ACTION_UP, 20f, 20f); idle(250)
        assertEquals(listOf(StudyService.AUDIO), commands)
    }
    @Test fun rightSwipeGoesNextAndDoesNotPlayAudio() {
        swipe(120f, 22f)
        assertEquals(listOf(StudyService.NEXT), commands)
        // The pose is restored even when a deck has just one note.
        assertEquals(1f, surface.alpha, .001f)
        assertEquals(0f, surface.translationX, .001f)
    }
    @Test fun downSwipeTogglesExamplesAndNewCardCollapsesThem() {
        swipe(22f, 140f)
        assertEquals(View.VISIBLE, surface.getChildAt(2).visibility)
        assertTrue(commands.isEmpty())
        swipe(22f, 140f)
        assertEquals(View.GONE, surface.getChildAt(2).visibility)
        swipe(22f, 140f)
        floating.show(StudyCard(2, "Goodbye", "さようなら", listOf("Goodbye now.")))
        assertEquals(View.GONE, surface.getChildAt(2).visibility)
    }
    @Test fun longPressDragsAndSavesWithoutPlayingOrChangingCard() {
        event(MotionEvent.ACTION_DOWN, 20f, 20f); idle(800)
        event(MotionEvent.ACTION_MOVE, 50f, 60f); idle(16)
        event(MotionEvent.ACTION_UP, 50f, 60f); idle(250)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0)
        assertTrue(prefs.contains("overlay_x")); assertTrue(prefs.contains("overlay_y"))
        assertTrue(commands.isEmpty())
    }
    @Test fun upSwipeHidesUntilTheNextCard() {
        swipe(22f, -100f)
        assertEquals(listOf(StudyService.HIDE), commands)
    }
    @Test fun cancelledGestureDoesNotTriggerAnAction() {
        event(MotionEvent.ACTION_DOWN, 20f, 20f); idle(16)
        event(MotionEvent.ACTION_MOVE, 120f, 20f)
        event(MotionEvent.ACTION_CANCEL, 120f, 20f); idle(800)
        assertTrue(commands.isEmpty())
    }
}
