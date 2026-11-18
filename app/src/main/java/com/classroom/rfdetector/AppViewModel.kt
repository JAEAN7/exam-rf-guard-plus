package com.classroom.rfdetector

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import com.classroom.rfdetector.core.DeviceIdentityResolver
import com.classroom.rfdetector.core.DeviceTracker
import com.classroom.rfdetector.core.DetectionEvent
import com.classroom.rfdetector.core.DistanceClass
import com.classroom.rfdetector.core.GeigerController
import com.classroom.rfdetector.core.MagneticEvent
import com.classroom.rfdetector.core.MagneticReading
import com.classroom.rfdetector.core.MagneticSample
import com.classroom.rfdetector.core.MagneticState
import com.classroom.rfdetector.core.PathLossEnvironment
import com.classroom.rfdetector.core.RateMeter
import com.classroom.rfdetector.core.magneticBeepIntervalMs
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.RssiFilter
import com.classroom.rfdetector.core.ScanHealth
import com.classroom.rfdetector.core.ScanSession
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.primary
import com.classroom.rfdetector.core.SensitivityMode
import com.classroom.rfdetector.core.SensitivityPreference
import com.classroom.rfdetector.core.SensitivityProfile
import com.classroom.rfdetector.core.PowerState
import com.classroom.rfdetector.core.chooseSensitivity
import com.classroom.rfdetector.core.wifiScanPeriodMs
import com.classroom.rfdetector.core.SignalConfidence
import com.classroom.rfdetector.core.SignalHint
import com.classroom.rfdetector.core.SignalPresence
import com.classroom.rfdetector.core.SignalSnapshot
import com.classroom.rfdetector.core.SilentMode
import com.classroom.rfdetector.core.SnapshotSignal
import com.classroom.rfdetector.core.SnapshotStore
import com.classroom.rfdetector.core.TrackedDevice
import com.classroom.rfdetector.core.WatchView
import com.classroom.rfdetector.core.baselineGroupCounts
import com.classroom.rfdetector.core.baselineSamples
import com.classroom.rfdetector.core.baselineWifiAddresses
import com.classroom.rfdetector.core.groupKey
import com.classroom.rfdetector.core.watchView
import com.classroom.rfdetector.scanner.BleScanner
import com.classroom.rfdetector.scanner.ClassicScanner
import com.classroom.rfdetector.scanner.MagneticScanner
import com.classroom.rfdetector.scanner.WifiScanner
import com.classroom.rfdetector.sim.RadioSimulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random

/** 감시가 완전하지 않은 이유. 해결 전까지는 해당 전파가 있어도 표시되지 않는다. */
enum class RadioProblem { BLUETOOTH_OFF, WIFI_OFF, LOCATION_OFF }

