package io.github.iruom.ankipopup

import kotlin.random.Random

data class StudyCard(val id: Long, val front: String, val back: String, val extras: List<String> = emptyList(), val audioNames: List<String> = emptyList())
data class Deck(val id: Long, val name: String)

class StudyCycle(private val intervalMs: Long, now: Long) {
    var deadline: Long = now + intervalMs; private set
    var hidden: Boolean = false; private set
    fun hide() { hidden = true }
    fun due(now: Long): Boolean = now >= deadline
    fun advance(now: Long) { hidden = false; deadline = now + intervalMs }
}

class ShuffleBag(private val size: Int, private val random: Random = Random.Default) {
    private val remaining = mutableListOf<Int>()
    private var last: Int? = null
    init { require(size > 0) }
    fun next(): Int {
        if (remaining.isEmpty()) {
            remaining.addAll((0 until size).shuffled(random))
            if (size > 1 && remaining.last() == last) {
                val first = remaining[0]; remaining[0] = remaining.last(); remaining[remaining.lastIndex] = first
            }
        }
        return remaining.removeAt(remaining.lastIndex).also { last = it }
    }
}

fun deckSearch(name: String): String = if (name.isEmpty()) "" else "deck:\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\""
fun soundNames(raw: String): List<String> = Regex("\\[sound:([^]]+)\\]").findAll(raw).map { it.groupValues[1] }
    .filter { it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".." }.distinct().toList()
fun fieldIndex(names: List<String>, explicit: String, front: Boolean): Int? {
    if (explicit.isNotBlank()) return names.indexOf(explicit).takeIf { it >= 0 }
    val aliases = if (front) setOf("front", "question", "expression", "word", "表面", "単語") else setOf("back", "answer", "meaning", "definition", "裏面", "意味")
    return names.indexOfFirst { it.lowercase() in aliases }.takeIf { it >= 0 } ?: (if (front) 0 else 1).takeIf { it < names.size }
}
