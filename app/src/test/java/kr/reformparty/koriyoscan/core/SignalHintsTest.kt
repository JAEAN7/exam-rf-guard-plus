package kr.reformparty.koriyoscan.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalHintsTest {
    @get:Rule val directory = TemporaryFolder()

    private fun ad(type: Int, vararg data: Int) = byteArrayOf((data.size + 1).toByte(), type.toByte()) + data.map { it.toByte() }
    private val flags = ad(0x01, 0x06)

    @Test fun appearanceCategoryNamesTheDeviceKind() {
        assertEquals(setOf(SignalHint.WATCH), bleHints(flags + ad(0x19, 0xC1, 0x00)))
        assertEquals(setOf(SignalHint.GLASSES), bleHints(ad(0x19, 0xC0, 0x01)))
        assertEquals(setOf(SignalHint.EARPHONES), bleHints(ad(0x19, 0x41, 0x09)))
        assertEquals(setOf(SignalHint.HEARING_AID), bleHints(ad(0x19, 0x40, 0x0A)))
        assertEquals(setOf(SignalHint.PHONE), bleHints(ad(0x19, 0x40, 0x00)))
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0x19, 0x00, 0x00)))
    }

    @Test fun appleMessagesSeparateActiveDevicesEarbudsAndFindMyBeacons() {
        // Handoff(0x0C) 뒤에 Nearby Info(0x10)가 이어지는 켜진 아이폰의 광고
        assertEquals(setOf(SignalHint.APPLE_ACTIVE), bleHints(flags + ad(0xFF, 0x4C, 0x00, 0x0C, 0x02, 0xAA, 0xBB, 0x10, 0x02, 0x01, 0x18)))
        assertEquals(setOf(SignalHint.APPLE_AUDIO), bleHints(ad(0xFF, 0x4C, 0x00, 0x07, 0x02, 0x01, 0x02)))
        assertEquals(setOf(SignalHint.APPLE_FIND_MY), bleHints(ad(0xFF, 0x4C, 0x00, 0x12, 0x02, 0x00, 0x00)))
        // 다른 회사가 같은 종류 바이트를 써도 Apple로 읽지 않는다.
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0xFF, 0x75, 0x00, 0x10, 0x02, 0x01, 0x18)))
    }

    @Test fun fastPairWindowsAndPriorityAreRead() {
        assertEquals(setOf(SignalHint.FAST_PAIR), bleHints(ad(0x16, 0x2C, 0xFE, 0x01, 0x02, 0x03)))
        assertEquals(setOf(SignalHint.FAST_PAIR), bleHints(ad(0x03, 0x0F, 0x18, 0x2C, 0xFE)))
        assertEquals(setOf(SignalHint.WINDOWS), bleHints(ad(0xFF, 0x06, 0x00, 0x01, 0x09)))
        val both = bleHints(ad(0x19, 0xC1, 0x00) + ad(0xFF, 0x4C, 0x00, 0x10, 0x02, 0x01, 0x18))
        assertEquals(setOf(SignalHint.WATCH, SignalHint.APPLE_ACTIVE), both)
        assertEquals(SignalHint.WATCH, both.primary)
    }

    @Test fun truncatedOrEmptyAdvertisementIsIgnored() {
        assertEquals(emptySet<SignalHint>(), bleHints(null))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(0, 0, 0)))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(9, 0x19, 0xC1.toByte())))
        assertEquals(emptySet<SignalHint>(), bleHints(byteArrayOf(2, 0xFF.toByte(), 0x4C)))
        assertEquals(emptySet<SignalHint>(), bleHints(ad(0xFF, 0x4C, 0x00, 0x10)))
    }

    @Test fun classicClassOfDeviceIgnoresServiceBitsAndSeparatesCamerasFromEarphones() {
        // 기기는 오디오 서비스 비트(0x200000)를 함께 알린다. 그 비트 때문에 분류가 실패하면 안 된다.
        assertEquals(setOf(SignalHint.EARPHONES), classicHints(0x0400, 0x200418))
        assertEquals(setOf(SignalHint.EARPHONES), classicHints(0x2400, 0x200404))
        assertEquals(setOf(SignalHint.EARPHONES), classicHints(0x0400, 0x0410))
        // 비디오 카메라(0x30)·캠코더(0x34)는 이어폰이 아니라 카메라다.
        assertTrue(SignalHint.EARPHONES !in classicHints(0x0400, 0x0430))
        assertEquals(setOf(SignalHint.CAMERA), classicHints(0x0400, 0x0430))
        assertEquals(setOf(SignalHint.CAMERA), classicHints(0x0400, 0x0434))
        // 스피커·카오디오·셋톱·VCR도 이어폰이 아니다.
        assertEquals(setOf(SignalHint.FAST_PAIR), classicHints(0x0400, 0x0414))
        assertEquals(setOf(SignalHint.FAST_PAIR), classicHints(0x0400, 0x0420))
        assertEquals(setOf(SignalHint.FAST_PAIR), classicHints(0x0400, 0x042C))
        // 이미지 대분류: 카메라는 카메라, 프린터는 단정하지 않는다.
        assertEquals(setOf(SignalHint.CAMERA), classicHints(0x0600, 0x0608))
        assertEquals(emptySet<SignalHint>(), classicHints(0x0600, 0x0610))
        // 웨어러블: 손목시계와 안경만. 페이저를 시계로 부르지 않는다.
        assertEquals(setOf(SignalHint.WATCH), classicHints(0x0700, 0x0704))
        assertEquals(setOf(SignalHint.GLASSES), classicHints(0x0700, 0x0714))
        assertEquals(emptySet<SignalHint>(), classicHints(0x0700, 0x0708))
        // 건강 기기(대분류 0x09)를 보청기로 부르지 않는다.
        assertEquals(emptySet<SignalHint>(), classicHints(0x0900, 0x0904))
        assertEquals(emptySet<SignalHint>(), classicHints(null, null))
    }

    @Test fun phoneHotspotAndWifiDirectNamesAreCandidatesOnly() {
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("홍길동의 iPhone"))
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("Galaxy S24 1234"))
        assertEquals(setOf(SignalHint.HOTSPOT), wifiHints("AndroidAP_5521"))
        assertEquals(setOf(SignalHint.WIFI_DIRECT), wifiHints("DIRECT-a1-Galaxy S24"))
        assertEquals(emptySet<SignalHint>(), wifiHints("SCHOOL_WIFI_5G"))
        assertEquals(emptySet<SignalHint>(), wifiHints(null))
    }

    @Test fun hintsFlowFromObservationToSavedSnapshotAndEquipmentType() {
        val tracker = DeviceTracker()
        tracker.update(RfObservation("AA:BB:CC:00:00:01", ScanSource.BLE, null, -60, null, false, 1000, hints = setOf(SignalHint.APPLE_ACTIVE)))
        tracker.update(RfObservation("AA:BB:CC:00:00:01", ScanSource.BLE, null, -60, null, false, 2000, hints = setOf(SignalHint.WATCH)))
        tracker.update(RfObservation("AA:BB:CC:00:00:02", ScanSource.WIFI, "Galaxy S24", -90, null, false, 2000, hints = wifiHints("Galaxy S24")))
        val devices = tracker.snapshot(2000)
        val watch = devices.first { it.source == ScanSource.BLE }
        assertEquals(setOf(SignalHint.APPLE_ACTIVE, SignalHint.WATCH), watch.hints)

        val store = SnapshotStore(directory.root)
        store.save(listOf(SignalSnapshot.capture("A", 3000, false, devices)))
        val loaded = store.load().single().signals.associateBy { it.source }
        assertEquals(setOf(SignalHint.APPLE_ACTIVE, SignalHint.WATCH), loaded.getValue(ScanSource.BLE).hints)
    }
}
