package com.classroom.rfdetector.scanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.classroom.rfdetector.core.MagneticAnalyzer
import com.classroom.rfdetector.core.MagneticProfile
import com.classroom.rfdetector.core.MagneticReading
import com.classroom.rfdetector.core.MagneticSample
import com.classroom.rfdetector.core.MagneticState
import com.classroom.rfdetector.core.SensitivityMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 자기장 센서(나침반)로 자성 금속·자석을 찾는 보조 스캐너.
 *
 * 이 앱의 다른 스캐너와 달리 전파를 쓰지 않는다. 그래서 비행기 모드나 무선을 꺼도 동작하고 권한도 필요 없다.
 *
 * 성능과 오탐을 함께 다루기 위해:
 * - **보정 센서**(`TYPE_MAGNETIC_FIELD`)를 우선 쓴다. 보정 센서는 기기 고정 편향(하드 아이언)을 빼므로
 *   휴대폰을 돌려도 |B|가 거의 일정하다. 보정되지 않은 센서는 돌릴 때 크기가 함께 흔들려 오탐이 났다.
 * - **가속도 센서**로 흔들기·걷기를 감지해 표본에 표시한다. 분석기는 이동 중에는 판정을 보류한다.
 * - 기기가 허용하는 가장 짧은 주기(`minDelay`)로 등록한다. 표본이 많을수록 평균으로 잡음이 줄어든다.
 * - 표본마다 [MagneticAnalyzer]에 넣어 100% 처리한다. 화면 표시만 10Hz로 줄인다.
 */
class MagneticScanner(context: Context) {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val analyzer = MagneticAnalyzer()

    /** 보정 센서를 우선한다. 보정 센서가 없으면 보정되지 않은 센서로 내려간다. */
    private val magnetometer: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            ?: sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    /** 흔들기·걷기를 판정할 가속도 센서. 선형 가속도가 있으면 그것을 우선한다. */
    private val accelerometer: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null

    /** 최근 표본을 받을 때 기기가 움직이고 있었는지. 분석기 판정을 보류하는 데 쓴다. */
    @Volatile private var deviceMoving = false
    private var motionEma = 0.0
    private var movingUntilMs = 0L

    @Volatile var latest: MagneticReading = MagneticReading()
        private set
    @Volatile var active: Boolean = false
        private set

    val available: Boolean get() = magnetometer != null

    /** 기기가 허용하는 최고 표본률(Hz). "자세히" 화면에 보여 준다. */
    val fastestHz: Int get() = magnetometer?.let { if (it.minDelay > 0) 1_000_000 / it.minDelay else 0 } ?: 0

    /** 실제로 등록된 센서가 보정되지 않은 쪽인지. */
    val uncalibrated: Boolean get() = magnetometer?.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED

    /**
     * 시스템이 알려 주는 센서 정확도([SensorManager.SENSOR_STATUS_*]).
     *
     * 자성 물체가 근처에 있으면 센서가 "보정되지 않음"으로 떨어지기도 한다. 그래서 낮은 정확도를
     * 그대로 감지 신호로 오해하지 않도록 화면에서 따로 안내한다. 공개 금속 탐지 앱들도 이 값을 읽어
     * 8자 보정을 안내한다.
     */
    @Volatile var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
        private set
    /** 정확도 콜백을 아직 받지 못했으면 false. 기본값을 "나쁨"으로 오해해 경고를 띄우지 않는다. */
    @Volatile var accuracyKnown: Boolean = false
        private set

    fun setProfile(mode: SensitivityMode) = analyzer.setProfile(MagneticProfile.of(mode))

    /** 기준값을 다시 잡는다. 다음 표본부터 새로 모으므로 화면도 바로 "기준 잡는 중"으로 돌린다. */
    fun calibrate() {
        analyzer.calibrate()
        latest = MagneticReading(state = MagneticState.CALIBRATING)
    }

    fun start(): Boolean {
        if (active) return true
        val sensor = magnetometer ?: run {
            latest = MagneticReading(state = MagneticState.OFFLINE)
            return false
        }
        motionEma = 0.0
        movingUntilMs = 0L
        deviceMoving = false

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                // 보정 센서도, 보정되지 않은 센서도 첫 3개 값이 자기장 성분이다.
                feed(
                    MagneticSample(
                        x = event.values[0].toDouble(),
                        y = event.values[1].toDouble(),
                        z = event.values[2].toDouble(),
                        atMs = System.currentTimeMillis(),
                        deviceMoving = deviceMoving,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, status: Int) {
                if (sensor?.type == magnetometer?.type) {
                    accuracy = status
                    accuracyKnown = true
                }
            }
        }
        listener = l
        analyzer.reset()
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        accuracyKnown = false
        // minDelay는 기기가 허용하는 최소 주기(μs)다. maxReportLatency 0은 지연 없이 즉시 받는다는 뜻이다.
        val periodUs = sensor.minDelay.coerceAtLeast(1_000)
        val registered = sensors.registerListener(l, sensor, periodUs, 0)
        if (!registered) {
            listener = null
            latest = MagneticReading(state = MagneticState.OFFLINE)
            return false
        }

        // 가속도는 50Hz면 흔들기·걷기를 구분하기에 충분하다. 없으면 움직임 표시 없이 계속한다.
        accelerometer?.let { accel ->
            val linear = accel.type == Sensor.TYPE_LINEAR_ACCELERATION
            val al = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.values.size < 3) return
                    val ax = event.values[0].toDouble()
                    val ay = event.values[1].toDouble()
                    val az = event.values[2].toDouble()
                    val raw = sqrt(ax * ax + ay * ay + az * az)
                    val dynamic = if (linear) raw else abs(raw - 9.80665)
                    motionEma += 0.2 * (dynamic - motionEma)
                    // 벽시계가 아니라 단조 시계를 쓴다. 시각을 수동으로 바꿔도 이동 판정이 고착되지 않는다.
                    val now = SystemClock.elapsedRealtime()
                    if (dynamic > 2.5 || motionEma > 0.9) movingUntilMs = now + 500L
                    deviceMoving = now < movingUntilMs
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (sensors.registerListener(al, accel, 20_000, 0)) accelListener = al
        }

        active = true
        return true
    }

    /** 표본을 분석기에 넣고 최신 값을 갱신한다. 가상 모드와 테스트도 같은 경로를 쓴다. */
    fun feed(sample: MagneticSample) {
        latest = analyzer.onSample(sample)
    }

    fun stop() {
        listener?.let { runCatching { sensors.unregisterListener(it) } }
        accelListener?.let { runCatching { sensors.unregisterListener(it) } }
        listener = null
        accelListener = null
        active = false
        deviceMoving = false
        motionEma = 0.0
        movingUntilMs = 0L
        accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        accuracyKnown = false
        latest = MagneticReading(state = if (available) MagneticState.CALIBRATING else MagneticState.OFFLINE)
    }
}