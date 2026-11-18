package com.classroom.rfdetector.scanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.classroom.rfdetector.core.RfObservation
import com.classroom.rfdetector.core.ScanSource
import com.classroom.rfdetector.core.classicHints
import java.util.Locale

/**
 * 클래식(BR/EDR) 블루투스 탐색. BLE 광고를 내지 않는 구형 카메라·헤드셋·스피커를 잡기 위한 보조 스캐너다.
 *
 * 클래식 탐색은 표준 inquiry가 10.24초라 그보다 짧게 끊으면 응답을 받을 틈이 없다. 표준에서는 12초,
 * 최대 감지에서는 20초까지 늘려 늦게 응답하는 먼 기기를 더 잡는다. 대신 오래 붙잡을수록 BLE와
 * 무선을 나눠 쓰는 시간이 늘어 배터리·발열이 커진다.
 */
class ClassicScanner(context: Context) {
    private val app = context.applicationContext
    private val adapter =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private companion object {
        const val SWEEP_MS = 12_000L
        /** 표준 inquiry가 10.24초이므로 그보다 짧게 끊지 않는다. */
        const val MIN_SWEEP_MS = 12_000L
        const val MAX_SWEEP_MS = 25_000L
        const val PERIOD_MS = 150_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nextSweep = Runnable { startSweep() }
    private var receiver: BroadcastReceiver? = null
    private var onObs: ((RfObservation) -> Unit)? = null
    private var sweep = 0L
    private var periodMs = PERIOD_MS
    /** 한 번의 탐색을 붙잡고 있는 시간. 늘릴수록 늦게 응답하는 기기를 더 잡는다. */
    @Volatile private var sweepMs = SWEEP_MS
    /**
     * 이번 탐색 회차에 이미 보고된 주소. 클래식 탐색은 같은 회차에 같은 기기를 두 번 보고할 수 있고,
     * 회차 안에서는 한 번만 세는 것이 맞다. 한 회차 = 그 시각의 존재 확인 한 번이다.
     */
    private val sweepSeen = HashSet<String>()

    @Volatile var active: Boolean = false
        private set
    @Volatile var lastReceivedAtMs: Long? = null
        private set
    @Volatile var lastSweepAtMs: Long? = null
        private set
    @Volatile var packetCount: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var sweeps: Int = 0
        private set

    fun isUsable(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    fun isRunning(): Boolean = receiver != null

    fun start(onObservation: (RfObservation) -> Unit, periodMs: Long = PERIOD_MS, sweepMs: Long = SWEEP_MS) {
        stop()
        onObs = onObservation
        this.periodMs = periodMs
        this.sweepMs = sweepMs.coerceIn(MIN_SWEEP_MS, MAX_SWEEP_MS)
        lastError = null
        packetCount = 0L
        sweeps = 0
        if (!isUsable()) {
            lastError = "블루투스가 꺼져 있거나 접근 권한이 없습니다"
            return
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (receiver !== this) return
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> onFound(intent)
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        active = false
                        scheduleNext()
                    }
                }
            }
        }
        receiver = listener
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(app, listener, filter, ContextCompat.RECEIVER_EXPORTED)
        startSweep()
    }

    private fun startSweep() {
        val ad = adapter ?: return
        if (!isUsable()) {
            lastError = "블루투스가 꺼져 있습니다"
            return
        }
        sweeps++
        sweep++
        sweepSeen.clear()
        lastSweepAtMs = System.currentTimeMillis()
        try {
            if (ad.isDiscovering) ad.cancelDiscovery()
            if (ad.startDiscovery()) {
                active = true
                lastError = null
                handler.postDelayed({
                    if (active) {
                        runCatching { ad.cancelDiscovery() }
                        active = false
                        scheduleNext()
                    }
                }, sweepMs)
            } else {
                lastError = "클래식 블루투스 탐색을 시작하지 못했습니다"
                scheduleNext()
            }
        } catch (_: SecurityException) {
            lastError = "블루투스 탐색 권한을 확인하세요"
            // 실패해도 다음 회차를 예약한다. 여기서 멈추면 권한이 돌아와도 스스로 재개하지 못한다.
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacks(nextSweep)
        if (receiver != null) handler.postDelayed(nextSweep, periodMs)
    }

    @Suppress("DEPRECATION")
    private fun onFound(intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice ?: return
        // 주소 조회도 BLUETOOTH_CONNECT 권한이 필요하다. 스캔 도중 권한이 회수되면 앱이 죽지 않게 건너뛴다.
        val address = try { device.address } catch (_: SecurityException) { return }
        // 같은 회차에 같은 주소가 다시 보고되면 한 번만 센다. 회차 안의 중복 보고가 증거를 부풀리지 않게 한다.
        if (!sweepSeen.add(address)) return
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
            .takeIf { it != Short.MIN_VALUE.toInt() && it in -127..0 }
        val now = System.currentTimeMillis()
        val name = try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
        val btClass = try { device.bluetoothClass } catch (_: SecurityException) { null }
        val major = btClass?.majorDeviceClass
        val minor = btClass?.deviceClass
        packetCount++
        lastReceivedAtMs = maxOf(lastReceivedAtMs ?: now, now)
        onObs?.invoke(
            RfObservation(
                key = address,
                source = ScanSource.CLASSIC,
                name = name,
                rssi = rssi,
                txPower = null,
                hidden = false,
                timestampMs = now,
                hints = classicHints(major, minor),
                details = buildMap {
                    btClass?.let {
                        put("블루투스 종류", classicClassName(it.majorDeviceClass))
                        put("클래스 값", "0x${it.deviceClass.toString(16).uppercase()}")
                    }
                    put("탐색 방식", "클래식(BR/EDR) 탐색")
                    put("마지막 탐색", "${SystemClock.elapsedRealtime() / 1000}초")
                },
                dedupKey = "$address:$sweep",
                // 클래식 주소는 회전하지 않는다. 앞 3옥텟(제조사)만 쓰면 같은 회사 기기들이 한 묶음으로 뭉쳐,
                // 하나를 "확인함"으로 뺄 때 다른 기기까지 함께 사라진다. 주소 전체를 쓴다.
                fingerprint = "classic:${address.uppercase(Locale.ROOT)}",
            )
        )
    }

    private fun classicClassName(major: Int?): String = when (major) {
        0x0100 -> "컴퓨터"
        0x0200 -> "휴대폰"
        0x0300 -> "네트워크"
        0x0400 -> "오디오·영상"
        0x0500 -> "주변기기"
        0x0600 -> "카메라·영상 장비"
        0x0700 -> "웨어러블"
        0x0800 -> "장난감"
        0x0900 -> "건강 기기"
        else -> "종류 미확인"
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        active = false
        onObs = null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
    }
}
