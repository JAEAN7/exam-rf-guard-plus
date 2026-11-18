package com.classroom.rfdetector.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.radioKey
import com.classroom.rfdetector.core.wifiDetails
import com.classroom.rfdetector.core.wifiHints
import com.classroom.rfdetector.core.wpsDetails
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Read only completed scans; cached observations keep their actual observation time.
 *
 * 먼 기기를 조금이라도 더 잡기 위해 두 가지를 함께 쓴다.
 * 1. 탐색 요청은 앱마다 2분에 4회 수준으로 제한되므로, 제한에 걸리면 간격을 스스로 늘렸다 줄인다.
 * 2. 요청 사이에는 캐시된 결과를 훑는다. 다른 앱이나 시스템이 갱신해 둔 결과도 여기 들어 있어,
 *    요청 횟수를 쓰지 않고도 새로 잡힌 약한 전파를 먼저 발견할 수 있다.
 *
 * 캐시를 훑을 때는 "되살리기 상한"과 "신선도 상한"을 나눠 쓴다. 오래된 캐시 결과는
 * 기억으로 되살아나는 것과 별개이므로, 신선도 상한을 넘긴 결과는 아예 받지 않는다.
 * 받아 버리면 태어나자마자 "잠시 끊김"·"사라짐"이 되어 내부 상태만 어지럽힌다.
 */
