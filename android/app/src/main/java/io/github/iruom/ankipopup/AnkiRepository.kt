package io.github.iruom.ankipopup

import android.content.ContentResolver
import android.net.Uri
import android.text.Html

class AnkiRepository(private val resolver: ContentResolver) {
    companion object {
        const val AUTHORITY = "com.ichi2.anki.flashcards"
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    }
    private fun uri(path: String) = Uri.parse("content://$AUTHORITY/$path")
    fun decks(): List<Deck> {
        val result = mutableListOf<Deck>()
        resolver.query(uri("decks"), arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) result.add(Deck(c.getLong(0), c.getString(1)))
        } ?: throw IllegalStateException("AnkiDroid returned no decks")
        return result.sortedBy { it.name.lowercase() }
    }
    fun cards(deckName: String, frontName: String, backName: String): List<StudyCard> {
        val models = mutableMapOf<Long, List<String>>()
        val result = mutableListOf<StudyCard>()
        resolver.query(uri("notes"), arrayOf("_id", "mid", "flds"), deckSearch(deckName), null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val model = c.getLong(1)
                val values = c.getString(2).split('\u001f')
                val names = models.getOrPut(model) {
                    resolver.query(uri("models/$model"), arrayOf("field_names"), null, null, null)?.use { m ->
                        if (m.moveToFirst()) m.getString(0).split('\u001f') else emptyList()
                    } ?: emptyList()
                }
                val front = fieldIndex(names, frontName, true) ?: continue
                val back = fieldIndex(names, backName, false) ?: continue
                if (front !in values.indices || back !in values.indices) continue
                val extraNames = setOf("example", "example sentence", "translation", "notes", "extra", "sentence", "例文英語", "例文日本語", "メモ")
                val extras = values.mapIndexedNotNull { i, value ->
                    if (i != front && i != back && names.getOrNull(i)?.lowercase() in extraNames) plainText(value).takeIf { it.isNotBlank() } else null
                }
                result.add(StudyCard(id, plainText(values[front]), plainText(values[back]), extras, soundNames(values[front] + values[back])))
            }
        }
        return result.distinctBy { it.id }.filter { it.front.isNotBlank() || it.back.isNotBlank() }
    }
}

fun plainText(raw: String): String {
    val clean = raw.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        .replace(Regex("\\[sound:[^]]+\\]"), "")
        .replace(Regex("(?i)<br\\s*/?>|</(?:div|p|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]*>"), "").replace("\n", "<br>")
    return Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
