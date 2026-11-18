package kr.reformparty.koriyoscan.core

import java.util.Locale

/** 이름을 숨긴 와이파이 전부가 공유하는 묶음 키. 이름이 같은 것이 아니므로 이름처럼 "원래 있던 것"으로 보면 안 된다. */
const val HIDDEN_WIFI_KEY = "WIFI:hidden"

/** 지문이 있는 숨김 와이파이는 서로 다른 공유기일 수 있으므로 지문별로 나눈다. */
fun isHiddenWifiGroup(key: String): Boolean = key == HIDDEN_WIFI_KEY || key.startsWith("$HIDDEN_WIFI_KEY:")

/**
 * 주소 대신 쓰는 묶음 기준. 블루투스 주소는 10~15분마다 바뀌므로 기기가 알린 이름,
 * 이름이 없으면 광고 지문, 그것도 없으면 회사 코드와 종류 단서로 묶는다. 와이파이는 이름으로 묶는다.
 */
fun signalGroupKey(
    source: ScanSource,
    address: String,
    name: String,
    manufacturerIds: Set<Int>,
    hints: Set<SignalHint>,
    fingerprint: String? = null,
): String {
    val advertised = advertisedName(address, name)
    val fp = fingerprint?.takeIf { it.isNotBlank() }
    return when {
        advertised != null -> "${source.name}:name:${advertised.lowercase(Locale.ROOT)}"
        source == ScanSource.WIFI -> fp?.let { "$HIDDEN_WIFI_KEY:$it" } ?: HIDDEN_WIFI_KEY
        fp != null -> "${source.name}:fp:$fp"
        manufacturerIds.isEmpty() && hints.isEmpty() -> "${source.name}:unknown"
        else -> "${source.name}:kind:${manufacturerIds.sorted().joinToString(",")}:${hints.primary?.name.orEmpty()}"
    }
}

/** 기기가 실제로 알린 이름. 앱이 대신 붙인 이름(주소 끝자리 등)이면 null이다. */
fun advertisedName(address: String, name: String): String? {
    val tail = address.takeLast(5)
    return name.trim().takeUnless {
        it.isEmpty() || it == "이름 없는 AP" || it == "Wi-Fi 기기 $tail" ||
            it == "BLE 기기 $tail" || it == "블루투스 기기 $tail"
    }
}

val TrackedDevice.groupKey: String get() = signalGroupKey(source, key, displayName, manufacturerIds, hints, fingerprint)
val SnapshotSignal.groupKey: String get() = signalGroupKey(source, address, name, manufacturerIds, hints, fingerprint)

/**
 * "확인함"으로 목록에서 뺄 때 쓰는 키. 보통은 묶음 키지만, 이름 숨김 와이파이는 서로 다른 기기가 한 묶음에 모이므로
 * 주소마다 따로 뺀다. 묶음째 빼면 숨김 와이파이 하나를 확인한 뒤 새로 켜진 다른 숨김 핫스팟까지 알리지 않게 된다.
 */
val TrackedDevice.acknowledgeKey: String get() = groupKey.let { group ->
    if (isHiddenWifiGroup(group)) "$group:${key.uppercase(Locale.ROOT)}" else group
}

/**
 * 빈 교실 기록에서 묶음마다 동시에 몇 개였는지. 저장 순간 잡히지 않던 묶음은 1개로 친다.
 * 여러 번 스윕한 기록이 있으면 그중 가장 많이 동시에 잡힌 값을 쓴다.
 */
fun baselineGroupCounts(baseline: SignalSnapshot): Map<String, Int> {
    val observed = baseline.signals.groupBy { it.groupKey }
        .mapValues { (_, signals) -> signals.count { it.presence != SignalPresence.HISTORY }.coerceAtLeast(1) }
    val keys = observed.keys + baseline.groupMaxCounts.keys
    return keys.associateWith { key -> maxOf(observed[key] ?: 0, baseline.groupMaxCounts[key] ?: 0).coerceAtLeast(1) }
}

