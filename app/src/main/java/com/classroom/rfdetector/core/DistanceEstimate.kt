package com.classroom.rfdetector.core

import java.util.Locale

/** 실내 환경별 경로손실 지수. 자동 추정은 어려워 사용자가 고르는 값이다. */
enum class PathLossEnvironment(val label: String, val n: Double) {
    OPEN("개방된 교실", 2.0),
    CLASSROOM("일반 교실", 2.7),
    CROWDED("사람·벽 많음", 3.5),
}

data class DistanceEstimate(
    val meters: Double,
    val lowerMeters: Double = meters,
    val upperMeters: Double = meters,
) {
    val label: String get() = when {
        upperMeters - lowerMeters < 0.2 -> "약 ${formatMeters(meters)} m"
        else -> "약 ${formatMeters(lowerMeters)}~${formatMeters(upperMeters)} m"
    }
}

/**
 * 로그 거리 모델로 거리를 어림한다. 세기 분산과 경로손실 지수의 불확실성을 함께 반영해
 * 하나의 숫자 대신 범위를 돌려준다.
 */
fun estimateDistance(
    rssi: Double?,
    referenceRssiAt1m: Double? = null,
    spreadDb: Double? = null,
    pathN: Double = PathLossEnvironment.CLASSROOM.n,
): DistanceEstimate? {
    if (rssi == null || !rssi.isFinite() || rssi !in -127.0..-1.0) return null
    val calibrated = referenceRssiAt1m?.takeIf { it.isFinite() && it in -100.0..20.0 }
    val reference = calibrated ?: RssiFilter.DEFAULT_TX_DBM
    val n = pathN.coerceIn(1.2, 6.0)
    val meters = RssiFilter.distanceM(rssi, reference, n)
    val sigma = spreadDb?.takeIf { it.isFinite() }?.coerceIn(0.5, 12.0) ?: 3.0
    val lower = RssiFilter.distanceM(rssi + 2 * sigma, reference, n + 0.5)
    val upper = RssiFilter.distanceM(rssi - 2 * sigma, reference, (n - 0.5).coerceAtLeast(1.0))
    return DistanceEstimate(
        meters = meters,
        lowerMeters = minOf(lower, meters),
        upperMeters = maxOf(upper, meters),
    )
}

private fun formatMeters(value: Double): String = when {
    value < 0.1 -> "<0.1"
    value > 100 -> ">100"
    value >= 10 -> String.format(Locale.getDefault(), "%.0f", value)
    else -> String.format(Locale.getDefault(), "%.1f", value)
}
