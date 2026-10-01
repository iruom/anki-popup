package io.github.iruom.ankipopup

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.util.function.Consumer
import kotlin.math.abs

/** Compact gesture surface; window focus and touches outside its bounds stay with the app below. */
// Saved drag positions are physical screen coordinates, including in RTL locales.
// GestureDetector routes taps through performClick, including accessibility actions.
@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class FloatingCard(private val context: Context, private val action: (String) -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val dialog = Dialog(context, R.style.FloatingCardTheme)
    private val window = dialog.window!!
    private val background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(35, 255, 255, 255))
    }
    private val easing = DecelerateInterpolator(1.5f)
    private val root = object : LinearLayout(context) {
        override fun onInterceptTouchEvent(event: MotionEvent) = true
        override fun onTouchEvent(event: MotionEvent): Boolean = touch(event)
        override fun performClick(): Boolean { super.performClick(); pulse(); action(StudyService.AUDIO); return true }
    }.apply {
        tag = "floating_card"; orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(11), dp(14), dp(11)); isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 17f; setTypeface(null, Typeface.BOLD)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val meaning = TextView(context).apply {
        setTextColor(Color.rgb(218, 218, 218)); textSize = 13f; maxLines = 2
        ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
    }
    private val examples = TextView(context).apply {
        setTextColor(Color.rgb(205, 205, 205)); textSize = 12f; maxLines = 7
        ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0); visibility = View.GONE
    }
    private val params = WindowManager.LayoutParams().apply {
        width = dp(220); height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        gravity = Gravity.TOP or Gravity.LEFT
        x = prefs.getInt("overlay_x", dp(12)); y = prefs.getInt("overlay_y", dp(100))
        dimAmount = 0f; windowAnimations = 0; format = PixelFormat.TRANSLUCENT
    }
    private var attached = false
    private var shownCard: StudyCard? = null
    private var expanded = false
    private var dragging = false
    private var swiping = false
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private val blurListener = Consumer<Boolean> { enabled -> updateGlass(enabled) }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean { root.performClick(); return true }
        override fun onLongPress(event: MotionEvent) {
            dragging = true; root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            root.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).start()
        }
    })
    init {
        root.addView(title); root.addView(meaning); root.addView(examples)
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.card_next, context.getString(R.string.next)))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.card_examples, context.getString(R.string.examples_action)))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.card_hide, context.getString(R.string.hide)))
            }
            override fun performAccessibilityAction(host: View, id: Int, args: android.os.Bundle?): Boolean = when (id) {
                R.id.card_next -> { next(); true }
                R.id.card_examples -> { toggleExamples(); true }
                R.id.card_hide -> { action(StudyService.HIDE); true }
                else -> super.performAccessibilityAction(host, id, args)
            }
        }
        dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false); dialog.setContentView(root)
        window.setBackgroundDrawable(background); window.attributes = params
        updateGlass(false)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun updateGlass(enabled: Boolean) {
        background.setColor(Color.argb(if (enabled) 175 else 245, 24, 24, 24))
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (enabled) dp(24) else 0)
    }
    private fun clamp() {
        val display = context.resources.displayMetrics
        params.width = dp(220).coerceAtMost((display.widthPixels - dp(16)).coerceAtLeast(1))
        root.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(display.heightPixels, View.MeasureSpec.AT_MOST))
        params.x = params.x.coerceIn(0, (display.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (display.heightPixels - root.measuredHeight).coerceAtLeast(0))
    }
    private fun layout() { clamp(); window.attributes = params }
    fun show(card: StudyCard) {
        if (!Settings.canDrawOverlays(context)) { hide(); return }
        if (attached && shownCard == card) return
        root.animate().cancel(); shownCard = card; expanded = false
        title.text = card.front; meaning.text = card.back
        examples.text = card.extras.joinToString("\n\n").ifBlank { context.getString(R.string.no_examples) }
        examples.visibility = View.GONE
        root.contentDescription = "${card.front}. ${card.back}. ${context.getString(R.string.gesture_help)}"
        layout()
        if (!attached) {
            dialog.show(); attached = true
            window.attributes = params
            if (Build.VERSION.SDK_INT >= 31) manager.addCrossWindowBlurEnabledListener(blurListener)
        }
        root.translationX = -dp(12).toFloat(); root.alpha = 0f; root.scaleX = .97f; root.scaleY = .97f
        settle(160)
    }
    private fun settle(duration: Long = 140) {
        root.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(easing).setDuration(duration).withEndAction(null).start()
    }
    private fun pulse() {
        root.animate().cancel(); root.scaleX = .97f; root.scaleY = .97f; settle(130)
    }
    private fun next() {
        if (!attached) return
        root.animate().cancel()
        root.animate().translationX(dp(44).toFloat()).alpha(0f).setDuration(80).setInterpolator(easing)
            .withEndAction { if (attached) { root.alpha = 1f; root.translationX = 0f; action(StudyService.NEXT) } }.start()
    }
    private fun toggleExamples() {
        if (!attached) return
        expanded = !expanded
        // Fade the newly revealed content without resizing the window every animation frame.
        examples.visibility = if (expanded) View.VISIBLE else View.GONE
        layout()
        if (expanded) { examples.alpha = 0f; examples.translationY = -dp(6).toFloat()
            examples.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(easing).start() }
        settle()
    }
    private fun touch(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) { cancelTouch(); return true }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            root.animate().cancel(); root.translationX = 0f; root.translationY = 0f; root.alpha = 1f
            downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
            dragging = false; swiping = false
        }
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (dragging) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); layout() }
                else if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                    swiping = true
                    root.translationX = if (abs(dx) > abs(dy)) dx.coerceIn(-dp(40).toFloat(), dp(64).toFloat()) * .5f else 0f
                    root.translationY = if (abs(dy) >= abs(dx)) dy.coerceIn(-dp(24).toFloat(), dp(24).toFloat()) * .3f else 0f
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                when {
                    dragging -> { prefs.edit().putInt("overlay_x", params.x).putInt("overlay_y", params.y).apply(); settle() }
                    swiping && dx > dp(36) && dx > abs(dy) -> next()
                    swiping && dy > dp(30) && dy > abs(dx) -> toggleExamples()
                    swiping && dy < -dp(30) && abs(dy) > abs(dx) -> action(StudyService.HIDE)
                    else -> settle()
                }
                dragging = false; swiping = false
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }
    private fun cancelTouch() {
        val cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel); cancel.recycle(); dragging = false; swiping = false; settle()
    }
    fun hide() {
        root.animate().cancel(); examples.animate().cancel()
        cancelTouch()
        if (attached) {
            if (Build.VERSION.SDK_INT >= 31) manager.removeCrossWindowBlurEnabledListener(blurListener)
            dialog.dismiss(); attached = false
        }
    }
}
