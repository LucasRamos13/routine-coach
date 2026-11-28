package dev.routinecoach.app.reminders

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RelayTopicTest {
    @Test
    fun generatedTopicsAreStrongAndUnique() {
        val a = Relay.randomTopic()
        assertTrue(Relay.isStrongTopic(a))
        assertNotEquals(a, Relay.randomTopic())
    }

    @Test
    fun weakTopicsAreRejected() {
        assertFalse(Relay.isStrongTopic("treino"))
        assertFalse(Relay.isStrongTopic("a".repeat(23)))
        assertFalse(Relay.isStrongTopic("rc-" + "a".repeat(30) + "/x"))
        assertFailsWith<IllegalArgumentException> { Relay("https://ntfy.sh", "treino") }
    }
}