/**
 * 지금 잡히는 한 묶음. devices는 센 순서다.
 *
 * [liveCount]는 화면에 "지금 몇 개"로 보여 줄 값이다. 표본이 끊긴 기억 속 기기는 세지 않고,
 * 개수 비교에도 넣지 않는다. 그래야 최대 감지에서 기억을 늘려도 "늘어난 전파"가 헛되이 뜨지 않는다.
 */
data class SignalGroup(
    val key: String,
    val devices: List<TrackedDevice>,
    val baselineCount: Int,
    val alert: Boolean,
    val confidence: SignalConfidence = SignalConfidence.OBSERVED,
    val liveCount: Int = devices.count { it.presence != SignalPresence.HISTORY && !it.isRecallOnly },
) {
    val strongest: TrackedDevice get() = devices.first()
    /** 지금 실제로 패킷이 오는 기기만. 개수 비교와 표시에 쓴다. */
    val liveDevices: List<TrackedDevice> get() = devices.filter { it.presence != SignalPresence.HISTORY && !it.isRecallOnly }
}

/**
 * "원래 있던 전파" 목록의 한 줄. 빈 교실 기록의 묶음마다 하나씩 있고 순서가 바뀌지 않는다.
 * 지금 잡히는지에 따라 live만 달라진다. sample은 빈 교실 기록의 대표, last는 가장 최근에 잡힌 주소다.
 */
data class KnownGroup(val key: String, val sample: SnapshotSignal?, val baselineCount: Int, val live: List<TrackedDevice>, val last: TrackedDevice?)

/** 빈 교실 기록의 묶음별 대표를 고정된 순서로: 와이파이 먼저, 그 안에서는 기록 당시 센 순서. */
fun baselineSamples(baseline: SignalSnapshot): List<SnapshotSignal> = baseline.signals
    .filterNot { it.source == ScanSource.BLE && it.hints.primary == SignalHint.APPLE_FIND_MY }
    .groupBy { it.groupKey }.values.map { group -> group.maxBy { it.rssi ?: Double.NEGATIVE_INFINITY } }
    .sortedWith(compareBy<SnapshotSignal> { it.source != ScanSource.WIFI }.thenByDescending { it.rssi ?: Double.NEGATIVE_INFINITY }.thenBy { it.groupKey })

/** 감시 화면에 보이는 것 전부. 새 전파는 지금 잡히는 것만 담고 누적하지 않는다. */
data class WatchView(
    val wifi: List<SignalGroup> = emptyList(),
    val bluetooth: List<SignalGroup> = emptyList(),
    val far: List<SignalGroup> = emptyList(),
    val findMy: List<TrackedDevice> = emptyList(),
    val knownGroups: List<KnownGroup> = emptyList(),
) {
    /**
     * 지금 패킷이 오는 묶음 수. 목록에 보이는 줄과 숫자를 맞추기 위해 `alert` 대신
     * "지금 잡히는 기기가 있는지"로 센다. 아직 반복 확인이 모자라 `alert`가 아닌 묶음도
     * 목록에는 보이므로, 그것까지 빼면 화면의 줄과 숫자가 어긋난다.
     * 기억으로만 유지되는 묶음은 목록에는 남아도 여기서는 세지 않는다.
     */
    val count: Int get() = (wifi + bluetooth).count { it.liveCount > 0 }
    /** 멀리서 약하게 잡히는 것 중 지금 패킷이 오는 묶음 수. */
    val farCount: Int get() = far.count { it.liveCount > 0 }
    /** 지금은 끊겼지만 장기 기억으로 목록에 남아 있는 묶음 수. 상단 숫자와 따로 안내한다. */
    val recallOnlyCount: Int get() = (wifi + bluetooth + far).count { it.liveCount == 0 }
}

/** 빈 교실 기록에 있던 와이파이 주소. 같은 공유기가 내는 다른 주소를 알아보는 데 쓴다. */
fun baselineWifiAddresses(baseline: SignalSnapshot): Set<String> =
    baseline.signals.filter { it.source == ScanSource.WIFI }.map { it.address.uppercase(Locale.ROOT) }.toSet()

