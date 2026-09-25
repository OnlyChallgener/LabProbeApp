package com.labprobe.app.feature.assistant

import org.junit.Assert.*
import org.junit.Test

class AiNotificationPolicyTest {
    private fun notice(id: Int) = AiNotification(id, "alert", "Notice $id", "Exact content $id")

    @Test fun startupImportsAllHistoricalPagesWithoutSystemBanners() {
        val first = planAiNotificationBatch(0, true, listOf(notice(1), notice(2)))
        assertEquals(2, first.cursor)
        assertTrue(first.baselinePending)
        assertEquals(listOf(notice(1), notice(2)), first.rows)
        assertTrue(first.alertRows.isEmpty())

        val second = planAiNotificationBatch(first.cursor, first.baselinePending, listOf(notice(3)))
        assertTrue(second.baselinePending)
        assertEquals(listOf(notice(3)), second.rows)
        assertTrue(second.alertRows.isEmpty())

        val caughtUp = planAiNotificationBatch(second.cursor, second.baselinePending, emptyList())
        assertEquals(3, caughtUp.cursor)
        assertFalse(caughtUp.baselinePending)
        assertTrue(caughtUp.rows.isEmpty())
    }

    @Test fun liveBatchSavesAndNotifiesEveryDistinctRowInOrder() {
        val batch = planAiNotificationBatch(4, false, listOf(notice(7), notice(5), notice(7), notice(6)))
        assertEquals(7, batch.cursor)
        assertEquals(listOf(notice(5), notice(6), notice(7)), batch.rows)
        assertEquals(batch.rows, batch.alertRows)
    }

    @Test fun cursorNeverRegressesAndDuplicatesAreSilent() {
        val batch = planAiNotificationBatch(8, false, listOf(notice(7), notice(8), notice(0)))
        assertEquals(8, batch.cursor)
        assertTrue(batch.rows.isEmpty())
        assertTrue(batch.alertRows.isEmpty())
    }

    @Test fun restartWithExistingCursorImportsMissedMessagesQuietly() {
        val batch = planAiNotificationBatch(8, true, listOf(notice(9)))
        assertEquals(9, batch.cursor)
        assertEquals(listOf(notice(9)), batch.rows)
        assertTrue(batch.alertRows.isEmpty())
    }
}
