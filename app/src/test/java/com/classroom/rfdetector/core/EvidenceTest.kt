package com.classroom.rfdetector.core

import org.junit.Assert.*
import org.junit.Test

class EvidenceTest {
    private fun ble(
        tracker: DeviceTracker,
        key: String,
        atMs: Long,
        rssi: Int = -90,
        dedupKey: String? = null,
        fingerprint: String? = null,
    ) = tracker.update(
        RfObservation(key, ScanSource.BLE, null, rssi, null, false, atMs,
            dedupKey = dedupKey, fingerprint = fingerprint)
    )

    @Test fun sparsePacketsOverMinutesStillReachLikely() {
        val tracker = DeviceTracker()
        // 30초 간격으로 두 번만 잡힌 먼 신호. 최근 점수는 낮지만 실제로 있는 기기다.
        ble(tracker, "far", 0, -96)
        ble(tracker, "far", 30_000, -95)
        assertEquals(SignalConfidence.LIKELY, tracker.snapshot(31_000).single().confidence)
    }

    @Test fun continuousStrongSignalBecomesConfirmed() {
        val tracker = DeviceTracker()
        repeat(12) { index -> ble(tracker, "near", index * 1_000L, -55) }
        assertEquals(SignalConfidence.CONFIRMED, tracker.snapshot(12_000).single().confidence)
    }

    @Test fun duplicateCallbackDoesNotDoubleCountEvidence() {
        val tracker = DeviceTracker()
        ble(tracker, "dup", 1_000, -70, dedupKey = "same-packet", fingerprint = "fp")
        ble(tracker, "dup", 1_000, -70, dedupKey = "same-packet", fingerprint = "fp")
        val device = tracker.snapshot(1_000).single()
        assertEquals(1, device.observationCount)
        assertEquals(1.0, device.evidenceScore, 0.001)
    }

    @Test fun extendedPresenceKeepsSparseBleVisibleLonger() {
        val tracker = DeviceTracker()
        tracker.setExtendedPresence(true)
        ble(tracker, "far", 0, -95)
        val later = tracker.snapshot(30_000).single()
        assertEquals(SignalPresence.QUIET, later.presence)
        assertEquals(SignalPresence.HISTORY, tracker.snapshot(90_000).single().presence)
    }

    @Test fun switchToMaximumRescoresSamplesAlreadyCollected() {
        val tracker = DeviceTracker()
        // 표준 모드에서 30초 간격으로 두 번 잡았다.
        ble(tracker, "far", 0, -95)
        ble(tracker, "far", 30_000, -95)
        val standard = tracker.snapshot(31_000).single()
        // 표준은 기억을 쓰지 않으므로 장기 기억이 비어 있다.
        assertEquals(0, standard.recall.hits)
        tracker.setProfile(SensitivityProfile.of(SensitivityMode.MAX))
        val max = tracker.snapshot(31_000).single()
        // 모드를 바꿔도 이미 쌓인 표본이 남아, 기억 판정이 처음부터 다시 시작되지 않는다.
        assertEquals(2, max.observationCount)
        assertEquals(2, max.recall.hits)
    }

    @Test fun recallSurvivesAfterTheSampleWindowExpires() {
        val tracker = DeviceTracker()
        tracker.setProfile(SensitivityProfile.of(SensitivityMode.MAX))
        listOf(0L, 61_000L, 122_000L).forEach { ble(tracker, "far", it, -95) }
        // 최대 감지의 최근 창(180초)을 훨씬 넘긴 시각에서도 장기 기억으로 목록에 남는다.
        val device = tracker.snapshot(600_000).single()
        assertEquals(SignalPresence.QUIET, device.presence)
        assertTrue(device.isRecallOnly)
        assertTrue("Three separate minutes", device.recall.activeMinutes >= 3)
        assertTrue(device.recall.hits >= 3)
    }

    @Test fun standardModeStillExpiresBleAfterFifteenSeconds() {
        val tracker = DeviceTracker()
        ble(tracker, "far", 0, -95)
        assertEquals(SignalPresence.HISTORY, tracker.snapshot(20_000).single().presence)
    }
}