/**
 * 공유기 한 대는 이름별·대역별로 주소를 여러 개 쓰는데, 이 주소들은 첫 자리나 끝 자리만 다르다.
 * 빈 교실 기록의 주소와 나머지 다섯 자리가 같으면 같은 공유기가 낸 것으로 본다.
 */
fun isSameAccessPoint(address: String, baselineWifi: Set<String>): Boolean {
    val candidate = address.uppercase(Locale.ROOT)
    if (candidate in baselineWifi) return true
    if (candidate.length != 17) return false
    return baselineWifi.any { known -> known.length == 17 && (known.substring(3) == candidate.substring(3) || known.dropLast(2) == candidate.dropLast(2)) }
}

/** 순위는 지금 잡히는 세기를 우선하되, 표본이 비었으면 장기 기억의 최고 세기를 쓴다. */
private fun TrackedDevice.rankRssi(): Double = rssiP90 ?: rssiMedian ?: rssiEma
    ?: recall.bestRssi?.toDouble() ?: Double.NEGATIVE_INFINITY

fun watchView(
    devices: List<TrackedDevice>,
    baseline: Map<String, Int>,
    acknowledged: Set<String>,
    nowMs: Long,
    baselineWifi: Set<String> = emptySet(),
    samples: List<SnapshotSignal> = emptyList(),
    farThresholdDbm: Double = WEAK_SIGNAL_DBM.toDouble(),
): WatchView {
    val live = devices.filter { it.presence != SignalPresence.HISTORY }
    // 전원을 끈 아이폰과 에어태그가 내는 신호. 제출한 휴대폰 수만큼 잡힐 수 있어 알리지 않고 따로 센다.
    // 장기 기억으로만 유지되는 것은 세지 않는다. 지금 패킷이 없는데도 "꺼 둔 아이폰 N개"가
    // 15분 동안 부풀어 보이면 안 된다.
    val (findMy, others) = live.partition { it.source == ScanSource.BLE && it.hints.primary == SignalHint.APPLE_FIND_MY }
    val findMyLive = findMy.filterNot { it.isRecallOnly }
    // 이름을 숨긴 것까지 포함해, 빈 교실에 있던 공유기가 뒤늦게 내보인 다른 주소는 원래 있던 것이다.
    val rest = others.filterNot { it.source == ScanSource.WIFI && isSameAccessPoint(it.key, baselineWifi) }
        // 확인함으로 뺀 이름 숨김 와이파이는 그 주소만 뺀다.
        .filterNot { isHiddenWifiGroup(it.groupKey) && it.acknowledgeKey in acknowledged }
    val flagged = mutableListOf<SignalGroup>()
    rest.groupBy { it.groupKey }.forEach { (key, members) ->
        // 지금 패킷이 오는 기기를 먼저 세운다. 기억만 남은 기기가 옛 세기로 대표가 되면
        // "지금 약한데 멀다"처럼 보인다.
        val sorted = members.sortedWith(compareBy<TrackedDevice> { it.isRecallOnly }.thenByDescending { it.rankRssi() })
        val before = baseline[key] ?: 0
        val wifi = sorted.first().source == ScanSource.WIFI
        // 주소 하나가 잠깐 겹친 것과, 여러 번 잡힌 증거를 구분한다.
        // 장기 기억만 남은 기기는 "지금 잡히는 수"에 넣지 않는다. 기억을 늘려도 헛경보가 나지 않게 한다.
        val established = members.count { it.confidence != SignalConfidence.OBSERVED && !it.isRecallOnly }
        // 몇 분에 걸쳐 반복 확인된 뒤 끊긴 기기는 목록에서 지우지 않는다. 다만 경고·진동·개수에는 넣지 않는다.
        val recalled = members.count { it.isRecallOnly }
        val hasLive = members.any { !it.isRecallOnly }
        // 지금 잡히는 기기의 신뢰도만 묶음의 신뢰도로 쓴다. 기억만 남은 기기가 "확인 필요"를 끌어올리면
        // 빈 교실에 있던 것까지 새 전파처럼 보인다.
        val confidence = (sorted.filterNot { it.isRecallOnly }.ifEmpty { sorted }).maxOf { it.confidence }
        val group = when {
            key in acknowledged -> null
            // 빈 교실에 없던 묶음은 바로 보인다. 와이파이 주소는 바뀌지 않으므로 바로 알린다.
            before == 0 -> SignalGroup(key, sorted, 0, alert = hasLive && (wifi || established > 0), confidence = confidence)
            // 이름을 숨긴 와이파이는 이름이 같다는 근거가 없다. 빈 교실 공유기의 주소는 위에서 이미 걸렀으므로
            // 남은 것은 모두 새 전파다. 주소가 바뀌지 않으므로 바로 알린다.
            isHiddenWifiGroup(key) -> SignalGroup(key, sorted, 0, alert = hasLive, confidence = confidence)
            // 같은 이름의 와이파이는 주소가 몇 개든 원래 있던 것이다.
            wifi -> null
            // 기억만 남은 기기도 "빈 교실보다 늘어난 것"이면 줄은 남긴다. 다만 지금 잡히지 않으므로 알리지 않는다.
            established + recalled > before -> SignalGroup(key, sorted, before, alert = established > before, confidence = confidence)
            else -> null
        }
        if (group != null) flagged += group
    }
    val byStrength = compareByDescending<SignalGroup> { it.strongest.rankRssi() }
    val (far, near) = flagged.sortedWith(byStrength).partition { (it.strongest.rankRssi()) < farThresholdDbm }
    return WatchView(
        wifi = near.filter { it.strongest.source == ScanSource.WIFI },
        bluetooth = near.filter { it.strongest.source != ScanSource.WIFI },
        far = far,
        findMy = findMyLive,
        knownGroups = knownGroups(devices, baseline, acknowledged, baselineWifi, samples),
    )
}

