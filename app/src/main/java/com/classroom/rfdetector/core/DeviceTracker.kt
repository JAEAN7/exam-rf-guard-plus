package com.classroom.rfdetector.core

import java.util.concurrent.ConcurrentHashMap

/** Keep each radio/address independent, including its name, strength and history. */
class DeviceTracker {
    private class Track(val key: String, val source: ScanSource) {
        var name: String? = null
        var hidden = false
        val manufacturerIds = LinkedHashSet<Int>()
        val hints = LinkedHashSet<SignalHint>()
        val details = LinkedHashMap<String, String>()
        var ema: Double? = null
        var lastSampleAtMs: Long? = null
        var tx: Double? = null
        var periods: List<ObservationPeriod> = emptyList()
        var firstSeen = 0L
        var lastSeen = 0L
        var observationCount = 0
        var peakRssi: Int? = null
        var fingerprint: String? = null
        var lastDedupKey: String? = null
        var lastDedupAtMs: Long = 0L
        val evidence = EvidenceWindow()
    }

    private val tracks = ConcurrentHashMap<String, Track>()
    /** 최대 감지 모드에서는 약한 신호를 더 오래 "잡히는 중"으로 유지한다. */
    @Volatile private var extendedPresence = false
    @Volatile private var profile = SensitivityProfile.of(SensitivityMode.STANDARD)

    fun setExtendedPresence(on: Boolean) {
        extendedPresence = on
        setProfile(SensitivityProfile.of(if (on) SensitivityMode.MAX else SensitivityMode.STANDARD))
    }

    /**
     * 감도 프로파일을 적용한다. 증거 창의 반감기·보관 기간이 바뀌므로, 이미 쌓인 표본으로
     * 점수를 다시 계산해 모드를 바꾼 직후 판정이 튀지 않게 한다.
     */
    @Synchronized
    fun setProfile(next: SensitivityProfile) {
        val changed = next != profile
        profile = next
        extendedPresence = next == SensitivityProfile.of(SensitivityMode.MAX)
        if (!changed) return
        tracks.values.forEach {
            it.evidence.configure(next.evidenceHalfLifeMs, next.evidenceMaxAgeMs, next.recallHorizonMs)
        }
    }

    @Synchronized
    fun update(obs: RfObservation) {
        val firstObservation = !tracks.containsKey(obs.trackingKey)
        val t = tracks.getOrPut(obs.trackingKey) {
            Track(obs.key, obs.source).also {
                it.firstSeen = obs.timestampMs
                it.evidence.configure(profile.evidenceHalfLifeMs, profile.evidenceMaxAgeMs, profile.recallHorizonMs)
            }
        }
        // Android can deliver the same advertisement twice (especially in batch callbacks).
        // Do not let a duplicate packet inflate the evidence score.
        if (obs.dedupKey != null && obs.dedupKey == t.lastDedupKey && obs.timestampMs == t.lastDedupAtMs) return
        if (obs.dedupKey != null) {
            t.lastDedupKey = obs.dedupKey
            t.lastDedupAtMs = obs.timestampMs
        }
        val late = !firstObservation && obs.timestampMs < t.lastSeen
        t.firstSeen = minOf(t.firstSeen, obs.timestampMs)
        t.lastSeen = maxOf(t.lastSeen, obs.timestampMs)
        t.observationCount++
        t.evidence.add(obs.timestampMs, obs.rssi)
        val previousPeriodCount = t.periods.size
        t.periods = addToPeriods(t.periods, obs.timestampMs, obs.source.historyGapMs())
        t.manufacturerIds.addAll(obs.manufacturerIds)
        t.hints.addAll(obs.hints)
        if (!late) t.details.putAll(obs.details)
        if (!late || t.name == null) obs.name?.takeIf { it.isNotBlank() }?.let { t.name = it }
        if (!late) t.hidden = obs.hidden
        obs.fingerprint?.takeIf { it.isNotBlank() }?.let { fp -> if (!late || t.fingerprint == null) t.fingerprint = fp }
        obs.rssi?.let { r ->
            if (!late) {
                val newPeriod = t.periods.size > previousPeriodCount
                val dt = t.lastSampleAtMs?.let { obs.timestampMs - it } ?: 0L
                t.ema = if (newPeriod || t.ema == null) r.toDouble() else RssiFilter.emaSince(t.ema, r, dt)
                t.lastSampleAtMs = obs.timestampMs
                t.tx = obs.txPower?.takeIf { it in -100..20 && it != 127 }?.toDouble() ?: t.tx
            }
            t.peakRssi = maxOf(t.peakRssi ?: r, r)
        }
    }

