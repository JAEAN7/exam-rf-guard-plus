package com.classroom.rfdetector.core

import java.util.Locale

/** 관측 출처 */
enum class ScanSource { BLE, WIFI, CLASSIC }

fun radioKey(source: ScanSource, address: String): String = "${source.name}:${address.uppercase(Locale.ROOT)}"

enum class SignalPresence {
    RECENT,
    QUIET,
    HISTORY,
}

/**
 * 얼마나 오래·자주 잡혔는지로 매기는 신뢰도. 주소 하나가 바뀌는 순간과
 * 멀리서 간헐적으로 잡히는 신호를 구분하기 위한 값이다.
 */
enum class SignalConfidence(val label: String) {
    OBSERVED("관찰 중"),
    LIKELY("가능성 있음"),
    CONFIRMED("확인 필요"),
}

/** pauses·pausedMs: 앱을 벗어나 수신이 멈췄던 횟수와 총 시간. 그 사이의 신호는 기록되지 않는다. */
data class ScanSession(val id: String, val startedAtMs: Long, val area: String,
    val pauses: Int = 0, val pausedMs: Long = 0)

/** 거리 등급 — RSSI 기반 추정이며 실제 거리 측정이 아니다. */
enum class DistanceClass(val label: String) {
    VERY_CLOSE("바로 옆"),
    CLOSE("가까움"),
    NEAR("중간"),
    FAR("멀다"),
    UNKNOWN("측정 중"),
}

/** 스캐너/시뮬레이터가 올리는 단일 관측값 */
data class RfObservation(
    val key: String,          // BLE MAC 또는 Wi-Fi BSSID
    val source: ScanSource,
    val name: String?,        // 기기명/SSID. null 이면 미지
    val rssi: Int?,           // null 이면 존재만 확인
    val txPower: Int?,        // 보정된 1m 기준 RSSI(dBm). 일반 AD 송신 출력값과 다르다.
    val hidden: Boolean,      // Wi-Fi SSID 숨김 AP
    val timestampMs: Long,
    val manufacturerIds: Set<Int> = emptySet(),
    val hints: Set<SignalHint> = emptySet(),
    val details: Map<String, String> = emptyMap(),
    /** 같은 패킷이 콜백에 두 번 올 때 증거를 두 번 세지 않기 위한 값. */
    val dedupKey: String? = null,
    /** 주소가 바뀌어도 같은 기기를 묶기 위한 광고 지문. */
    val fingerprint: String? = null,
) {
    val trackingKey: String get() = radioKey(source, key)
}

/** UI 로 내보내는 추적 단위 */
data class TrackedDevice(
    val key: String,
    val displayName: String,
    val source: ScanSource,
    val hidden: Boolean,
    val rssiEma: Double?,
    val distanceClass: DistanceClass,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val manufacturerIds: Set<Int> = emptySet(),
    val identity: DeviceIdentity = DeviceIdentity(),
    val presence: SignalPresence = SignalPresence.RECENT,
    val lastSeenAgeSeconds: Long = 0,
    val observationCount: Int = 0,
    val peakRssi: Int? = null,
    val periods: List<ObservationPeriod> = emptyList(),
    val referenceRssiAt1m: Double? = null,
    val hints: Set<SignalHint> = emptySet(),
    /** 기기가 알린 나머지 정보(항목 → 값). 화면 표시용이며 저장하지 않는다. */
    val details: Map<String, String> = emptyMap(),
    /** 관측 증거와 신뢰도. 멀리서 간헐적으로 잡히는 신호를 놓치지 않기 위한 값이다. */
    val confidence: SignalConfidence = SignalConfidence.OBSERVED,
    val evidenceScore: Double = 0.0,
    val packetRatePerMin: Double = 0.0,
    val rssiMedian: Double? = null,
    val rssiP90: Double? = null,
    val rssiSpread: Double? = null,
    val recentPeakRssi: Int? = null,
    val fingerprint: String? = null,
    /** 위치 찾기 화면의 실시간 그래프용 최근 세기(오래된 것부터). */
    val rssiHistory: List<Int> = emptyList(),
    /**
     * 지금은 새 패킷이 없지만, 최근 몇 분 동안 여러 시점에 걸쳐 반복 확인된 신호.
     * 몇 분에 한 번만 잡히는 먼 기기를 목록에서 지우지 않기 위한 값이다.
     */
    val recall: RecallEvidence = RecallEvidence(),
    /**
     * 최근 창을 벗어나 장기 기억만으로 목록에 남아 있는 상태.
     * 지금 잡히는 개수에는 넣지 않는다. 기억을 늘려도 "늘어난 전파"가 헛되이 뜨지 않게 하기 위해서다.
     */
    val keptByRecall: Boolean = false,
) {
    val trackingKey: String get() = radioKey(source, key)
    val isRadioActive: Boolean get() = source != ScanSource.WIFI
    /** 최근 창에는 없지만 장기 기억으로 유지 중인 신호. */
    val isRecallOnly: Boolean get() = keptByRecall
}

const val WEAK_SIGNAL_DBM = -85

/** 약한 신호도 증거가 쌓이면 보여 주되, 경고와 관찰을 구분하기 위한 기준. */
object EvidenceThresholds {
    /** 이 점수를 넘으면 "가능성 있음"으로 본다. 약 3~5패킷/60초에 해당한다. */
    const val LIKELY = 1.5
    /** 이 점수를 넘으면 "확인 필요"로 본다. 약 8~10패킷/60초에 해당한다. */
    const val CONFIRMED = 4.0
}
