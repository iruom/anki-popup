package io.github.iruom.ankipopup

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import java.util.concurrent.Executors

class StudyService : Service() {
    companion object {
        const val START = "io.github.iruom.ankipopup.START"
        const val NEXT = "io.github.iruom.ankipopup.NEXT"
        const val HIDE = "io.github.iruom.ankipopup.HIDE"
        const val AUDIO = "io.github.iruom.ankipopup.AUDIO"
        const val STOP = "io.github.iruom.ankipopup.STOP"
        const val CHANNEL = "study_cards_v1"
        const val NOTIFICATION_ID = 1
        @Volatile var running = false; private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var audio: LocalAudio
    private var cards = emptyList<StudyCard>()
    private var current: StudyCard? = null
    private var bag: ShuffleBag? = null
    private var cycle = StudyCycle(30_000, 0)
    private var files: Map<String, Uri> = emptyMap()
    private var generation = 0
    private var alive = true
    private var interval = 30
    private var language = "en-US"
    private val tick = object : Runnable {
        override fun run() {
            if (!alive || cards.isEmpty()) return
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return }
            if (cycle.due(SystemClock.elapsedRealtime())) nextCard()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        audio = LocalAudio(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != START && !running) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            START -> startSession(intent.getBooleanExtra("demo", false))
            STOP -> stopSelf()
            NEXT -> if (cards.isNotEmpty()) nextCard()
            HIDE -> if (current != null) { cycle.hide(); audio.stop(); showCard() }
            AUDIO -> if (!cycle.hidden) current?.let { audio.play(it, files, language) }
        }
        return START_NOT_STICKY
    }
    private fun startSession(demo: Boolean) {
        manager.cancel(NOTIFICATION_ID + 1)
        val notification = notification(getString(R.string.app_name), getString(R.string.preparing), hidden = true)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) { stopSelf(); return }
        running = true
        handler.removeCallbacks(tick); audio.stop(); cards = emptyList(); current = null
        interval = prefs.getInt("interval", 30).coerceIn(5, 3600)
        language = prefs.getString("language", "en-US") ?: "en-US"
        val deck = prefs.getString("deck", "") ?: ""
        val front = prefs.getString("front", "") ?: ""
        val back = prefs.getString("back", "") ?: ""
        val media = prefs.getString("media", null)
        val thisGeneration = ++generation
        worker.execute {
            try {
                val loaded = if (demo) listOf(
                    StudyCard(1, "serendipity", "偶然の幸運・思いがけない発見", listOf("A chance meeting led to a new idea.")),
                    StudyCard(2, "at your own pace", "自分のペースで", listOf("Learn a little, at your own pace."))
                ) else AnkiRepository(contentResolver).cards(deck, front, back)
                val audioFiles = indexAudioFolder(this, media)
                handler.post {
                    if (alive && generation == thisGeneration) {
                        if (loaded.isEmpty()) { showError(R.string.no_cards); return@post }
                        cards = loaded; files = audioFiles; bag = ShuffleBag(cards.size)
                        cycle = StudyCycle(interval * 1000L, SystemClock.elapsedRealtime())
                        nextCard(); handler.postDelayed(tick, 1000)
                    }
                }
            } catch (_: Exception) { handler.post { if (alive && generation == thisGeneration) showError(R.string.error) } }
        }
    }
    private fun nextCard() {
        val next = bag?.next() ?: return
        audio.stop(); current = cards[next]; cycle.advance(SystemClock.elapsedRealtime()); showCard()
    }
    private fun showCard() {
        val card = current ?: return
        if (cycle.hidden) manager.notify(NOTIFICATION_ID, notification(getString(R.string.session), getString(R.string.hidden), true))
        else manager.notify(NOTIFICATION_ID, notification(card.front, listOf(card.back, *card.extras.toTypedArray()).filter { it.isNotBlank() }.joinToString("\n\n"), false))
    }
    private fun action(action: String, request: Int): PendingIntent = PendingIntent.getService(this, request,
        Intent(this, StudyService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(title: String, body: String, hidden: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicVersion = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.session)).setContentText(getString(R.string.notice)).build()
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(180)).setContentText(body.take(1000))
            .setStyle(Notification.BigTextStyle().bigText(body.take(3500)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setSubText("Anki Popup · ${interval}s").setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .apply {
                if (!hidden) addAction(Notification.Action.Builder(null, getString(R.string.audio), action(AUDIO, 1)).build())
                addAction(Notification.Action.Builder(null, getString(if (hidden) R.string.next else R.string.hide), action(if (hidden) NEXT else HIDE, 2)).build())
                addAction(Notification.Action.Builder(null, getString(R.string.stop), action(STOP, 3)).build())
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }.build()
    }
    private fun showError(message: Int) {
        val notice = notification(getString(R.string.app_name), getString(message), true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager.notify(NOTIFICATION_ID + 1, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(message)).setStyle(Notification.BigTextStyle().bigText(getString(message)))
            .setContentIntent(notice.contentIntent).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
        stopSelf()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        alive = false; running = false; generation++
        handler.removeCallbacksAndMessages(null); worker.shutdownNow(); audio.close()
        stopForeground(STOP_FOREGROUND_REMOVE); manager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
