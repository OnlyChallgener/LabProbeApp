package com.labprobe.app.feature.assistant

import org.junit.Assert.*
import org.junit.Test

class AiNotificationPolicyTest {
    private fun notice(id: Int) = AiNotification(id, "alert", "Notice $id", "Exact content $id")

    @Test fun startupDrainsAllHistoricalPagesSilently() {
        val first = planAiNotificationBatch(0, true, listOf(notice(1), notice(2)))
        assertEquals(2, first.cursor)
        assertTrue(first.baselinePending)
        assertNull(first.latest)
        val second = planAiNotificationBatch(first.cursor, first.baselinePending, listOf(notice(3)))
        assertTrue(second.baselinePending)
        assertNull(second.latest)
        val caughtUp = planAiNotificationBatch(second.cursor, second.baselinePending, emptyList())
        assertEquals(3, caughtUp.cursor)
        assertFalse(caughtUp.baselinePending)
        assertNull(caughtUp.latest)
    }

    @Test fun liveBatchNotifiesOnlyNewestWithExactContent() {
        val batch = planAiNotificationBatch(4, false, listOf(notice(7), notice(5), notice(7), notice(6)))
        assertEquals(7, batch.cursor)
        assertEquals(notice(7), batch.latest)
    }

    @Test fun cursorNeverRegressesAndDuplicatesAreSilent() {
        val batch = planAiNotificationBatch(8, false, listOf(notice(7), notice(8), notice(0)))
        assertEquals(8, batch.cursor)
        assertNull(batch.latest)
    }

    @Test fun restartWithExistingCursorDoesNotNotifyMissedMessages() {
        val batch = planAiNotificationBatch(8, true, listOf(notice(9)))
        assertEquals(9, batch.cursor)
        assertNull(batch.latest)
    }
}
