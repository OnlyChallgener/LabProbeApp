package com.labprobe.app.feature.assistant

/** The cursor advances only after every row in this batch is saved locally. */
internal data class AiNotificationBatch(
    val cursor: Int,
    val baselinePending: Boolean,
    val rows: List<AiNotification>,
    val alertRows: List<AiNotification>,
)

internal fun planAiNotificationBatch(
    previousCursor: Int,
    baselinePending: Boolean,
    rows: List<AiNotification>,
): AiNotificationBatch {
    val cursor = previousCursor.coerceAtLeast(0)
    val fresh = rows.filter { it.id > cursor }.distinctBy { it.id }.sortedBy { it.id }
    // Drain historical pages into the inbox, without replaying old system banners.
    return AiNotificationBatch(
        cursor = fresh.lastOrNull()?.id ?: cursor,
        baselinePending = baselinePending && fresh.isNotEmpty(),
        rows = fresh,
        alertRows = if (baselinePending) emptyList() else fresh,
    )
}
