package io.github.iruom.ankipopup

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class StudyCoreTest {
    @Test fun hidingKeepsOriginalDeadline() {
        val cycle = StudyCycle(30_000, 100_000)
        cycle.hide()
        assertTrue(cycle.hidden)
        assertEquals(130_000, cycle.deadline)
        assertFalse(cycle.due(129_999))
        assertTrue(cycle.due(130_000))
        cycle.advance(130_000)
        assertFalse(cycle.hidden)
        assertEquals(160_000, cycle.deadline)
    }
    @Test fun shuffleHasNoRepeatsAndNoRepeatAtBoundary() {
        val bag = ShuffleBag(4, Random(42))
        val first = List(4) { bag.next() }
        val second = List(4) { bag.next() }
        assertEquals(4, first.toSet().size)
        assertEquals(4, second.toSet().size)
        assertNotEquals(first.last(), second.first())
    }
    @Test fun oneCardDeckDoesNotFail() { val bag = ShuffleBag(1); repeat(10) { assertEquals(0, bag.next()) } }
    @Test fun namesAndExplicitFieldsAreMapped() {
        val names = listOf("番号", "表面", "裏面")
        assertEquals(1, fieldIndex(names, "", true))
        assertEquals(2, fieldIndex(names, "", false))
        assertEquals(0, fieldIndex(names, "番号", true))
        assertNull(fieldIndex(names, "missing", true))
        assertNull(fieldIndex(listOf("Front"), "", false))
    }
    @Test fun deckNamesAreQuotedWithoutSearchInjection() {
        assertEquals("", deckSearch(""))
        assertEquals("deck:\"My \\\"deck\\\"\\\\x\"", deckSearch("My \"deck\"\\x"))
    }
    @Test fun audioNamesAreDeduplicatedAndCannotEscapeFolder() {
        assertEquals(listOf("hello.mp3"), soundNames("[sound:hello.mp3][sound:hello.mp3][sound:../secret.mp3][sound:C:\\bad.mp3]"))
    }
}
