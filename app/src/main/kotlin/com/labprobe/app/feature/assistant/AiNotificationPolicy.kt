package com.labprobe.app.feature.assistant

/** Seen cursor is independent of notification permission/delivery. Never replay a backlog. */
internal data class AiNotificationBatch(
    val cursor: Int,
    val baselinePending: Boolean,
    val latest: AiNotification?,
)

internal fun planAiNotificationBatch(
    previousCursor: Int,
    baselinePending: Boolean,
    rows: List<AiNotification>,
): AiNotificationBatch {
    val cursor = previousCursor.coerceAtLeast(0)
    val fresh = rows.filter { it.id > cursor }.distinctBy { it.id }
    val newest = fresh.maxByOrNull { it.id }
    // Drain every historical page quietly; one non-empty page is not proof we caught up.
    return AiNotificationBatch(
        cursor = newest?.id ?: cursor,
        baselinePending = baselinePending && fresh.isNotEmpty(),
        latest = newest.takeUnless { baselinePending },
    )
}
