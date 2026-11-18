package com.classroom.rfdetector.core

data class ObservationPeriod(val firstSeenMs: Long, val lastSeenMs: Long, val count: Int)

/**
 * 수신 구간을 나누는 공백 기준.
 * 클래식 블루투스는 탐색을 표준 2~3분, 최대 감지 1분에 한 번만 돌리므로 훨씬 긴 공백을 허용한다.
 */
fun ScanSource.historyGapMs(): Long = when (this) {
    ScanSource.WIFI -> 90_000L
    ScanSource.CLASSIC -> 180_000L
    ScanSource.BLE -> 15_000L
}

fun signalPresence(source: ScanSource, lastSeenMs: Long, nowMs: Long, extended: Boolean = false): SignalPresence {
    val age = (nowMs - lastSeenMs).coerceAtLeast(0)
    val (recentMs, quietMs) = presenceWindows(source, extended)
    return when {
        age <= recentMs -> SignalPresence.RECENT
        age <= quietMs -> SignalPresence.QUIET
        else -> SignalPresence.HISTORY
    }
}

/**
 * [extended]는 최대 감지 모드다. 먼 기기는 패킷 사이 간격이 길어서
 * 표준 창에서는 금방 "사라짐"이 된다. 최대 모드에서는 그 간격을 견디게 한다.
 */
private fun presenceWindows(source: ScanSource, extended: Boolean): Pair<Long, Long> = if (extended) when (source) {
    ScanSource.WIFI -> 90_000L to 180_000L
    ScanSource.CLASSIC -> 60_000L to 300_000L
    ScanSource.BLE -> 15_000L to 60_000L
} else when (source) {
    ScanSource.WIFI -> 45_000L to source.historyGapMs()
    ScanSource.CLASSIC -> 30_000L to source.historyGapMs()
    ScanSource.BLE -> 5_000L to source.historyGapMs()
}

/** Merge late samples too: a delayed packet may bridge two apparent gaps. */
fun addToPeriods(periods: List<ObservationPeriod>, timeMs: Long, gapMs: Long): List<ObservationPeriod> {
    val sorted = (periods + ObservationPeriod(timeMs, timeMs, 1)).sortedBy { it.firstSeenMs }
    val merged = mutableListOf<ObservationPeriod>()
    for (period in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && period.firstSeenMs - previous.lastSeenMs <= gapMs) {
            merged[merged.lastIndex] = ObservationPeriod(previous.firstSeenMs, maxOf(previous.lastSeenMs, period.lastSeenMs), previous.count + period.count)
        } else merged.add(period)
    }
    return merged
}