class WifiScanner(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    /** 이번 탐색에서 끝까지 완료된 탐색 횟수. 빈 교실 기록이 충분한지 판단하는 데 쓴다. */
    @Volatile var completedScans: Int = 0
        private set
    /** 이번 탐색에서 받은 결과 수. 감시 품질 표시에 쓴다. */
    @Volatile var packetCount: Long = 0L
        private set
    /** 캐시 훑기만으로 찾아낸 새 결과 수. 요청 횟수를 쓰지 않고 얻은 수신이다. */
    @Volatile var cachedPicks: Long = 0L
        private set
    /** 마지막으로 끝까지 완료된 탐색 시각. 감시가 멈췄는지 판단하는 데 쓴다. */
    @Volatile var lastCompletedAtMs: Long? = null
        private set
    private var job: Job? = null
    private var cacheJob: Job? = null
    private var receiver: BroadcastReceiver? = null
    private val observedTimestamps = mutableMapOf<String, Long>()
    private var periodMs = 30_000L
    private var cacheWindowMs = 90_000L
    private var cacheFreshMs = 60_000L
    private var cachePollMs = 20_000L
    private var onObservation: ((RfObservation) -> Unit)? = null

    fun isEnabled(): Boolean = try { wifi.isWifiEnabled } catch (_: SecurityException) { false }

    /**
     * 시스템이 탐색 빈도를 제한하고 있는지. Android 10부터 기본이 켜져 있고, 제조사 설정이나
     * 개발자 옵션에서 끌 수 있다. 끌 수 있는 기기에서는 요청을 더 자주 보내도 되므로,
     * 주기를 칩·시스템이 허용하는 만큼 당긴다. 알 수 없으면 제한이 걸린 것으로 본다.
     */
    fun isScanThrottled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) wifi.isScanThrottleEnabled else true
    } catch (_: SecurityException) {
        true
    }

    fun start(
        onObs: (RfObservation) -> Unit,
        periodMs: Long = 30_000L,
        resetCounts: Boolean = true,
        cacheWindowMs: Long = 90_000L,
        cacheFreshMs: Long = 60_000L,
        cachePollMs: Long = 0L,
    ) {
        stop()
        this.periodMs = periodMs
        this.cacheWindowMs = cacheWindowMs
        this.cacheFreshMs = cacheFreshMs
        this.cachePollMs = cachePollMs
        onObservation = onObs
        lastError = null
        if (resetCounts) {
            completedScans = 0
            packetCount = 0L
            cachedPicks = 0L
            lastCompletedAtMs = null
            observedTimestamps.clear()
        }
        val startedAtElapsedUs = SystemClock.elapsedRealtimeNanos() / 1000
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (receiver !== this) return
                if (!intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    reportError("Wi-Fi 갱신 대기 · 시스템이 탐색 빈도를 제한할 수 있습니다")
                    return
                }
                try {
                    val now = System.currentTimeMillis()
                    val elapsedMs = SystemClock.elapsedRealtime()
                    lastError = null
                    completedScans++
                    lastCompletedAtMs = now
                    harvest(startedAtElapsedUs, elapsedMs, now, countAsPick = false)
                } catch (_: SecurityException) {
                    active = false
                    reportError("Wi-Fi 권한을 확인하세요 · 정확한 위치 권한이 필요합니다")
                }
            }
        }
        receiver = listener
        ContextCompat.registerReceiver(app, listener, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_EXPORTED)
        active = true
        job = scope.launch {
            while (isActive) {
                val requested = try {
                    wifi.startScan()
                } catch (_: SecurityException) {
                    active = false
                    reportError("Wi-Fi 탐색 권한이 없습니다")
                    break
                }
                if (!requested) reportError("Wi-Fi 갱신 대기 · Wi-Fi와 위치 설정을 확인하세요")
                // Android는 2분에 4회 수준으로 제한한다. 고정 주기는 경계에 걸리므로 조금 흔들어 준다.
                val jitter = if (periodMs <= 25_000L) 3_000L else 4_000L
                delay(periodMs + Random.nextLong(0L, jitter))
            }
        }
        if (cachePollMs > 0L) cacheJob = scope.launch {
            while (isActive) {
                delay(cachePollMs)
                val now = System.currentTimeMillis()
                try {
                    harvest(startedAtElapsedUs, SystemClock.elapsedRealtime(), now, countAsPick = true)
                } catch (_: SecurityException) {
                    break
                }
            }
        }
    }

    /** 탐색이 멈춘 것 같을 때 통계를 유지한 채 다시 건다. */
    fun restart(
        onObs: (RfObservation) -> Unit,
        periodMs: Long = this.periodMs,
        cacheWindowMs: Long = this.cacheWindowMs,
        cacheFreshMs: Long = this.cacheFreshMs,
        cachePollMs: Long = this.cachePollMs,
    ) {
        start(onObs, periodMs, resetCounts = false, cacheWindowMs = cacheWindowMs, cacheFreshMs = cacheFreshMs, cachePollMs = cachePollMs)
    }

    /** 스캔 결과를 읽으려면 정확한 위치 권한과(Android 13+) 주변 Wi-Fi 기기 권한이 필요하다. */
    private fun hasScanPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, "android.permission.NEARBY_WIFI_DEVICES") == PackageManager.PERMISSION_GRANTED
        return fine && nearby
    }

    /**
     * 캐시된 결과에서 이번 측정 시작 이후, 그리고 [cacheWindowMs] 안에 관측된 것만 꺼낸다.
     * 같은 주소의 같거나 이전 시각은 다시 세지 않으므로, 요청 탐색과 캐시 훑기가 겹쳐도 중복되지 않는다.
     *
     * [countAsPick]은 캐시 훑기 경로다. 이 경로에서는 [cacheFreshMs]보다 오래된 결과를 버린다.
     * 완료된 탐색이 돌려준 결과는 그 자체가 방금 잰 값이므로 신선도 상한을 따로 걸지 않는다.
     */
    @SuppressLint("MissingPermission")
    private fun harvest(startedAtElapsedUs: Long, elapsedMs: Long, nowMs: Long, countAsPick: Boolean) {
        // 권한이 도중에 회수될 수 있다. 앱의 준비 단계에서 허용했더라도 읽기 직전에 한 번 더 확인한다.
        if (!hasScanPermission()) return
        val emit = onObservation ?: return
        val windowMs = if (countAsPick) minOf(cacheWindowMs, cacheFreshMs) else cacheWindowMs
        val results = wifi.scanResults.filter {
            it.timestamp >= startedAtElapsedUs && elapsedMs - it.timestamp / 1000 in 0..windowMs
        }
        results.forEach { result ->
            val key = radioKey(ScanSource.WIFI, result.BSSID)
            val previousTimestamp = observedTimestamps[key]
            if (previousTimestamp != null && result.timestamp <= previousTimestamp) return@forEach
            observedTimestamps[key] = result.timestamp
            packetCount++
            if (countAsPick) cachedPicks++
            emit(
                RfObservation(
                    result.BSSID, ScanSource.WIFI, result.SSID?.takeIf { it.isNotBlank() }, result.level, null,
                    result.SSID.isNullOrBlank(), nowMs - (elapsedMs - result.timestamp / 1000),
                    hints = wifiHints(result.SSID),
                    details = details(result), fingerprint = wifiFingerprint(result),
                    dedupKey = "$key:${result.timestamp}"
                )
            )
        }
    }

    /**
     * 이름을 숨긴 와이파이를 서로 구분하기 위한 지문. BSSID 앞자리·주파수·보안/규격만 쓴다.
     * 이름이 있는 와이파이는 이름으로 이미 묶이므로 지문이 필요 없다.
     */
    private fun wifiFingerprint(result: ScanResult): String? {
        if (!result.SSID.isNullOrBlank()) return null
        val standard = if (Build.VERSION.SDK_INT >= 30) result.wifiStandard else 0
        val stable = "${result.BSSID.take(8).uppercase()}:${result.frequency}:${result.capabilities.orEmpty()}:$standard"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun details(result: ScanResult): Map<String, String> {
        val modern = Build.VERSION.SDK_INT >= 30
        val details = LinkedHashMap(wifiDetails(result.frequency, result.capabilities, if (modern) result.wifiStandard else null))
        // 부가 정보 블록이 없는 결과에서는 이 조회 자체가 실패할 수 있다.
        if (modern) runCatching { result.informationElements }.getOrNull().orEmpty().filter { it.id == 221 }.forEach { element ->
            val buffer = element.bytes
            details.putAll(wpsDetails(ByteArray(buffer.remaining()).also { buffer.get(it) }))
        }
        return details
    }

    fun stop() {
        active = false
        job?.cancel()
        job = null
        cacheJob?.cancel()
        cacheJob = null
        onObservation = null
        receiver?.let { app.unregisterReceiver(it) }
        receiver = null
    }

    private fun reportError(message: String) {
        lastError = message
    }
}
