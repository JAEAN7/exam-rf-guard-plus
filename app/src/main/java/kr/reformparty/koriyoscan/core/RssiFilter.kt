package kr.reformparty.koriyoscan.core

import kotlin.math.exp
import kotlin.math.pow

/** RSSI 평활, 거리 환산, 거리 등급 분류 */
object RssiFilter {
    /** 보정 정보가 없을 때 사용하는 1m 기준 RSSI 가정값. BLE 표준 보정값은 아니다. */
    const val DEFAULT_TX_DBM = -59.0
    /** 실내 경로손실 지수 */
    const val PATH_N = 2.7

    /** 지수 이동평균(패킷 수 기준). 시간 간격을 모를 때 쓰는 옛 방식이다. */
    fun ema(prev: Double?, sample: Int, alpha: Double = 0.25): Double =
        prev?.let { it + alpha * (sample - it) } ?: sample.toDouble()

    /**
     * 시간 간격을 반영한 지수 이동평균. 패킷이 드문 약한 기기도 벽시계 기준으로 수렴한다.
     * dtMs 가 0 이하이거나 비정상이면 기존 EMA로 되돌아간다.
     */
    fun emaSince(prev: Double?, sample: Int, dtMs: Long, tauMs: Long = 2_000L): Double {
        if (prev == null) return sample.toDouble()
        if (dtMs <= 0L || tauMs <= 0L) return ema(prev, sample)
        val alpha = 1.0 - exp(-dtMs.toDouble() / tauMs)
        return prev + alpha.coerceIn(0.02, 1.0) * (sample - prev)
    }

    /** 로그 거리 경로손실 모델 */
    fun distanceM(rssi: Double, txDbm: Double = DEFAULT_TX_DBM, pathN: Double = PATH_N): Double {
        val exponent = (txDbm - rssi) / (10 * pathN.coerceAtLeast(0.5))
        return 10.0.pow(exponent)
    }

    fun classify(distance: Double?): DistanceClass = when {
        distance == null -> DistanceClass.UNKNOWN
        distance < 1.6 -> DistanceClass.VERY_CLOSE
        distance < 3.2 -> DistanceClass.CLOSE
        distance < 8.0 -> DistanceClass.NEAR
        else -> DistanceClass.FAR
    }
}
