package io.github.iruom.ankipopup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*
import android.widget.*

/** A small, non-focusable window: typing and touches outside the card stay with the app below. */
// Drag positions use physical screen coordinates; the handle calls performClick on release.
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class FloatingCard(private val context: Context, private val action: (String) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(6), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(Color.rgb(24, 43, 39)); cornerRadius = dp(18).toFloat() }
        elevation = dp(8).toFloat()
    }
    private val title = TextView(context).apply { setTextColor(Color.WHITE); textSize = 20f; setTypeface(null, Typeface.BOLD) }
    private val body = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(6), 0, dp(6)) }
    private val params = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = 0.8f
            x = prefs.getInt("overlay_x", dp(12)); y = prefs.getInt("overlay_y", dp(100))
        }
    private var attached = false
    private var shownCard: StudyCard? = null
    init {
        val handle = TextView(context).apply { setText(R.string.drag_card); setTextColor(Color.rgb(179, 223, 205)); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }
        root.addView(handle)
        root.addView(title)
        root.addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(-1, dp(110)))
        val row = LinearLayout(context)
        for ((label, command) in listOf(R.string.audio to StudyService.AUDIO, R.string.next to StudyService.NEXT, R.string.hide to StudyService.HIDE, R.string.stop to StudyService.STOP)) {
            row.addView(Button(context).apply {
                setText(label); isAllCaps = false; textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0); setOnClickListener { action(command) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        root.addView(row)
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt(); params.y = startY + (event.rawY - downY).toInt()
                    clamp(); if (attached) manager.updateViewLayout(root, params); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply(); view.performClick(); true
                }
                else -> false
            }
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun clamp() {
        val display = context.resources.displayMetrics
        params.width = dp(300).coerceAtMost(display.widthPixels)
        params.x = params.x.coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (display.heightPixels - (root.height.takeIf { it > 0 } ?: dp(250))).coerceAtLeast(0))
    }
    fun show(card: StudyCard) {
        if (!Settings.canDrawOverlays(context)) { hide(); return }
        if (attached && shownCard == card) return
        shownCard = card
        title.text = card.front
        body.text = listOf(card.back, *card.extras.toTypedArray()).filter { it.isNotBlank() }.joinToString("\n\n")
        clamp()
        if (!attached) { manager.addView(root, params); attached = true }
        else manager.updateViewLayout(root, params)
    }
    fun hide() { if (attached) { manager.removeViewImmediate(root); attached = false } }
}
