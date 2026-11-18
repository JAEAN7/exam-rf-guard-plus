package com.classroom.rfdetector.scanner

import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import com.classroom.rfdetector.core.RfObservation
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WifiScannerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner = WifiScanner(app, scope)
    private val observations = mutableListOf<RfObservation>()

    @Before fun grantScanPermissions() {
        // 스캐너는 결과를 읽기 전에 위치·주변 Wi-Fi 권한을 확인한다. 테스트는 허용된 상태를 흉내 낸다.
        shadowOf(app).grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.NEARBY_WIFI_DEVICES",
        )
    }

    @After fun close() { scanner.stop(); scope.cancel() }

    private fun result(address: String, ssid: String?, ageMs: Long = 0) = ScanResult().apply {
        BSSID = address
        SSID = ssid
        level = -60
        frequency = 2437
        timestamp = (SystemClock.elapsedRealtime() - ageMs) * 1000
    }

    private fun complete(success: Boolean = true) {
        app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).putExtra(WifiManager.EXTRA_RESULTS_UPDATED, success))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun hiddenSsidCanBeNullAndKeepsActualObservationTime() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        shadowOf(manager).setScanResults(listOf(result("00:11:22:33:44:55", null, 5_000)))
        complete()
        val observation = observations.single()
        assertNull(observation.name)
        assertTrue(observation.hidden)
        val ageMs = System.currentTimeMillis() - observation.timestampMs
        assertTrue("Cached result must retain its five-second age, was $ageMs ms", ageMs in 5_000..5_500)
    }

    @Test fun expiredAndFutureScanEntriesAreExcluded() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(listOf(result("fresh", "fresh"), result("expired", "expired", 90_001), result("future", "future", -1_000)))
        scanner.start(onObs = { observations.add(it) })
        complete()
        assertEquals(listOf("fresh"), observations.map { it.key })
    }

    @Test fun failedCompletionDoesNotReprocessCachedResults() {
        shadowOf(manager).setScanResults(listOf(result("cached", "cached")))
        scanner.start(onObs = { observations.add(it) })
        complete(false)
        assertTrue(observations.isEmpty())
        assertEquals(0, scanner.completedScans)
        assertNotNull(scanner.lastError)
    }

    @Test fun stopUnregistersReceiver() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        complete()
        scanner.stop()
        shadowOf(manager).setScanResults(listOf(result("after-stop", "after-stop")))
        complete()
        assertTrue(observations.isEmpty())
    }

    @Test fun duplicateAndOlderNotificationsDoNotCountAsNewSignalSamples() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        val original = result("same-ap", "same-ap")
        shadowOf(manager).setScanResults(listOf(original))
        scanner.start(onObs = { observations.add(it) })
        complete()
        complete()
        assertEquals(1, observations.size)
        shadowOf(manager).setScanResults(listOf(result("same-ap", "same-ap", 10_000)))
        complete()
        assertEquals(1, observations.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        shadowOf(manager).setScanResults(listOf(result("same-ap", "same-ap")))
        complete()
        assertEquals(2, observations.size)
        // Robolectric's elapsed clock can advance independently of the JVM wall clock.
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - observations.last().timestampMs) < 1000)
    }

    @Test fun cachedFramesFromBeforeThisSessionAreNotAssignedToTheNewRoom() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(listOf(result("previous-room", "old AP", 1000)))
        scanner.start(onObs = { observations.add(it) })
        complete()
        assertTrue(observations.isEmpty())
    }

    @Test fun queuedBroadcastForStoppedReceiverDoesNotRunInRestartedSession() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true))
        scanner.stop()
        scanner.start(onObs = { observations.add(it) })
        shadowOf(manager).setScanResults(listOf(result("new-room", "new AP")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(observations.isEmpty())
        complete()
        assertEquals(listOf("new-room"), observations.map { it.key })
    }

    @Test fun successfulEmptyCompletionClearsErrorButIsNotInventedDeviceReception() {
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) })
        assertTrue(scanner.active)
        complete(false)
        assertNotNull(scanner.lastError)
        assertEquals(0, scanner.completedScans)
        complete()
        assertNull(scanner.lastError)
        assertEquals(1, scanner.completedScans)
        assertTrue(observations.isEmpty())
        scanner.stop()
        assertFalse(scanner.active)
        scanner.start(onObs = { observations.add(it) })
        assertEquals(0, scanner.completedScans)
    }

    @Test fun cachePollKeepsFreshResultsAndDropsStaleOnesLongBeforeTheReviveWindow() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        shadowOf(manager).setScanResults(emptyList())
        scanner.start(onObs = { observations.add(it) }, cacheWindowMs = 180_000L, cacheFreshMs = 60_000L, cachePollMs = 20_000L)
        // 측정 시작 뒤 100초 동안은 캐시가 비어 있다. 그 전 결과는 이번 측정 이전 것이라 받지 않는다.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(100))
        // 두 결과 모두 되살리기 상한(180초) 안이지만 나이가 다르다.
        shadowOf(manager).setScanResults(listOf(result("fresh", "fresh", 1_000), result("stale", "stale", 90_000)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        // 신선도 상한 60초를 넘긴 캐시 결과는 받지 않는다. 받으면 태어나자마자 "사라짐"이 된다.
        assertEquals(listOf("fresh"), observations.map { it.key })
        assertEquals(1L, scanner.cachedPicks)
        // 완료된 탐색은 방금 잰 값을 돌려주므로 되살리기 상한까지 받는다.
        shadowOf(manager).setScanResults(listOf(result("completed", "completed", 90_000)))
        complete()
        assertTrue(observations.map { it.key }.contains("completed"))
    }
}

