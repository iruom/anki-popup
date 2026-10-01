package io.github.iruom.ankipopup

import android.app.KeyguardManager
import android.os.PowerManager
import android.view.WindowManager
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.shadows.ShadowSettings
import android.app.Notification
import android.Manifest
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowApplication
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AndroidIntegrationTest {
    class FakeAnki : ContentProvider() {
        var selectionSeen: String? = null
        var writes = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection!!.map { it }.toTypedArray()
            return MatrixCursor(columns).apply {
                when (uri.path) {
                    "/decks" -> addRow(arrayOf<Any>(10L, "Sample::Child"))
                    "/models/5" -> addRow(arrayOf("表面\u001f裏面\u001f例文英語"))
                    "/notes" -> { selectionSeen = selection; addRow(arrayOf<Any>(42L, 5L, "<b>Hello</b> [sound:hello.mp3]\u001fこんにちは\u001fHello there.")) }
                    else -> error("Unexpected API endpoint: $uri")
                }
            }
        }
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/test"
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("Writes forbidden") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Writes forbidden") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { writes++; error("Writes forbidden") }
    }
    @Test fun officialApiContractIsQueriedWithoutWrites() {
        val fake = FakeAnki()
        ShadowContentResolver.registerProviderInternal(AnkiRepository.AUTHORITY, fake)
        val repository = AnkiRepository(RuntimeEnvironment.getApplication().contentResolver)
        assertEquals("Sample::Child", repository.decks().single().name)
        val card = repository.cards("Sample", "", "").single()
        assertEquals("deck:\"Sample\"", fake.selectionSeen)
        assertEquals("Hello", card.front)
        assertEquals("こんにちは", card.back)
        assertEquals(listOf("Hello there."), card.extras)
        assertEquals(listOf("hello.mp3"), card.audioNames)
        assertEquals(0, fake.writes)
    }
    @Test fun htmlActiveContentIsRemoved() {
        assertEquals("hello & goodbye", plainText("<script>secret</script><style>.x{}</style><img src='https://invalid.example/x'><b>hello &amp; goodbye</b> [sound:x.mp3]"))
    }
    @Test fun notificationSessionShowsBothSidesAndHidesWithoutRestarting() = studySession(false)
    @Test fun floatingSessionHidesReturnsAndRemovesWindowsOnStop() = studySession(true)
    private fun studySession(floating: Boolean) {
        ShadowSettings.setCanDrawOverlays(floating)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().putBoolean("overlay", floating).apply()
        val controller = Robolectric.buildService(StudyService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(service, StudyService::class.java).setAction(StudyService.START).putExtra("demo", true), 0, 1)
        val manager = shadowOf(service.getSystemService(NotificationManager::class.java))
        val windows = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        // Repository work happens on an executor. Wait only for the initial delivery.
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            val title = manager.getNotification(StudyService.NOTIFICATION_ID)?.extras?.getString(Notification.EXTRA_TITLE)
            if (title == "serendipity" || title == "at your own pace") return@repeat
            Thread.sleep(10)
        }
        if (floating) {
            assertEquals(1, windows.views.size)
            val params = windows.views.single().layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
        val notification = manager.getNotification(StudyService.NOTIFICATION_ID)
        assertNotNull(notification)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().isNotBlank())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(3, notification.actions.size)
        assertNotEquals(notification.extras.getString(Notification.EXTRA_TITLE), notification.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        if (floating) {
            val card = windows.views.single() as android.widget.LinearLayout
            val controls = card.getChildAt(3) as android.widget.LinearLayout
            controls.getChildAt(2).performClick()
        } else service.onStartCommand(Intent(service, StudyService::class.java).setAction(StudyService.HIDE), 0, 2)
        if (floating) assertEquals(0, windows.views.size)
        assertEquals(service.getString(R.string.hidden), manager.getNotification(StudyService.NOTIFICATION_ID).extras.getString(Notification.EXTRA_TEXT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(29))
        assertEquals(service.getString(R.string.hidden), manager.getNotification(StudyService.NOTIFICATION_ID).extras.getString(Notification.EXTRA_TEXT))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNotEquals(service.getString(R.string.hidden), manager.getNotification(StudyService.NOTIFICATION_ID).extras.getString(Notification.EXTRA_TEXT))
        service.onStartCommand(Intent(service, StudyService::class.java).setAction(StudyService.NEXT), 0, 3)
        assertNotEquals(service.getString(R.string.hidden), manager.getNotification(StudyService.NOTIFICATION_ID).extras.getString(Notification.EXTRA_TEXT))
        assertEquals(1, manager.allNotifications.size)
        if (floating) {
            assertEquals(1, windows.views.size)
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(0, windows.views.size)
            shadowOf(service.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, windows.views.size)
        }
        controller.destroy()
        assertFalse(StudyService.running)
        assertEquals(0, manager.allNotifications.size)
        if (floating) assertEquals(0, windows.views.size)
    }
}
