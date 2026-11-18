package com.classroom.rfdetector.core

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 기기 하나의 최근 관측을 보관하고 "얼마나 꾸준히 잡혔는지"를 점수로 바꾼다.
 *
 * 기존의 "30초 연속" 판정은 15초만 끊겨도 처음부터 다시 세야 했다. 멀리 있는 기기는
 * 패킷이 드문드문 오기 때문에 영원히 조건을 못 채웠다. 이 창은 시간이 지날수록
 * 오래된 패킷의 무게를 줄이면서(반감기) 최근 증거를 누적한다.
 *
 * 감도 모드에 따라 반감기와 보관 기간을 바꿀 수 있다. 최대 감지에서는 반감기를 늘려
 * 약하고 드문 패킷도 점수에 남게 하고, 보관 기간을 늘려 몇 분에 한 번 오는 먼 기기가
 * "관찰 중"으로 되돌아가지 않게 한다.
 */
class EvidenceWindow(
    halfLifeMs: Long = 20_000L,
    maxAgeMs: Long = 120_000L,
) {
    private data class Sample(val atMs: Long, val rssi: Int?)

    /** 1분 단위 버킷. 오래된 표본을 버린 뒤에도 "몇 분에 걸쳐 반복해서 잡혔는지"는 남긴다. */
    private class Bucket {
        var hits = 0
        var best: Int? = null
        var firstMs = Long.MAX_VALUE
        var lastMs = Long.MIN_VALUE
    }

    @Volatile private var halfLifeMs: Long = halfLifeMs.coerceAtLeast(1_000L)
    @Volatile private var maxAgeMs: Long = maxAgeMs.coerceAtLeast(10_000L)
    @Volatile private var bucketHorizonMs: Long = DEFAULT_BUCKET_HORIZON_MS

    private val samples = ArrayDeque<Sample>()
    // 점수는 상수 시간에 구하려고 누적값으로 유지한다. 매번 모든 패킷에 pow()를 하면
    // BLE 패킷이 많은 교실에서 화면 갱신(0.1초)마다 수십만 번 계산하게 된다.
    private var weighted = 0.0
    private var weightedAtMs = 0L
    private var hasSamples = false
    private val buckets = HashMap<Long, Bucket>()

    private companion object {
        /** 원거리 기억에 쓰는 버킷 크기와 기본 보관 기간. */
        const val BUCKET_MS = 60_000L
        const val DEFAULT_BUCKET_HORIZON_MS = 15 * 60_000L
    }

    /** 감도 모드가 바뀌면 반감기·보관 기간을 바꾸고 누적 점수를 표본에서 다시 계산한다. */
    @Synchronized
    fun configure(halfLifeMs: Long, maxAgeMs: Long, bucketHorizonMs: Long = DEFAULT_BUCKET_HORIZON_MS) {
        val nextHalfLife = halfLifeMs.coerceAtLeast(1_000L)
        val nextMaxAge = maxAgeMs.coerceAtLeast(10_000L)
        val nextHorizon = maxOf(bucketHorizonMs, DEFAULT_BUCKET_HORIZON_MS).coerceAtLeast(maxAgeMs)
        if (nextHalfLife == this.halfLifeMs && nextMaxAge == this.maxAgeMs && nextHorizon == this.bucketHorizonMs) return
        this.halfLifeMs = nextHalfLife
        this.maxAgeMs = nextMaxAge
        this.bucketHorizonMs = nextHorizon
        rebuild()
    }

    private fun rebuild() {
        if (samples.isEmpty()) {
            weighted = 0.0; weightedAtMs = 0L; hasSamples = false; return
        }
        val newest = samples.last().atMs
        weighted = samples.sumOf { 0.5.pow((newest - it.atMs).toDouble() / halfLifeMs) }
        weightedAtMs = newest
        hasSamples = true
        pruneSamples(newest)
    }

    @Synchronized
    fun add(atMs: Long, rssi: Int?) {
        if (!hasSamples) {
            weighted = 1.0
            weightedAtMs = atMs
            hasSamples = true
        } else {
            val dt = (atMs - weightedAtMs).coerceAtLeast(0)
            weighted = weighted * 0.5.pow(dt.toDouble() / halfLifeMs) + 1.0
            weightedAtMs = maxOf(weightedAtMs, atMs)
        }
        samples.addLast(Sample(atMs, rssi))
        pruneSamples(atMs)
        rememberBucket(atMs, rssi)
    }

    private fun pruneSamples(newestMs: Long) {
        while (samples.isNotEmpty() && newestMs - samples.first().atMs > maxAgeMs) samples.removeFirst()
    }

    private fun rememberBucket(atMs: Long, rssi: Int?) {
        val index = Math.floorDiv(atMs, BUCKET_MS)
        val bucket = buckets.getOrPut(index) { Bucket() }
        bucket.hits++
        bucket.firstMs = minOf(bucket.firstMs, atMs)
        bucket.lastMs = maxOf(bucket.lastMs, atMs)
        rssi?.let { bucket.best = maxOf(bucket.best ?: it, it) }
        val horizon = bucketHorizonMs
        val cutoff = Math.floorDiv(maxOf(atMs, bucket.lastMs) - horizon, BUCKET_MS)
        if (buckets.size > 4) buckets.keys.removeAll { it < cutoff }
    }

    /**
     * 표본은 지웠어도 남겨 둔 1분 버킷으로 "몇 분에 걸쳐, 몇 번" 잡혔는지 센다.
     * 멀리 있는 기기는 30초~2분에 한 번씩만 보이므로, 이 값이 없으면 화면에서 계속 사라진다.
     */
    @Synchronized
    fun recall(nowMs: Long, horizonMs: Long): RecallEvidence {
        if (horizonMs <= 0L || buckets.isEmpty()) return RecallEvidence()
        val cutoff = nowMs - horizonMs
        var hits = 0
        var minutes = 0
        var best: Int? = null
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        buckets.forEach { (index, bucket) ->
            if ((index + 1) * BUCKET_MS <= cutoff) return@forEach
            minutes++
            hits += bucket.hits
            bucket.best?.let { best = maxOf(best ?: it, it) }
            first = minOf(first, bucket.firstMs)
            last = maxOf(last, bucket.lastMs)
        }
        if (hits == 0 || last == Long.MIN_VALUE) return RecallEvidence()
        return RecallEvidence(hits, minutes, (last - first).coerceAtLeast(0), last, best)
    }

    /** 최근 패킷에 가중치를 준 증거 점수. 반감기가 지난 패킷은 절반씩 줄여 센다. */
    fun score(nowMs: Long): Double {
        if (!hasSamples) return 0.0
        val age = (nowMs - weightedAtMs).coerceAtLeast(0)
        // 보관 기간의 두 배가 지난 패킷은 사실상 0이므로 굳이 다시 계산하지 않는다.
        if (age > maxAgeMs * 2) return 0.0
        return weighted * 0.5.pow(age.toDouble() / halfLifeMs)
    }

    fun count(nowMs: Long, withinMs: Long = 60_000L): Int =
        samples.count { nowMs - it.atMs in 0..withinMs }

    /** 최근 창 안에서 처음 잡힌 때와 마지막으로 잡힌 때의 차이. 짧은 재등장을 걸러 낸다. */
    fun spanMs(nowMs: Long, withinMs: Long = 120_000L): Long {
        val recent = samples.filter { nowMs - it.atMs in 0..withinMs }
        if (recent.size < 2) return 0L
        return recent.maxOf { it.atMs } - recent.minOf { it.atMs }
    }

    fun medianRssi(nowMs: Long, withinMs: Long = 10_000L): Double? = percentile(nowMs, 0.50, withinMs)

    fun p90Rssi(nowMs: Long, withinMs: Long = 10_000L): Double? = percentile(nowMs, 0.90, withinMs)

    fun spread(nowMs: Long, withinMs: Long = 30_000L): Double? {
        val values = recentRssi(nowMs, withinMs)
        if (values.size < 3) return null
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    fun history(nowMs: Long, limit: Int = 40, withinMs: Long = 60_000L): List<Int> =
        recentRssi(nowMs, withinMs).takeLast(limit)

    fun recentPeak(nowMs: Long, withinMs: Long = 15_000L): Int? = recentRssi(nowMs, withinMs).maxOrNull()

    private fun recentRssi(nowMs: Long, withinMs: Long): List<Int> =
        samples.filter { nowMs - it.atMs in 0..withinMs }.mapNotNull { it.rssi }

    private fun percentile(nowMs: Long, fraction: Double, withinMs: Long): Double? {
        val values = recentRssi(nowMs, withinMs).sorted()
        if (values.isEmpty()) return null
        if (values.size == 1) return values.first().toDouble()
        val position = (values.size - 1) * fraction
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(values.lastIndex)
        val weight = position - lower
        return values[lower] + (values[upper] - values[lower]) * weight
    }
}

/**
 * 보관 기간이 지난 표본 대신 남겨 두는 장기 기억. 표본을 지워도 버킷은 남으므로,
 * 몇 분에 한 번만 잡히는 먼 기기도 "여러 시점에 걸쳐 반복 확인된 신호"로 남는다.
 */
data class RecallEvidence(
    val hits: Int = 0,
    /** 서로 다른 1분 구간 중 패킷이 있던 구간 수. 한 번 몰아서 온 패킷은 1이다. */
    val activeMinutes: Int = 0,
    val spanMs: Long = 0L,
    val lastSeenMs: Long? = null,
    val bestRssi: Int? = null,
)

/** 최근 1분 동안 몇 개의 패킷이 왔는지 세는 작은 계량기. 감시 품질 표시에 쓴다. */
class RateMeter(private val windowMs: Long = 60_000L) {
    private val marks = ArrayDeque<Long>()

    fun mark(nowMs: Long) {
        marks.addLast(nowMs)
        prune(nowMs)
    }

    fun clear() = marks.clear()

    fun perMinute(nowMs: Long): Double {
        prune(nowMs)
        return marks.size.toDouble()
    }

    fun count(nowMs: Long): Int {
        prune(nowMs)
        return marks.size
    }

    private fun prune(nowMs: Long) {
        while (marks.isNotEmpty() && nowMs - marks.first() > windowMs) marks.removeFirst()
    }
}

/**
 * 감지 감도. 최대 모드는 같은 신호를 더 오래 "잡히는 중"으로 유지하고,
 * 클래식 블루투스와 와이파이를 더 자주 확인해 먼·간헐 신호를 놓칠 확률을 낮춘다.
 * 대신 배터리와 발열이 늘어난다.
 */
enum class SensitivityMode(val label: String, val description: String) {
    STANDARD("표준", "배터리를 아끼면서 일반적인 신호를 찾습니다."),
    MAX("최대 감지", "약하고 간헐적인 신호를 더 오래 보여 주고, 몇 분에 한 번만 잡히는 먼 기기도 기억합니다. 클래식 블루투스와 와이파이를 더 자주 확인합니다. 배터리를 더 씁니다."),
}

/**
 * 감도 모드 하나가 바꾸는 값 전부. 탐지 거리를 넓히는 소프트웨어 손잡이를 한 곳에 모아,
 * 모드를 바꿀 때 스캐너·증거 창·판정 기준이 서로 어긋나지 않게 한다.
 *
 * - [evidenceHalfLifeMs]: 오래된 패킷의 무게를 줄이는 속도. 길수록 약한 패킷이 점수에 남는다.
 * - [evidenceMaxAgeMs]: 점수 계산에 쓰는 표본 보관 기간.
 * - [recallHorizonMs]: 표본이 지워진 뒤에도 "몇 분에 걸쳐 반복됐는지"를 보는 기간. 0이면 끔.
 * - [wifiCacheWindowMs]: 최신 탐색에 없어도 캐시에서 되살릴 수 있는 나이. 되살리기 상한이다.
 * - [wifiCacheFreshMs]: 캐시에서 꺼낸 결과를 "지금 잡힌 것"으로 인정하는 최대 나이. 되살리기 상한과 분리한다.
 * - [wifiCachePollMs]: 탐색 요청 없이 캐시만 훑는 주기. 캐시 읽기는 OS 탐색 제한을 쓰지 않으므로
 *   최대 감지에서는 아주 짧게 잡아, 다른 앱·시스템이 갱신해 둔 결과를 먼저 주워 담는다.
 * - [bleRefreshMs]: 시스템이 탐색을 낮추기 전에 다시 거는 주기.
 * - [classicSweepMs]: 클래식 탐색 한 번을 붙잡고 있는 시간. 길수록 늦게 응답하는 기기를 더 잡는다.
 * - [farThresholdDbm]: 이보다 약한 신호를 "멀리서 약하게"로 묶는 기준.
 */
data class SensitivityProfile(
    val evidenceHalfLifeMs: Long,
    val evidenceMaxAgeMs: Long,
    val recallHorizonMs: Long,
    val recallMinHits: Int,
    val recallMinActiveMinutes: Int,
    val wifiCacheWindowMs: Long,
    /**
     * 캐시에서 꺼낸 결과를 "지금 잡힌 것"으로 인정하는 최대 나이.
     * [wifiCacheWindowMs]와 분리한다. 되살리기 상한까지 늘리면, 그 사이 나이의 결과가
     * 태어나자마자 "잠시 끊김"·"사라짐"으로 들어와 내부 상태만 어지럽힌다.
     */
    val wifiCacheFreshMs: Long,
    val wifiCachePollMs: Long,
    val bleRefreshMs: Long,
    /**
     * 클래식 탐색 한 번을 붙잡고 있는 시간. 표준 inquiry는 10.24초라 12초 아래로는 내리지 않는다.
     * 최대 감지에서는 늘려, 늦게 응답하는 먼 기기가 응답할 틈을 더 준다.
     */
    val classicSweepMs: Long,
    val farThresholdDbm: Double,
    val likelyThreshold: Double,
    val confirmedThreshold: Double,
) {
    val recallEnabled: Boolean get() = recallHorizonMs > 0L

    companion object {
        fun of(mode: SensitivityMode): SensitivityProfile = when (mode) {
            SensitivityMode.STANDARD -> SensitivityProfile(
                evidenceHalfLifeMs = 20_000L,
                evidenceMaxAgeMs = 120_000L,
                recallHorizonMs = 0L,
                recallMinHits = 0,
                recallMinActiveMinutes = 0,
                wifiCacheWindowMs = 90_000L,
                wifiCacheFreshMs = 60_000L,
                // [FORK 1.1.2] 캐시 읽기는 OS 탐색 제한을 쓰지 않으므로 절약 모드에서도 30초마다 훑는다(원본 0=끔).
                wifiCachePollMs = 30_000L,
                bleRefreshMs = 4 * 60_000L,
                classicSweepMs = 12_000L,
                farThresholdDbm = WEAK_SIGNAL_DBM.toDouble(),
                likelyThreshold = EvidenceThresholds.LIKELY,
                confirmedThreshold = EvidenceThresholds.CONFIRMED,
            )
            // 약한 패킷이 점수에 더 오래 남으므로 기준값도 함께 올려, 잡음 하나로 "확인 필요"가 되지 않게 한다.
            SensitivityMode.MAX -> SensitivityProfile(
                evidenceHalfLifeMs = 45_000L,
                evidenceMaxAgeMs = 300_000L,
                recallHorizonMs = 15 * 60_000L,
                recallMinHits = 3,
                recallMinActiveMinutes = 3,
                wifiCacheWindowMs = 180_000L,
                wifiCacheFreshMs = 60_000L,
                // [FORK 1.1.2] 최대 감지에서 캐시를 6초마다 훑고(원본 20초), BLE도 100초마다 다시 건다(원본 150초).
                wifiCachePollMs = 6_000L,
                bleRefreshMs = 100_000L,
                classicSweepMs = 20_000L,
                farThresholdDbm = -100.0,
                likelyThreshold = 2.0,
                confirmedThreshold = 5.0,
            )
        }
    }
}

enum class HealthLevel { GOOD, WARNING, BAD }

/** 지금 감시가 실제로 돌아가고 있는지 보여 주기 위한 상태. */
data class ScanHealth(
    val blePacketsPerMinute: Double = 0.0,
    val wifiPacketsPerMinute: Double = 0.0,
    val classicPacketsPerMinute: Double = 0.0,
    val bleActive: Boolean = false,
    val wifiActive: Boolean = false,
    val classicActive: Boolean = false,
    val bleLastSeenAgeSec: Long? = null,
    val wifiCompletedScans: Int = 0,
    val wifiLastScanAgeSec: Long? = null,
    /** 탐색 요청 없이 캐시만 훑어 찾아낸 결과 수. 요청 횟수를 아끼면서 얻은 수신이다. */
    val wifiCachedPicks: Long = 0L,
    val bleError: String? = null,
    val wifiError: String? = null,
    val classicError: String? = null,
    val thermalStatus: Int = 0,
    val batteryPercent: Int? = null,
    /** Android가 와이파이 탐색을 제한하고 있는지. 개발자 옵션에서 끄면 false가 된다. */
    val wifiThrottled: Boolean = false,
) {
    val level: HealthLevel get() = when {
        // 와이파이의 "갱신 대기"는 시스템이 탐색 빈도를 제한할 때도 나오므로 오류로 단정하지 않는다.
        bleError != null -> HealthLevel.BAD
        !bleActive || !wifiActive -> HealthLevel.BAD
        thermalStatus >= 3 -> HealthLevel.WARNING
        bleLastSeenAgeSec != null && bleLastSeenAgeSec > 30 -> HealthLevel.WARNING
        wifiLastScanAgeSec != null && wifiLastScanAgeSec > 90 -> HealthLevel.WARNING
        else -> HealthLevel.GOOD
    }
}
