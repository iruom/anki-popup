package io.github.iruom.ankipopup

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.util.Locale

fun indexAudioFolder(context: Context, tree: String?): Map<String, Uri> {
    if (tree == null) return emptyMap()
    return try {
        val root = Uri.parse(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        val files = mutableMapOf<String, Uri>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c ->
            while (c.moveToNext()) files[c.getString(0)] = DocumentsContract.buildDocumentUriUsingTree(root, c.getString(1))
        }
        files
    } catch (_: Exception) { emptyMap() }
}

class LocalAudio(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS || it == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }.build()
    private val tts: TextToSpeech
    private var ready = false
    private var initialized = false
    private var player: MediaPlayer? = null
    private var pendingSpeech: Pair<String, String>? = null
    private val queue = ArrayDeque<Uri>()
    private var fallback: Pair<String, String>? = null
    private var closed = false
    init {
        tts = TextToSpeech(context) { status -> handler.post {
            if (!closed) { initialized = true; ready = status == TextToSpeech.SUCCESS; pendingSpeech?.let { pendingSpeech = null; speak(it.first, it.second) } }
        } }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { manager.abandonAudioFocusRequest(focus) }
            @Deprecated("Required by Android") override fun onError(utteranceId: String?) { manager.abandonAudioFocusRequest(focus) }
        })
    }
    fun play(card: StudyCard, files: Map<String, Uri>, language: String) {
        stop()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        fallback = card.front to language
        queue.addAll(card.audioNames.mapNotNull { files[it] })
        if (queue.isEmpty()) speak(card.front, language) else playNext()
    }
    private fun playNext() {
        if (closed) return
        if (queue.isEmpty()) { manager.abandonAudioFocusRequest(focus); return }
        val uri = queue.removeFirst()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context, uri)
                setOnPreparedListener { if (it === player && !closed) it.start() }
                setOnCompletionListener { it.release(); player = null; playNext() }
                setOnErrorListener { p, _, _ -> p.release(); player = null; queue.clear(); fallback?.let { speak(it.first, it.second) }; true }
                prepareAsync()
            }
        } catch (_: Exception) { player?.release(); player = null; queue.clear(); fallback?.let { speak(it.first, it.second) } }
    }
    private fun speak(text: String, language: String) {
        if (!initialized) { pendingSpeech = text to language; return }
        if (!ready) {
            manager.abandonAudioFocusRequest(focus)
            Toast.makeText(context, R.string.speech_unavailable, Toast.LENGTH_LONG).show(); return
        }
        val locale = Locale.forLanguageTag(language)
        val voice = tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.toLanguageTag() == locale.toLanguageTag() }
            ?: tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
        if (voice == null) {
            manager.abandonAudioFocusRequest(focus)
            Toast.makeText(context, R.string.speech_unavailable, Toast.LENGTH_LONG).show(); return
        }
        tts.voice = voice
        tts.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH, null, "card")
    }
    fun stop() {
        pendingSpeech = null; queue.clear(); fallback = null
        player?.release(); player = null
        tts.stop(); manager.abandonAudioFocusRequest(focus)
    }
    fun close() { stop(); closed = true; tts.shutdown() }
}
