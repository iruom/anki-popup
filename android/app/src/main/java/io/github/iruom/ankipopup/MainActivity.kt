package io.github.iruom.ankipopup

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var floating: Switch
    private lateinit var deck: Spinner
    private lateinit var interval: EditText
    private lateinit var front: EditText
    private lateinit var back: EditText
    private lateinit var language: EditText
    private lateinit var status: TextView
    private lateinit var mediaStatus: TextView
    private var decks = listOf(Deck(0, ""))
    private var waitingDemo: Boolean? = null
    private var destroyed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(26), dp(24), dp(32)) }
        scroll.addView(column); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            column.setPadding(dp(24), dp(26) + insets.systemWindowInsetTop, dp(24), dp(32) + insets.systemWindowInsetBottom)
            insets
        }
        fun text(value: String, size: Float = 15f): TextView = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(8), 0, dp(8)); column.addView(this) }
        fun button(label: Int, action: () -> Unit) = Button(this).apply { setText(label); isAllCaps = false; setOnClickListener { action() }; column.addView(this, LinearLayout.LayoutParams(-1, -2)) }
        fun field(label: Int, initial: String, numeric: Boolean = false): EditText {
            text(getString(label), 13f)
            return EditText(this).apply { setText(initial); setSingleLine(); inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT; column.addView(this, LinearLayout.LayoutParams(-1, -2)) }
        }
        text("Anki Popup", 30f); text(getString(R.string.tagline), 19f); text(getString(R.string.intro))
        button(R.string.connect) { connect() }
        text(getString(R.string.deck), 13f)
        deck = Spinner(this).also { column.addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
        setDecks(emptyList())
        interval = field(R.string.interval, prefs.getInt("interval", 30).toString(), true)
        front = field(R.string.front_field, prefs.getString("front", "") ?: "")
        back = field(R.string.back_field, prefs.getString("back", "") ?: "")
        language = field(R.string.language, prefs.getString("language", "en-US") ?: "en-US")
        floating = Switch(this).apply { setText(R.string.floating_mode); isChecked = prefs.getBoolean("overlay", true); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("overlay", checked).apply() } }
        column.addView(floating)
        button(R.string.start) { requestStart(false) }
        val row = LinearLayout(this)
        for ((label, action) in listOf(R.string.next to StudyService.NEXT, R.string.hide to StudyService.HIDE, R.string.stop to StudyService.STOP)) {
            row.addView(Button(this).apply { setText(label); isAllCaps = false; setOnClickListener {
                if (action == StudyService.STOP) {
                    val stopIntent = Intent(this@MainActivity, StudyService::class.java)
                    stopService(stopIntent); status.setText(R.string.stopped)
                }
                else if (StudyService.running) startService(Intent(this@MainActivity, StudyService::class.java).setAction(action))
            } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        column.addView(row)
        button(R.string.demo) { requestStart(true) }
        button(R.string.media) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 30) }
        button(R.string.clear_media) { clearMedia(); updateMedia() }
        mediaStatus = text(""); updateMedia()
        status = text(getString(if (StudyService.running) R.string.running else R.string.ready))
        text(getString(R.string.limits), 13f); text(getString(R.string.privacy), 13f)
        if (checkSelfPermission(AnkiRepository.PERMISSION) == PackageManager.PERMISSION_GRANTED) loadDecks()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun setDecks(items: List<Deck>) {
        decks = listOf(Deck(0, "")) + items
        deck.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, decks.map { if (it.id == 0L) getString(R.string.all_decks) else it.name })
        deck.setSelection(decks.indexOfFirst { it.name == prefs.getString("deck", "") }.coerceAtLeast(0))
    }
    private fun connect() {
        if (packageManager.resolveContentProvider(AnkiRepository.AUTHORITY, 0) == null) { status.setText(R.string.api_missing); return }
        if (checkSelfPermission(AnkiRepository.PERMISSION) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(AnkiRepository.PERMISSION), 10)
        else loadDecks()
    }
    private fun loadDecks() {
        status.setText(R.string.working)
        worker.execute {
            try {
                val items = AnkiRepository(contentResolver).decks()
                runOnUiThread { if (!destroyed) { setDecks(items); status.setText(R.string.ready) } }
            } catch (_: Exception) { runOnUiThread { if (!destroyed) status.setText(R.string.api_missing) } }
        }
    }
    private fun requestStart(demo: Boolean) {
        if (floating.isChecked && !Settings.canDrawOverlays(this)) {
            status.setText(R.string.overlay_required)
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { status.setText(R.string.overlay_required) }
            return
        }
        if (!demo && checkSelfPermission(AnkiRepository.PERMISSION) != PackageManager.PERMISSION_GRANTED) { connect(); return }
        val seconds = interval.text.toString().toIntOrNull()
        if (seconds == null || seconds !in 5..3600) { status.setText(R.string.bad_interval); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            waitingDemo = demo; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20); return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { status.setText(R.string.notification_denied); return }
        prefs.edit().putString("deck", decks.getOrElse(deck.selectedItemPosition) { Deck(0, "") }.name)
            .putInt("interval", seconds).putString("front", front.text.toString().trim()).putString("back", back.text.toString().trim())
            .putString("language", language.text.toString().trim().ifBlank { "en-US" }).apply()
        try {
            startForegroundService(Intent(this, StudyService::class.java).setAction(StudyService.START).putExtra("demo", demo))
            status.setText(R.string.running)
        } catch (_: Exception) { status.setText(R.string.error) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allowed = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (requestCode == 10) { if (allowed) loadDecks() else status.setText(R.string.api_denied) }
        if (requestCode == 20) { val demo = waitingDemo; waitingDemo = null; if (allowed && demo != null) requestStart(demo) else status.setText(R.string.notification_denied) }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                clearMedia()
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("media", uri.toString()).apply(); updateMedia()
            } catch (_: SecurityException) { status.setText(R.string.error) }
        }
    }
    private fun clearMedia() {
        prefs.getString("media", null)?.let { try { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
        prefs.edit().remove("media").apply()
    }
    private fun updateMedia() { mediaStatus.setText(if (prefs.contains("media")) R.string.media_set else R.string.media_unset) }
    override fun onDestroy() { destroyed = true; worker.shutdownNow(); super.onDestroy() }
}