/** 빈 교실 기록 → 시험 중 감시의 상태를 화면 재생성과 무관하게 유지한다. 화면 밖에서는 수신을 멈춘다. */
@SuppressLint("StaticFieldLeak") // applicationContext만 보관한다.
class AppViewModel(private val app: Context) : ViewModel() {
    companion object {
        private val archiveDispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "rf-archive").apply { isDaemon = true } }.asCoroutineDispatcher()
        /** 자기장 변화 추이를 화면에 남겨 두는 표본 수. 10Hz 갱신이므로 약 5초다. */
        private const val MAGNETIC_HISTORY_MAX = 48
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val storageScope = CoroutineScope(SupervisorJob() + archiveDispatcher)
    private var closed = false

    val tracker = DeviceTracker()
    val geiger = GeigerController(app)
    val ble = BleScanner(app)
    val wifi = WifiScanner(app, scope)
    /** BLE 광고를 내지 않는 구형 기기를 위한 클래식 블루투스 보조 탐색. */
    val classic = ClassicScanner(app)
    /** 자기장 센서로 자성 금속·자석을 찾는 보조 탐지기. 전파를 쓰지 않아 무선을 꺼도 동작하고 권한도 필요 없다. */
    val magnetic = MagneticScanner(app)
    /** 가상 전파. 화면에서는 켤 수 없고 자동 테스트에서만 쓴다. */
    val sim = RadioSimulator()
    /** 회사 코드·주소 앞자리로 제조사를 찾는 등록부. 앱에 들어 있어 인터넷 없이 조회한다. */
    private var identityResolver = DeviceIdentityResolver(emptyMap(), emptyMap())

    private val _devices = MutableStateFlow<List<TrackedDevice>>(emptyList())
    val devices: StateFlow<List<TrackedDevice>> = _devices
    val scanning = MutableStateFlow(false)
    val simMode = MutableStateFlow(false)
    val geigerOn = MutableStateFlow(false)
    val scanSession = MutableStateFlow<ScanSession?>(null)
    val healthTick = MutableStateFlow(0L)

    /** 지금 감시에 쓰는 빈 교실 기록. null이면 아직 측정 전이다. */
    val baselineSnapshot = MutableStateFlow<SignalSnapshot?>(null)
    val baselineSet = MutableStateFlow(false)
    /** 앱을 다시 열었을 때 이어 쓸 수 있는, 마지막으로 저장한 빈 교실 기록. */
    val savedBaseline = MutableStateFlow<SignalSnapshot?>(null)
    val snapshotBusy = MutableStateFlow(true)
    val snapshotError = MutableStateFlow<String?>(null)
    private val snapshotStore = SnapshotStore(app.filesDir)
    private var baselineGroups: Map<String, Int> = emptyMap()
    private var baselineWifi: Set<String> = emptySet()
    private var baselineSamples: List<SnapshotSignal> = emptyList()

    /** 빈 교실 기록과 비교해 지금 잡히는 전파를 묶음으로 나눈 결과. */
    val watch = MutableStateFlow(WatchView())
    val problems = MutableStateFlow<List<RadioProblem>>(emptyList())
    /** 지금 수신이 실제로 돌아가고 있는지 보여 주는 품질 상태. */
    val health = MutableStateFlow(ScanHealth())
    /** [FORK 1.1.2] Android가 와이파이 탐색을 제한 중인지. 개발자 옵션에서 끄면 더 자주 확인한다. */
    val wifiThrottled = MutableStateFlow(false)
    // [FORK 1.1.2] 감도 3택(자동/표준(최대)/배터리 절약)과 선택 저장. 원본은 표준/최대 2택이었고 저장하지 않았다.
    /** 사용자가 고른 감도 선호(자동/표준(최대)/배터리 절약). 앱을 다시 켜도 SharedPreferences에서 되살린다. */
    val sensitivityPreference = MutableStateFlow(SensitivityPreference.AUTO)
    /** 지금 실제로 적용 중인 감도 모드. 자동이면 전원 상태에 따라 바뀐다. */
    val sensitivity = MutableStateFlow(SensitivityMode.MAX)
    /** 감도 모드가 실제로 바꾸는 값. 스캐너·증거 창·판정이 같은 값을 보게 한다. */
    @Volatile private var profileCache = SensitivityProfile.of(SensitivityMode.MAX)
    private val profile: SensitivityProfile get() = profileCache
    /** 감도 선택만 저장하는 작은 설정. 개인정보는 담지 않는다. */
    private val settings = app.getSharedPreferences("koriyo-settings", Context.MODE_PRIVATE)
    /** 자동 모드를 다시 평가할 시각. 배터리·발열을 10Hz로 읽지 않으려고 20초에 한 번만 본다. */
    private var lastAutoCheckMs = 0L

    /** 자기장 감지 상태. 기본 화면에는 세기와 문구만 쓰고, 숫자는 "자세히"를 펼쳤을 때만 보여 준다. */
    val magneticReading = MutableStateFlow(MagneticReading())
    /** 최근 변화량(µT) 추이. "자세히"의 그래프에 쓴다. */
    val magneticHistory = MutableStateFlow<List<Double>>(emptyList())
    /** 자기장 감지 기록. 감지가 시작된 시각을 키로 계속 갱신한다. */
    val magneticEvents = MutableStateFlow<List<MagneticEvent>>(emptyList())
    /** 자기장 감지 진동 알림. 새 전파 알림과 따로 끌 수 있다. */
    val magneticAlert = MutableStateFlow(true)
    /** 금속·자석 탐지를 켠 상태. 전파 감시([scanning])와 무관하게 따로 켜고 끈다. */
    val magneticActive = MutableStateFlow(false)
    /** 금속·자석 탐지 감도. 전파 감도와 따로 둬서 두 기능을 독립적으로 쓴다. */
    val magneticMode = MutableStateFlow(SensitivityMode.STANDARD)
    /**
     * 금속 탐지기의 삐 소리. 진동 알림과 따로 켜고 끈다.
     * 시험장에서 갑자기 울리지 않도록 기본은 꺼 둔다.
     */
    val magneticSound = MutableStateFlow(false)
    private val magneticLog = LinkedHashMap<Long, MagneticEvent>()
    private val magneticHistoryList = ArrayDeque<Double>()
    private var alertedMagneticRun = 0L

    /** 기기에 자기장 센서가 있는지, 최고 표본률이 얼마인지, 보정 안 된 센서를 쓰는지. */
    val magneticAvailable: Boolean get() = magnetic.available
    val magneticFastestHz: Int get() = magnetic.fastestHz
    val magneticUncalibrated: Boolean get() = magnetic.uncalibrated
    /** 시스템이 알려 주는 자기장 센서 정확도([android.hardware.SensorManager.SENSOR_STATUS_*]). */
    val magneticAccuracy: Int get() = magnetic.accuracy
    val magneticAccuracyKnown: Boolean get() = magnetic.accuracyKnown
    /** 감시 중 새로 잡힌 묶음의 기록. 판정이 아니라 시간·세기 기록이다. 앱을 닫으면 지워진다. */
    val detections = MutableStateFlow<List<DetectionEvent>>(emptyList())
    private val detectionLog = LinkedHashMap<String, DetectionEvent>()
    /** 감시 중 새로 확인된 전파를 짧은 진동으로 알릴지. 기본은 켜 둔다. */
    val alertVibrate = MutableStateFlow(true)
    private val vibratedGroups = mutableSetOf<String>()
    /** 거리 추정에 쓰는 실내 환경. 사용자가 고른다. */
    val pathLoss = MutableStateFlow(PathLossEnvironment.CLASSROOM)
    /** 1m에서 측정한 기기별 기준 세기(묶음 키 → dBm). */
    val calibration = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val bleRate = RateMeter()
    private val wifiRate = RateMeter()
    private val classicRate = RateMeter()
    private val baselineMaxCounts = mutableMapOf<String, Int>()
    private var problemsCheckedAtMs = 0L
    private var detectionsRecordedAtMs = 0L
    private var batteryReadAtMs = 0L
    private var batteryPercentCache: Int? = null
    private var thermalStatusCache = 0
    private var lastBleRestartAtMs = 0L
    private var lastWifiRestartAtMs = 0L
    private var lastClassicRestartAtMs = 0L
    /** 위치 찾기 대상(trackingKey). 지정하면 근접 진동이 이 기기를 따른다. */
    val findTarget = MutableStateFlow<String?>(null)
    /** 감독관이 "확인함"으로 표시한 묶음(groupKey). 이번 감시 동안만 유지한다. */
    val acknowledged = MutableStateFlow<Set<String>>(emptySet())
    private var pausedAtMs: Long? = null
    private val silent = SilentMode(app)
    /** 방해 금지 권한이 없어 휴대폰을 자동으로 무음으로 바꾸지 못하는 상태. */
    val silenceMissing = MutableStateFlow(!silent.allowed())
    /** 비행기 모드가 꺼져 있어 전화·문자가 들어올 수 있는 상태. 앱이 직접 켤 수는 없다. */
    val airplaneOff = MutableStateFlow(airplaneModeOff())

    private var simJob: Job? = null
    private var refreshJob: Job? = null
    private var magneticJob: Job? = null
    /** 금속 탐지 화면용 갱신 주기. 전파 감시가 꺼져 있어도 이것만 따로 돈다. */
    private var magneticTickJob: Job? = null
    /** 연속 삐 소리 루프. 소리를 끄거나 탐지를 끝내면 취소한다. */
    private var magneticBeepJob: Job? = null
    /** 화면이 뒤로 갔다 와도 사용자가 켜 둔 금속 탐지를 이어 갈지 기억한다. */
    private var magneticWanted = false

    init {
        // 감도 선택을 되살린다. 자동이면 지금 전원 상태로 실제 모드를 정한다.
        val savedPreference = runCatching {
            SensitivityPreference.valueOf(settings.getString("sensitivity", null) ?: "")
        }.getOrNull() ?: SensitivityPreference.AUTO
        sensitivityPreference.value = savedPreference
        applySensitivity(chooseSensitivity(savedPreference, readPowerState()), restart = false, refreshAfter = false)
        scope.launch {
            identityResolver = withContext(Dispatchers.IO) {
                fun rows(file: String) = app.assets.open(file).bufferedReader().useLines { lines ->
                    lines.filter { it.contains('	') }.associate { line -> line.substringBefore('	') to line.substringAfter('	') }
                }
                DeviceIdentityResolver(rows("mac-vendors.tsv"), rows("bluetooth-companies.tsv").mapKeys { it.key.toInt() })
            }
        }
        scope.launch {
            try {
                savedBaseline.value = withContext(archiveDispatcher) { snapshotStore.load() }.filter { !it.simulation }.maxByOrNull { it.capturedAtMs }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshotError.value = "저장된 빈 교실 기록을 읽지 못했습니다. 새로 측정해 주세요."
            } finally { snapshotBusy.value = false }
        }
    }

    /** API 레벨별로 런타임 요청이 필요한 권한 목록 */
    fun requiredPermissions(): List<String> {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            p += "android.permission.BLUETOOTH_SCAN"
            p += "android.permission.BLUETOOTH_CONNECT"
        }
        p += "android.permission.ACCESS_COARSE_LOCATION"
        p += "android.permission.ACCESS_FINE_LOCATION"
        if (Build.VERSION.SDK_INT >= 33) p += "android.permission.NEARBY_WIFI_DEVICES"
        return p
    }

    /** WifiManager 스캔 결과에는 위치 서비스가 필요하다. */
    fun locationServicesOff(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return !LocationManagerCompat.isLocationEnabled(lm)
    }

    fun startScan() = begin(resumedPauseAtMs = null)

    /** 앱이 화면 밖으로 나가면 수신을 멈추되, 돌아오면 같은 감시를 이어 간다. */
    fun pauseForBackground() {
        if (!scanning.value) return
        stopScan(keepSilent = true)
        pausedAtMs = System.currentTimeMillis()
    }

    fun resumeFromBackground() {
        begin(pausedAtMs ?: return)
    }

    private fun begin(resumedPauseAtMs: Long?) {
        if (scanning.value || closed) return
        pausedAtMs = null
        val paused = scanSession.value?.takeIf { resumedPauseAtMs != null }
        if (paused != null) {
            scanSession.value = paused.copy(pauses = paused.pauses + 1,
                pausedMs = paused.pausedMs + (System.currentTimeMillis() - resumedPauseAtMs!!).coerceAtLeast(0))
        } else {
            acknowledged.value = emptySet()
            tracker.clear()
            _devices.value = emptyList()
            watch.value = WatchView()
            baselineMaxCounts.clear()
            bleRate.clear()
            wifiRate.clear()
            classicRate.clear()
            detectionLog.clear()
            detections.value = emptyList()
            detectionsRecordedAtMs = 0L
            vibratedGroups.clear()
            lastBleRestartAtMs = 0L
            lastWifiRestartAtMs = 0L
            lastClassicRestartAtMs = 0L
            scanSession.value = ScanSession(UUID.randomUUID().toString(), System.currentTimeMillis(), "시험실")
        }
        if (sensitivityPreference.value == SensitivityPreference.AUTO) {
            applySensitivity(chooseSensitivity(SensitivityPreference.AUTO, readPowerState()), restart = false, refreshAfter = false)
        }
        // [FORK 1.1.2] 개발자 옵션의 탐색 제한 상태를 시작 시점에 반영한다.
        wifiThrottled.value = wifi.isScanThrottled()
        tracker.setProfile(profile)
        applyBaseline()
        scanning.value = true
        checkExamMode()
        if (simMode.value) {
            if (paused == null) sim.reset()
            simJob = scope.launch {
                while (isActive) {
                    sim.tick().forEach { onObservation(it) }
                    delay(250)
                }
            }
        } else {
            if (ble.isUsable()) ble.start(::onObservation, bleRefreshMs())
            wifi.start(::onObservation, wifiPeriodMs(), cacheWindowMs = profile.wifiCacheWindowMs, cacheFreshMs = profile.wifiCacheFreshMs, cachePollMs = profile.wifiCachePollMs)
            if (classic.isUsable()) classic.start(::onObservation, classicPeriodMs(), classicSweepMs())
        }
        // 금속·자석 탐지는 전파 감시와 별개 화면에서 따로 켜고 끈다. 여기서는 건드리지 않는다.
        refreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(100)
            }
        }
    }

    private fun onObservation(obs: RfObservation) {        if (!scanning.value) return
        val now = System.currentTimeMillis()
        when (obs.source) {
            ScanSource.BLE -> bleRate.mark(now)
            ScanSource.WIFI -> wifiRate.mark(now)
            ScanSource.CLASSIC -> classicRate.mark(now)
        }
        tracker.update(obs)
    }

    /**
     * 가상 자기장. 조용한 기준 위에 12초마다 3초 동안 자성 물체가 지나가는 상황을 만든다.
     * 화면·기록·알림이 실제 센서와 같은 분석 경로를 타는지 확인하는 데 쓴다.
     */
    private fun startMagneticSimulation() {
        magneticJob?.cancel()
        magneticJob = scope.launch {
            val start = System.currentTimeMillis()
            var elapsed = 0L
            while (isActive) {
                val phase = elapsed % 12_000L
                val bump = if (phase in 4_000L..7_000L) 3.0 else 0.0
                val noise = (Random.nextDouble() - 0.5) * 0.06
                magnetic.feed(
                    MagneticSample(
                        x = 24.0 + bump + noise,
                        y = -31.0 + noise,
                        z = 18.0 + noise,
                        atMs = start + elapsed,
                    )
                )
                delay(50)
                elapsed += 50
            }
        }
    }

    /**
     * 자기장 분석 결과를 화면 상태로 옮기고, 감지가 확정되면 알림과 기록을 남긴다.
     * 분석 자체는 센서 주기(수십~수백 Hz)로 돌지만, 화면과 기록은 전용 주기의 10Hz로 충분하다.
     */
    private fun updateMagnetic() {
        val reading = magnetic.latest
        magneticReading.value = reading
        if (!magneticActive.value) return
        magneticHistoryList.addLast(reading.delta)
        while (magneticHistoryList.size > MAGNETIC_HISTORY_MAX) magneticHistoryList.removeFirst()
        magneticHistory.value = magneticHistoryList.toList()
        if (reading.state != MagneticState.CLEAR && reading.runStartMs > 0L) {
            val previous = magneticLog[reading.runStartMs]
            magneticLog[reading.runStartMs] = MagneticEvent(
                startedAtMs = reading.runStartMs,
                lastSeenMs = System.currentTimeMillis(),
                peakDeltaMicroTesla = maxOf(previous?.peakDeltaMicroTesla ?: 0.0, reading.peakDelta),
                durationMs = maxOf(previous?.durationMs ?: 0L, reading.durationMs),
                repeats = maxOf(previous?.repeats ?: 0, reading.repeats),
            )
            while (magneticLog.size > 50) magneticLog.remove(magneticLog.keys.first())
            // 임계에 못 미친 흔들림은 기록에 남기지 않는다. 기록은 "확인해 볼 만한 변화"만 담는다.
            val threshold = reading.thresholdMicroTesla
            magneticEvents.value = magneticLog.values
                .filter { it.peakDeltaMicroTesla >= threshold }
                .sortedByDescending { it.lastSeenMs }
        }
        // 감지가 확정된 순간에 한 번만 울린다. 같은 변화가 이어지는 동안에는 다시 울리지 않는다.
        if (reading.state == MagneticState.DETECTED && magneticAlert.value &&
            reading.runStartMs > 0L && reading.runStartMs != alertedMagneticRun
        ) {
            alertedMagneticRun = reading.runStartMs
            // 진동은 "진동 알림"을 켰을 때 한 번 울리고, 소리는 "삐 소리"를 켰을 때만 연속 루프가 낸다.
            geiger.pulse()
        }
    }

    /** 주변 환경이 바뀌었을 때(다른 교실로 이동 등) 기준값을 다시 잡는다. */
    fun calibrateMagnetic() {
        magnetic.calibrate()
        magneticReading.value = MagneticReading(state = MagneticState.CALIBRATING)
    }

    fun setMagneticAlert(on: Boolean) {
        magneticAlert.value = on
    }

    fun clearMagneticEvents() {
        magneticLog.clear()
        magneticEvents.value = emptyList()
    }

    /**
     * 금속·자석 탐지를 따로 켠다. 전파 감시([scanning])와 무관하게 동작하며, 무선·권한·비행기 모드를 요구하지 않는다.
     * 가상 전파로 도는 자동 테스트에서도 같은 분석 경로를 타도록 가상 표본을 쓴다.
     */
    fun startMagnetic() {
        if (magneticActive.value || closed) return
        if (!simMode.value && !magnetic.available) {
            magneticReading.value = MagneticReading(state = MagneticState.OFFLINE)
            return
        }
        magneticWanted = true
        magnetic.setProfile(magneticMode.value)
        magnetic.calibrate()
        if (simMode.value) {
            startMagneticSimulation()
        } else if (!magnetic.start()) {
            magneticWanted = false
            magneticReading.value = MagneticReading(state = MagneticState.OFFLINE)
            return
        }
        magneticActive.value = true
        // 새로 켤 때 이전 추이 그래프가 남아 있으면 방금 잰 값처럼 보인다. 기록은 남기고 그래프만 비운다.
        magneticHistoryList.clear()
        magneticHistory.value = emptyList()
        magneticReading.value = magnetic.latest
        magneticTickJob?.cancel()
        magneticTickJob = scope.launch {
            while (isActive) {
                updateMagnetic()
                delay(100)
            }
        }
        if (magneticSound.value) startMagneticBeep()
    }

    /** 금속 탐지 화면을 닫을 때 부른다. 센서를 떼고 기록과 알림 설정은 남긴다. */
    fun stopMagnetic() {
        magneticWanted = false
        haltMagnetic()
    }

    /** 앱이 화면 밖으로 나가는 동안 센서만 잠시 뗀다. 돌아오면 [resumeMagnetic]이 이어 간다. */
    fun pauseMagnetic() = haltMagnetic()

    /** 사용자가 켜 둔 채 앱으로 돌아왔으면 금속 탐지를 다시 켠다. */
    fun resumeMagnetic() {
        if (magneticWanted) startMagnetic()
    }

    private fun haltMagnetic() {
        stopMagneticBeep()
        magneticTickJob?.cancel(); magneticTickJob = null
        magneticJob?.cancel(); magneticJob = null
        if (!magneticActive.value) return
        magneticActive.value = false
        magnetic.stop()
        magneticReading.value = MagneticReading(state = MagneticState.OFFLINE)
    }

    /** 금속 탐지 감도. 바뀌면 주변 기준을 다시 잡는다. */
    fun setMagneticMode(mode: SensitivityMode) {
        if (magneticMode.value == mode) return
        magneticMode.value = mode
        magnetic.setProfile(mode)
        if (magneticActive.value) magnetic.calibrate()
    }

    /**
     * 실제 금속 탐지기처럼 삐 소리를 켜고 끈다.
     * 켜 두면 신호가 세질수록 울리는 간격이 짧아지고, 조용할 때는 울리지 않는다.
     */
    fun setMagneticSound(on: Boolean) {
        magneticSound.value = on
        if (on) {
            if (magneticActive.value) startMagneticBeep()
        } else {
            stopMagneticBeep()
        }
    }

    private fun startMagneticBeep() {
        magneticBeepJob?.cancel()
        magneticBeepJob = scope.launch {
            while (isActive) {
                val r = magneticReading.value
                val sounding = r.state == MagneticState.TRANSIENT || r.state == MagneticState.DETECTED
                if (!sounding) {
                    // 조용할 때는 울리지 않는다. 실제 탐지기도 신호가 없으면 소리가 없다.
                    delay(150)
                    continue
                }
                geiger.beep(60)
                // 신호가 세질수록 간격이 짧아진다(650ms → 130ms).
                delay(magneticBeepIntervalMs(r.strength))
            }
        }
    }

    private fun stopMagneticBeep() {
        magneticBeepJob?.cancel()
        magneticBeepJob = null
    }

    private fun airplaneModeOff() = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 0

    /** 비행기 모드, 방해 금지 권한, 블루투스·와이파이·위치 상태를 다시 확인한다. 권한을 방금 허용했다면 바로 무음으로 바꾼다. 가상 전파로 도는 자동 테스트에서는 휴대폰 설정을 건드리지 않는다. */
    fun checkExamMode() {
        silenceMissing.value = !silent.allowed()
        airplaneOff.value = airplaneModeOff()
        problems.value = radioProblems()
        if (scanning.value && !simMode.value) silent.on()
    }

    /** keepSilent: 앱을 잠시 벗어난 동안에도 시험 중이므로 무음을 유지한다. */
    fun stopScan(keepSilent: Boolean = false) {
        if (!keepSilent) silent.off()
        if (!scanning.value) return
        refresh()
        scanning.value = false
        simJob?.cancel(); simJob = null
        refreshJob?.cancel(); refreshJob = null
        ble.stop()
        wifi.stop()
        classic.stop()
        // 금속·자석 탐지는 별개 화면이므로 전파 감시를 끝내도 계속 돈다.
        if (geigerOn.value) setGeiger(false)
    }

    private fun applyBaseline() {
        val baseline = baselineSnapshot.value?.takeIf { scanSession.value != null && it.simulation == simMode.value }
        baselineSet.value = baseline != null
        baselineGroups = baseline?.let(::baselineGroupCounts) ?: emptyMap()
        baselineWifi = baseline?.let(::baselineWifiAddresses) ?: emptySet()
        baselineSamples = baseline?.let { baselineSamples(it) } ?: emptyList()
    }

    /** 지금까지 잡힌 전파 전부를 이 교실에 원래 있던 것으로 저장하고 감시를 시작한다. */
    fun takeBaseline() {
        val session = scanSession.value ?: return
        if (!scanning.value || snapshotBusy.value || devices.value.isEmpty()) return
        refresh()
        val snapshot = SignalSnapshot.capture(
            session.area,
            System.currentTimeMillis(),
            simMode.value,
            devices.value,
            baselineMaxCounts.toMap(),
        )
        snapshotBusy.value = true
        snapshotError.value = null
        storageScope.launch {
            try {
                // 마지막 빈 교실 기록 하나만 보관한다.
                snapshotStore.save(listOf(snapshot))
                withContext(Dispatchers.Main.immediate) {
                    if (!snapshot.simulation) savedBaseline.value = snapshot
                    if (!closed && scanSession.value?.id == session.id) {
                        baselineSnapshot.value = snapshot
                        applyBaseline()
                        refresh()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshotError.value = "빈 교실 기록을 저장하지 못했습니다. 다시 눌러 주세요."
            } finally { snapshotBusy.value = false }
        }
    }

    /** 앱을 다시 연 뒤, 마지막으로 저장한 빈 교실 기록으로 바로 감시한다. */
    fun useSavedBaseline() {
        val saved = savedBaseline.value ?: return
        baselineSnapshot.value = saved
        applyBaseline()
    }

    /** 빈 교실 기록을 비운다. 다음 시작은 1단계 측정부터다. */
    fun resetBaseline() {
        baselineSnapshot.value = null
        watch.value = WatchView()
        baselineMaxCounts.clear()
        detectionLog.clear()
        detections.value = emptyList()
        vibratedGroups.clear()
        applyBaseline()
    }

    /** 빈 교실 기록과 목록을 비우고 새 측정을 바로 시작한다. */
    fun restartFromEmptyRoom() {
        stopScan()
        resetBaseline()
        startScan()
    }

    fun acknowledge(groupKey: String) {
        acknowledged.value += groupKey
        refresh() // 다음 갱신 주기를 기다리지 않고 목록에서 바로 뺀다.
    }

    fun setGeiger(on: Boolean) {
        if (on && !scanning.value) return
        geigerOn.value = on
        if (on) geiger.start() else geiger.stop()
    }

    /** 위치 찾기에서 1m 거리 기준 세기를 잡아 둔다. 주소가 바뀌어도 같은 묶음에 적용된다. */
    fun calibrateAtOneMeter(trackingKey: String): Boolean {
        val device = _devices.value.firstOrNull { it.trackingKey == trackingKey } ?: return false
        val rssi = device.rssiMedian ?: device.rssiEma ?: return false
        calibration.value = calibration.value + (device.groupKey to rssi)
        refresh()
        return true
    }

    fun clearCalibration() {
        calibration.value = emptyMap()
        refresh()
    }

    fun setPathLoss(environment: PathLossEnvironment) {
        pathLoss.value = environment
        refresh()
    }

    /** [FORK 1.1.2] 감도 선호를 바꾼다. 선택은 저장해 다음 실행에도 남기고, 자동이면 지금 전원 상태로 실제 모드를 정한다. */
    fun setSensitivityPreference(preference: SensitivityPreference) {
        sensitivityPreference.value = preference
        settings.edit().putString("sensitivity", preference.name).apply()
        applySensitivity(chooseSensitivity(preference, readPowerState()))
    }

    /** 표준 ↔ 최대 감지를 직접 고르는 옛 경로. 각각 배터리 절약·최대 선호로 저장된다. */
    fun setSensitivity(mode: SensitivityMode) = setSensitivityPreference(
        if (mode == SensitivityMode.MAX) SensitivityPreference.MAX else SensitivityPreference.SAVER
    )

    /** 실제 모드를 적용한다. 스캐너·증거 창·판정이 같은 프로파일을 보게 한다. */
    private fun applySensitivity(mode: SensitivityMode, restart: Boolean = true, refreshAfter: Boolean = true) {
        if (sensitivity.value != mode) {
            sensitivity.value = mode
            profileCache = SensitivityProfile.of(mode)
            tracker.setProfile(profileCache)
            if (restart && scanning.value && !simMode.value) {
                ble.start(::onObservation, bleRefreshMs())
                wifi.restart(::onObservation, wifiPeriodMs(), cacheWindowMs = profile.wifiCacheWindowMs, cacheFreshMs = profile.wifiCacheFreshMs, cachePollMs = profile.wifiCachePollMs)
                if (classic.isUsable()) classic.start(::onObservation, classicPeriodMs(), classicSweepMs())
            }
        }
        if (refreshAfter) refresh()
    }

    /** [FORK 1.1.2] 자동 감도가 쓰는 기기 전원 상태. 배터리·열 값을 못 읽는 기기에서는 최대 감지를 유지한다(탐지 우선). */
    private fun readPowerState(): PowerState {
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val charging = battery?.let { runCatching { it.isCharging }.getOrDefault(false) } ?: false
        val capacity = battery?.let { runCatching { it.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1) } ?: -1
        val saving = power?.isPowerSaveMode ?: false
        val hot = power?.let { runCatching { it.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE }.getOrDefault(false) } ?: false
        return PowerState(charging = charging, capacityPercent = capacity, hotOrSaving = saving || hot)
    }

    fun clearDetections() {
        detectionLog.clear()
        detections.value = emptyList()
    }

    fun setAlertVibrate(on: Boolean) {
        alertVibrate.value = on
    }

    /**
     * 와이파이 탐색 주기. Android는 2분에 4회 수준으로 제한하므로, 제한이 걸린 기기에서는
     * 그보다 잦은 요청이 거절되어 "갱신 대기"만 쌓인다. 개발자 옵션에서 제한을 끈 기기에서는
     * 표준(최대)에서 10초까지 당겨 받을 수 있는 만큼 받는다([wifiScanPeriodMs]).
     */
    private fun wifiPeriodMs(): Long = wifiScanPeriodMs(sensitivity.value, wifi.isScanThrottled())
    private fun classicPeriodMs(): Long = if (sensitivity.value == SensitivityMode.MAX) 60_000L else 150_000L
    private fun bleRefreshMs(): Long = profile.bleRefreshMs
    /** 클래식 탐색을 한 번에 붙잡는 시간. 최대 감지에서 늘려 늦게 응답하는 먼 기기를 더 잡는다. */
    private fun classicSweepMs(): Long = profile.classicSweepMs

    /** 자동 테스트용: 실제 무선 대신 가상 전파를 쓴다. */
    fun setSim(on: Boolean) {
        if (simMode.value == on) return
        if (scanning.value) stopScan()
        simMode.value = on
        tracker.clear()
        _devices.value = emptyList()
        scanSession.value = null
        resetBaseline()
    }

    fun simAddDevice() {
        if (scanning.value && simMode.value) sim.addDevice()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        // [FORK 1.1.2] 자동 감도는 20초에 한 번만 다시 본다. 배터리·발열 값을 10Hz로 읽지 않는다.
        if (sensitivityPreference.value == SensitivityPreference.AUTO && now - lastAutoCheckMs >= 20_000L) {
            lastAutoCheckMs = now
            applySensitivity(chooseSensitivity(SensitivityPreference.AUTO, readPowerState()), restart = true, refreshAfter = false)
        }
        healthTick.value = now
        val calibrationNow = calibration.value
        val pathN = pathLoss.value.n
        val list = tracker.snapshot(now, includeInactive = true).map { d ->
            val reference = calibrationNow[d.groupKey] ?: d.referenceRssiAt1m
            val rssi = d.rssiMedian ?: d.rssiEma
            val distance = rssi?.let { RssiFilter.distanceM(it, reference ?: RssiFilter.DEFAULT_TX_DBM, pathN) }
            d.copy(
                identity = identityResolver.resolve(d.key, d.displayName, setOf(d.source), d.manufacturerIds),
                referenceRssiAt1m = reference,
                distanceClass = RssiFilter.classify(distance),
            )
        }
        _devices.value = list
        if (scanning.value && !baselineSet.value) {
            // 빈 교실 기록 중 묶음별로 동시에 가장 많이 잡힌 수를 기억한다. 나중에 한 대 더 켜졌는지 비교하는 기준이다.
            list.filter {
                it.presence != SignalPresence.HISTORY && !it.isRecallOnly &&
                    !(it.source == ScanSource.BLE && it.hints.primary == SignalHint.APPLE_FIND_MY)
            }.groupBy { it.groupKey }.forEach { (key, members) ->
                baselineMaxCounts[key] = maxOf(baselineMaxCounts[key] ?: 0, members.size)
            }
        }
        val view = if (baselineSet.value) watchView(
            list, baselineGroups, acknowledged.value, now, baselineWifi, baselineSamples,
            farThresholdDbm = profile.farThresholdDbm,
        ) else WatchView()
        watch.value = view
        // 감지 기록은 1초에 한 번만 갱신한다. 화면 갱신(0.1초)마다 목록을 새로 만들 필요가 없다.
        if (baselineSet.value && now - detectionsRecordedAtMs >= 1_000L) {
            detectionsRecordedAtMs = now
            recordDetections(view)
            if (scanning.value) vibrateNewAlerts(view)
        }
        health.value = scanHealth(now)
        geiger.setClass(list.firstOrNull { it.trackingKey == findTarget.value && it.presence == SignalPresence.RECENT }?.distanceClass ?: DistanceClass.UNKNOWN)
        // 설정 상태 확인은 1초에 한 번이면 충분하다.
        if (now - problemsCheckedAtMs >= 1_000) {
            problemsCheckedAtMs = now
            checkExamMode()
            if (scanning.value && !simMode.value) restartStalledScanners(now)
        }
    }

    /** BLE·와이파이·클래식 탐색이 조용히 멈춘 경우 자동으로 다시 건다. */
    private fun restartStalledScanners(now: Long) {
        // BLE: 오류로 멈췄으면 60초에 한 번 다시 시도한다.
        if (ble.lastError != null && ble.isUsable() && now - lastBleRestartAtMs >= 60_000L) {
            lastBleRestartAtMs = now
            ble.start(::onObservation, bleRefreshMs())
        } else if (!ble.active && ble.lastError == null && ble.isUsable()) {
            ble.start(::onObservation, bleRefreshMs())
        }
        // Wi-Fi: 2분 30초 넘게 완료된 탐색이 없으면 통계를 유지한 채 다시 건다.
        val sessionStart = scanSession.value?.startedAtMs ?: now
        val wifiAge = wifi.lastCompletedAtMs?.let { now - it } ?: (now - sessionStart)
        if (wifiAge >= 150_000L && wifi.isEnabled() && now - lastWifiRestartAtMs >= 150_000L) {
            lastWifiRestartAtMs = now
            wifi.restart(::onObservation, wifiPeriodMs(), cacheWindowMs = profile.wifiCacheWindowMs, cacheFreshMs = profile.wifiCacheFreshMs, cachePollMs = profile.wifiCachePollMs)
        }
        // 클래식: 5분 넘게 탐색을 시작하지 못했으면 다시 건다.
        val classicAge = classic.lastSweepAtMs?.let { now - it } ?: (now - sessionStart)
        if (classicAge >= 300_000L && classic.isUsable() && now - lastClassicRestartAtMs >= 300_000L) {
            lastClassicRestartAtMs = now
            classic.start(::onObservation, classicPeriodMs(), classicSweepMs())
        }
    }

    /** 새로 잡힌 묶음을 시간순 기록으로 남긴다. 같은 묶음은 처음 시각을 유지하고 마지막 시각만 갱신한다. */
    private fun recordDetections(view: WatchView) {
        // 한 번 스친 약한 패킷까지 기록에 남기면 기록이 잡음으로 가득 찬다.
        // 새 전파로 알린 묶음(alert)과, 반복 관측으로 신뢰도가 올라간 묶음만 남긴다.
        // 지금 패킷이 없고 기억으로만 유지되는 묶음은 뺀다. 실제로 잡혔을 때 이미 기록됐다.
        (view.wifi + view.bluetooth + view.far)
            .filter { it.liveCount > 0 && (it.alert || it.confidence != SignalConfidence.OBSERVED) }
            .forEach { group ->
                val device = group.strongest
                val previous = detectionLog[group.key]
                val peak = listOfNotNull(previous?.peakRssi, group.devices.mapNotNull { it.peakRssi }.maxOrNull()).maxOrNull()
                detectionLog[group.key] = DetectionEvent(
                    groupKey = group.key,
                    name = device.displayName,
                    kind = device.hints.primary?.label,
                    source = device.source,
                    address = device.key,
                    firstSeenMs = previous?.firstSeenMs ?: group.devices.minOf { it.firstSeenMs },
                    lastSeenMs = maxOf(previous?.lastSeenMs ?: 0L, group.devices.maxOf { it.lastSeenMs }),
                    peakRssi = peak,
                    confidence = previous?.confidence?.takeIf { it.ordinal > group.confidence.ordinal } ?: group.confidence,
                    observations = maxOf(previous?.observations ?: 0, group.devices.sumOf { it.observationCount }),
                )
            }
        if (detectionLog.size > 200) {
            while (detectionLog.size > 200) detectionLog.remove(detectionLog.keys.first())
        }
        val list = detectionLog.values.sortedByDescending { it.lastSeenMs }
        if (list != detections.value) detections.value = list
    }

    /** 새 전파가 처음 확인될 때 한 번만 짧게 진동한다. 같은 묶음이 계속 잡혀도 다시 울리지 않는다. */
    private fun vibrateNewAlerts(view: WatchView) {
        if (!alertVibrate.value) return
        var newAlert = false
        (view.wifi + view.bluetooth + view.far).forEach { group ->
            // 지금 잡히지 않고 기억으로만 남은 묶음은 새로 확인된 것이 아니다. 울리지 않는다.
            if (group.alert && group.liveCount > 0 && vibratedGroups.add(group.key)) newAlert = true
        }
        if (newAlert) geiger.pulse()
    }

    private fun scanHealth(now: Long): ScanHealth {
        // 배터리·발열 값은 화면 갱신(0.1초)마다 읽을 필요가 없다. 10초에 한 번만 읽는다.
        if (now - batteryReadAtMs >= 10_000L) {
            batteryReadAtMs = now
            batteryPercentCache = (app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            thermalStatusCache = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: 0
            // [FORK 1.1.2] 개발자 옵션에서 탐색 제한을 켜고 끄면 곧바로 새 주기로 다시 건다.
            val throttledNow = wifi.isScanThrottled()
            if (wifiThrottled.value != throttledNow) {
                wifiThrottled.value = throttledNow
                if (scanning.value && !simMode.value) {
                    wifi.restart(::onObservation, wifiPeriodMs(), cacheWindowMs = profile.wifiCacheWindowMs, cacheFreshMs = profile.wifiCacheFreshMs, cachePollMs = profile.wifiCachePollMs)
                }
            }
        }
        return ScanHealth(
            blePacketsPerMinute = bleRate.perMinute(now),
            wifiPacketsPerMinute = wifiRate.perMinute(now),
            classicPacketsPerMinute = classicRate.perMinute(now),
            bleActive = ble.active,
            wifiActive = wifi.active,
            classicActive = classic.active,
            bleLastSeenAgeSec = ble.lastReceivedAtMs?.let { ((now - it) / 1000).coerceAtLeast(0) },
            wifiCompletedScans = wifi.completedScans,
            wifiLastScanAgeSec = wifi.lastCompletedAtMs?.let { ((now - it) / 1000).coerceAtLeast(0) },
            wifiCachedPicks = wifi.cachedPicks,
            bleError = ble.lastError,
            wifiError = wifi.lastError,
            classicError = classic.lastError,
            thermalStatus = thermalStatusCache,
            batteryPercent = batteryPercentCache,
            wifiThrottled = wifiThrottled.value,
        )
    }

    private fun radioProblems(): List<RadioProblem> = if (simMode.value) emptyList() else listOfNotNull(
        RadioProblem.BLUETOOTH_OFF.takeIf { !ble.isUsable() },
        RadioProblem.WIFI_OFF.takeIf { !wifi.isEnabled() },
        RadioProblem.LOCATION_OFF.takeIf { locationServicesOff() },
    )

    fun shutdown() {
        stopScan()
        stopMagnetic()
        geiger.release()
        closed = true
        scope.cancel()
    }

    override fun onCleared() { shutdown() }
}
