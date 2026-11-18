package com.classroom.rfdetector.core

/**
 * 감시 중 새로 잡힌 묶음 하나의 기록. 판정이 아니라 "언제 얼마나 세게 잡혔는지"만 남긴다.
 * 앱 안에서만 유지하고 디스크에 저장하지 않는다.
 */
data class DetectionEvent(
    val groupKey: String,
    val name: String,
    val kind: String?,
    val source: ScanSource,
    val address: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val peakRssi: Int?,
    val confidence: SignalConfidence,
    val observations: Int,
) {
    val durationSeconds: Long get() = ((lastSeenMs - firstSeenMs).coerceAtLeast(0)) / 1000
}