    @Synchronized
    fun snapshot(nowMs: Long, includeInactive: Boolean = false): List<TrackedDevice> {
        val extended = extendedPresence
        val p = profile
        val inactiveWindow = if (extended) 180_000L else 90_000L
        return tracks.values.filter { t ->
            if (includeInactive) return@filter true
            if (nowMs - t.lastSeen <= maxOf(inactiveWindow, t.source.historyGapMs())) return@filter true
            // 표본 창을 넘긴 기기도, 몇 분에 걸쳐 반복 확인됐다면 목록에 남긴다.
            p.recallEnabled && qualifiesForRecall(t, nowMs, p)
        }.map { t ->
            val score = t.evidence.score(nowMs)
            val recall = if (p.recallEnabled) t.evidence.recall(nowMs, p.recallHorizonMs) else RecallEvidence()
            val confidence = confidenceOf(t, nowMs, score, p, recall)
            val median = t.evidence.medianRssi(nowMs) ?: t.ema
            val p90 = t.evidence.p90Rssi(nowMs)
            val spread = t.evidence.spread(nowMs)
            val current = median ?: t.ema
            val dist = current?.let { RssiFilter.distanceM(it, t.tx ?: RssiFilter.DEFAULT_TX_DBM) }
            val ageMs = (nowMs - t.lastSeen).coerceAtLeast(0)
            val basePresence = signalPresence(t.source, t.lastSeen, nowMs, extended)
            // 최근 창을 벗어났어도 장기 기억이 남아 있으면 "잠시 끊김"으로 유지한다.
            // 기준은 감도 프로파일과 같게 맞춘다. 기억 판정과 화면 유지가 서로 다른 기준을 쓰면
            // 목록에는 남는데 개수에는 들어가는 식으로 어긋난다.
            val keptByRecall = basePresence == SignalPresence.HISTORY && qualifies(recall, p)
            val presence = if (keptByRecall) SignalPresence.QUIET else basePresence
            TrackedDevice(
                key = t.key,
                displayName = t.name ?: defaultName(t),
                source = t.source,
                hidden = t.hidden,
                rssiEma = t.ema,
                distanceClass = RssiFilter.classify(dist),
                firstSeenMs = t.firstSeen,
                lastSeenMs = t.lastSeen,
                manufacturerIds = t.manufacturerIds.toSet(),
                presence = presence,
                lastSeenAgeSeconds = ageMs / 1000,
                observationCount = t.observationCount,
                peakRssi = t.peakRssi,
                periods = t.periods.toList(),
                referenceRssiAt1m = t.tx,
                hints = t.hints.toSet(),
                details = t.details.toMap(),
                confidence = confidence,
                evidenceScore = score,
                packetRatePerMin = t.evidence.count(nowMs, 60_000L).toDouble(),
                rssiMedian = median,
                rssiP90 = p90,
                rssiSpread = spread,
                recentPeakRssi = t.evidence.recentPeak(nowMs),
                fingerprint = t.fingerprint,
                rssiHistory = t.evidence.history(nowMs),
                recall = recall,
                keptByRecall = keptByRecall,
            )
        }.sortedWith(
            compareBy<TrackedDevice> { it.presence.ordinal }
                .thenByDescending { it.rssiEma ?: Double.NEGATIVE_INFINITY }
        )
    }

    private fun qualifiesForRecall(t: Track, nowMs: Long, p: SensitivityProfile): Boolean =
        qualifies(t.evidence.recall(nowMs, p.recallHorizonMs), p)

    /** 몇 분에 걸쳐 반복 확인됐는지. 표본이 지워진 뒤에도 목록에 남길지 결정한다. */
    private fun qualifies(recall: RecallEvidence, p: SensitivityProfile): Boolean =
        p.recallEnabled && recall.hits >= p.recallMinHits && recall.activeMinutes >= p.recallMinActiveMinutes

    private fun confidenceOf(
        t: Track,
        nowMs: Long,
        score: Double,
        p: SensitivityProfile,
        recall: RecallEvidence,
    ): SignalConfidence {
        val recentPackets = t.evidence.count(nowMs, 60_000L)
        val recentSpan = t.evidence.spanMs(nowMs, 120_000L)
        // 먼 기기는 30초~2분에 한 번만 잡힐 수 있다. 최근 점수만 보면 영원히 "관찰 중"에 머문다.
        // 증거 창(2분) 안에서 두 번 이상, 20초 이상 간격을 두고 잡혔다면 실제로 있는 기기로 본다.
        val longPackets = t.evidence.count(nowMs, 110_000L)
        val longSpan = t.evidence.spanMs(nowMs, 110_000L)
        // 최근 창에서 완전히 빠진 표본은 장기 기억(1분 버킷)으로 대신 본다.
        // 장기 기억을 끈 표준 모드에서는 이 경로를 쓰지 않는다.
        val recallConfirms = p.recallEnabled && recall.hits >= p.recallMinHits + 2 &&
            recall.activeMinutes >= p.recallMinActiveMinutes + 1
        val recallLikely = p.recallEnabled && recall.hits >= p.recallMinHits &&
            recall.activeMinutes >= p.recallMinActiveMinutes
        return when {
            (score >= p.confirmedThreshold && recentPackets >= 3 && recentSpan >= 10_000L) ||
                (longPackets >= 5 && longSpan >= 60_000L) ||
                recallConfirms -> SignalConfidence.CONFIRMED
            score >= p.likelyThreshold ||
                (longPackets >= 2 && longSpan >= 20_000L) ||
                recallLikely -> SignalConfidence.LIKELY
            else -> SignalConfidence.OBSERVED
        }
    }

    private fun defaultName(t: Track): String = when {
        t.hidden -> "이름 없는 AP"
        t.source == ScanSource.WIFI -> "Wi-Fi 기기 " + t.key.takeLast(5)
        t.source == ScanSource.CLASSIC -> "블루투스 기기 " + t.key.takeLast(5)
        else -> "BLE 기기 " + t.key.takeLast(5)
    }

    @Synchronized
    fun clear() {
        tracks.clear()
    }
}
