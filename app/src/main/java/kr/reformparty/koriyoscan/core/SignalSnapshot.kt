package kr.reformparty.koriyoscan.core

/** A saved observation, scoped by radio transport rather than product name. */
data class SnapshotSignal(
    val address: String,
    val source: ScanSource,
    val name: String,
    val rssi: Double?,
    val manufacturer: String,
    val presence: SignalPresence = SignalPresence.RECENT,
    val manufacturerIds: Set<Int> = emptySet(),
    val hints: Set<SignalHint> = emptySet(),
    /** 주소가 바뀌어도 같은 기기를 묶기 위한 광고 지문. */
    val fingerprint: String? = null,
)

data class SignalSnapshot(
    val area: String,
    val capturedAtMs: Long,
    val simulation: Boolean,
    val signals: List<SnapshotSignal>,
    /** 여러 번 스윕하는 동안 묶음별로 동시에 가장 많이 잡힌 수. 비어 있으면 신호 목록에서 계산한다. */
    val groupMaxCounts: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun capture(
            area: String,
            capturedAtMs: Long,
            simulation: Boolean,
            devices: List<TrackedDevice>,
            groupMaxCounts: Map<String, Int> = emptyMap(),
        ): SignalSnapshot {
            val signals = devices.map { device ->
                SnapshotSignal(
                    address = device.key,
                    source = device.source,
                    name = device.displayName,
                    rssi = device.rssiEma,
                    manufacturer = device.identity.summary,
                    presence = device.presence,
                    manufacturerIds = device.manufacturerIds.toSet(),
                    hints = device.hints.toSet(),
                    fingerprint = device.fingerprint,
                )
            }
            return SignalSnapshot(area.trim(), capturedAtMs, simulation, signals, groupMaxCounts)
        }
    }
}