/**
 * 빈 교실 기록의 묶음을 고정된 순서로 나열하고, 그 뒤에 감시 중 원래 있던 것으로 판단된 묶음
 * (같은 공유기의 다른 주소, 확인함으로 표시한 것)을 처음 잡힌 순서로 붙인다. 사라져도 줄은 남는다.
 */
private fun knownGroups(devices: List<TrackedDevice>, baseline: Map<String, Int>, acknowledged: Set<String>, baselineWifi: Set<String>,
    samples: List<SnapshotSignal>): List<KnownGroup> {
    val byStrength = compareBy<TrackedDevice> { it.isRecallOnly }.thenByDescending { it.rankRssi() }
    val members = devices.filterNot { it.source == ScanSource.BLE && it.hints.primary == SignalHint.APPLE_FIND_MY }.groupBy { it.groupKey }
    fun row(key: String, sample: SnapshotSignal?): KnownGroup {
        val all = members[key].orEmpty()
        // 원래 있던 전파 줄은 "지금 잡히는지"만 보여 준다. 기억으로만 남은 기기는 여기에 넣지 않는다.
        return KnownGroup(key, sample, baseline[key] ?: 0,
            all.filter { it.presence != SignalPresence.HISTORY && !it.isRecallOnly }.sortedWith(byStrength),
            all.maxByOrNull { it.lastSeenMs })
    }
    val fromEmptyRoom = samples.map { row(it.groupKey, it) }
    val recorded = samples.map { it.groupKey }.toSet()
    val later = members.filter { (key, all) ->
        key !in recorded && (key in acknowledged || all.any { it.acknowledgeKey in acknowledged } ||
            all.any { it.source == ScanSource.WIFI && isSameAccessPoint(it.key, baselineWifi) })
    }.entries.sortedWith(compareBy({ entry -> entry.value.minOf { it.firstSeenMs } }, { it.key })).map { row(it.key, null) }
    return fromEmptyRoom + later
}
