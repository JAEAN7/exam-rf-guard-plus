package com.classroom.rfdetector.core

import kotlin.math.sqrt

/**
 * 자기장 표본. 단위는 µT(마이크로테슬라).
 *
 * 지구 자기장이 25~65 µT이므로, 그 위에 겹치는 **작은 변화**를 본다. 철·니켈 같은 자성 금속과
 * 자석은 몇 cm 안에서 1~수십 µT를 더하지만, 알루미늄·구리·금 같은 비자성 금속은 사실상 0이다.
 */
data class MagneticSample(
    val x: Double,
    val y: Double,
    val z: Double,
    val atMs: Long,
    /** 가속도 센서가 이 표본을 받을 때 기기가 흔들리거나 이동 중이었는지. */
    val deviceMoving: Boolean = false,
) {
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)
}

/** 화면에 그대로 쓰는 상태. 색과 문구를 여기서 정한다. */
enum class MagneticState(val label: String) {
    OFFLINE("센서 없음"),
    CALIBRATING("기준 잡는 중"),
    CLEAR("변화 없음"),
    TRANSIENT("순간 변화"),
    DETECTED("자성 물체 의심"),
}

/**
 * 한 시점의 분석 결과.
 *
 * 화면 기본 표시에는 [state]와 [strength]만 쓰고, 나머지 값은 "자세히"를 펼쳤을 때만 보여 준다.
 * 그래서 사용자는 숫자를 몰라도 막대와 문구만으로 판단할 수 있다.
 */
data class MagneticReading(
    val state: MagneticState = MagneticState.OFFLINE,
    /** 평활한 자기장 크기 µT. */
    val magnitude: Double = 0.0,
    /** 주변 환경에 맞춰 스스로 갱신한 기준값 µT. */
    val baseline: Double = 0.0,
    /** [magnitude] - [baseline]. 부호가 있다. 회전에 영향을 받지 않는다. */
    val delta: Double = 0.0,
    /** 3축 벡터 차이의 크기. 휴대폰을 돌리면 커지므로 회전이 느릴 때만 쓴다. */
    val vectorDelta: Double = 0.0,
    /** 초당 변화량 µT/s. */
    val ratePerSec: Double = 0.0,
    /** 최근 창의 표준편차 µT. 손떨림과 진짜 물체를 구분한다. */
    val noise: Double = 0.0,
    /** 여러 알고리즘을 합친 점수. 1.0이 임계. */
    val score: Double = 0.0,
    /** 임계를 넘긴 알고리즘 수. */
    val agreeing: Int = 0,
    /** 이번 변화가 시작된 시각. 기록을 묶는 키다. */
    val runStartMs: Long = 0L,
    /** 지금 휴대폰을 빠르게 움직이는 중인지. 화면 안내에 쓴다. */
    val moving: Boolean = false,
    /** 같은 변화가 몇 번 반복됐는지. sweep으로 왔다 갔다 하면 늘어난다. */
    val repeats: Int = 0,
    /** 이번 변화가 이어진 시간. */
    val durationMs: Long = 0L,
    /** 이번 변화의 최대 |delta|. */
    val peakDelta: Double = 0.0,
    /** 평활한 축 값. "자세히"에서만 보여 준다. */
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    /** 임계 대비 세기 0~1. 화면 막대 길이에 그대로 쓴다. */
    val strength: Double = 0.0,
    /** 이 시점의 감지 임계 μT. 기록에서 잡음과 진짜 변화를 가르는 데도 쓴다. */
    val thresholdMicroTesla: Double = 0.0,
    /**
     * 같은 방향의 작은 변화가 얼마나 쌓였는지(CUSUM). 1.0이 경보 기준이고,
     * [MagneticProfile.cusumSolo] 이상이면 다른 알고리즘이 동의하지 않아도 지속 변화로 본다.
     */
    val cusumScore: Double = 0.0,
) {
    companion object {
        const val ALGORITHMS = 6
    }
}

/** 감지 한 건의 기록. 시작 시각을 키로 계속 갱신한다. */
data class MagneticEvent(
    val startedAtMs: Long,
    val lastSeenMs: Long,
    val peakDeltaMicroTesla: Double,
    val durationMs: Long,
    val repeats: Int,
)

/**
 * 감도 모드별 자기장 분석 값.
 *
 * 임계값은 "한 손으로 휴대폰을 들고 책상이나 사람에게 천천히 가져다 대는" 사용법을 기준으로 잡았다.
 * 최대 감지는 0.6 µT까지 본다. 휴대폰 자기장 센서의 표본 잡음이 0.1~0.5 µT 수준이므로,
 * [seedSamples]만큼 모아 기준값을 잡고 그 뒤로 평활로 잡음을 더 줄인다.
 */
data class MagneticProfile(
    /** 이만큼 기준값에서 벗어나면 감지로 본다. */
    val thresholdMicroTesla: Double,
    /** 감지로 인정하기 위해 이 시간 이상 이어져야 한다. */
    val holdMs: Long,
    /** 임계의 이 비율 아래로 내려가면 다시 무장한다(히스테리시스). */
    val releaseRatio: Double,
    /** 평활 시간상수 ms. 작을수록 빨리 반응하고 잡음이 커진다. */
    val smoothingTauMs: Double,
    /** 기준값 적응 시간상수 ms. 클수록 천천히 따라간다. */
    val baselineTauMs: Double,
    /** 이보다 큰 한 번의 도약은 손 움직임으로 보고 기준값에서 제외한다. */
    val spikeLimitMicroTesla: Double,
    /** 이 시간이 지나면 반복 횟수를 0으로 되돌린다. */
    val repeatWindowMs: Long,
    /** 이 각도(도/표본)보다 빠르게 돌면 회전으로 보고 벡터 분석을 끈다. */
    val rotationDegreesPerSample: Double,
    /** 기준값을 잡기 위해 모으는 표본 수. */
    val seedSamples: Int,
    /**
     * CUSUM 누적 검정에서 매 표본 허용하는 표류(임계 대비). 작을수록 예민하다.
     * 0.45면 임계의 45%만큼 한 방향으로 계속 벗어날 때 누적이 쌓인다.
     */
    val cusumDrift: Double = 0.45,
    /** CUSUM 누적이 임계의 이 배수에 이르면 경보로 본다. */
    val cusumAlarm: Double = 3.0,
    /** CUSUM 점수가 이 값 이상이면 다른 알고리즘 없이도 지속 변화로 본다. */
    val cusumSolo: Double = 2.0,
    /** 기준을 잡은 뒤 이 시간 동안은 누적을 시작하지 않는다. 센서 예열 흔들림을 걸러낸다. */
    val cusumWarmupMs: Long = 1_500L,
    /**
     * 기기 센서 잡음이 크면 임계를 이 배수까지 올린다. 표본 잡음이 0.5 μT인 기기에서
     * 최대 감지(0.6 μT)를 그대로 쓰면 오탐이 잦아지므로, 조용할 때 잰 잡음에 맞춰 올린다.
     */
    val noiseThresholdFactor: Double = 2.5,
    /** 잡음 때문에 올릴 수 있는 임계의 상한(기본 임계 대비). */
    val noiseThresholdCap: Double = 1.8,
    /** 확정 감지가 근거를 잃어도 임계의 [releaseRatio] 위에 있으면 이 시간까지 유지한다. */
    val stickyHoldMs: Long = 1_800L,
    /**
     * 움직임이 멈춘 뒤 이 시간 동안은 판정하지 않는다. 손에서 남은 흔들림이 가라앉고,
     * **정적인 자기 변화**만 자성 물체로 인정하기 위해서다.
     */
    val stillRequiredMs: Long = 350L,
    /** 창 잡음이 임계의 이 비율 이하일 때만 '정지한 물체'로 본다. 움직이는 손은 잡음이 크다. */
    val stableNoiseRatio: Double = 0.5,
) {
    companion object {
        fun of(mode: SensitivityMode): MagneticProfile = when (mode) {
            SensitivityMode.STANDARD -> MagneticProfile(
                thresholdMicroTesla = 1.5,
                holdMs = 900L,
                releaseRatio = 0.6,
                smoothingTauMs = 260.0,
                baselineTauMs = 4_000.0,
                spikeLimitMicroTesla = 25.0,
                repeatWindowMs = 12_000L,
                rotationDegreesPerSample = 2.0,
                seedSamples = 24,
                cusumDrift = 0.45,
                cusumAlarm = 3.0,
                cusumSolo = 2.0,
                cusumWarmupMs = 1_500L,
                noiseThresholdFactor = 2.5,
                noiseThresholdCap = 1.8,
                stickyHoldMs = 1_800L,
                stillRequiredMs = 350L,
                stableNoiseRatio = 0.5,
            )
            SensitivityMode.MAX -> MagneticProfile(
                thresholdMicroTesla = 0.6,
                holdMs = 600L,
                releaseRatio = 0.55,
                smoothingTauMs = 160.0,
                baselineTauMs = 3_000.0,
                spikeLimitMicroTesla = 18.0,
                repeatWindowMs = 15_000L,
                rotationDegreesPerSample = 2.5,
                seedSamples = 40,
                cusumDrift = 0.35,
                cusumAlarm = 2.5,
                cusumSolo = 1.8,
                cusumWarmupMs = 1_200L,
                noiseThresholdFactor = 2.2,
                noiseThresholdCap = 1.8,
                stickyHoldMs = 2_700L,
                stillRequiredMs = 300L,
                stableNoiseRatio = 0.5,
            )
        }
    }
}

/**
 * 삐 소리 간격(ms).
 *
 * 실제 금속 탐지기처럼 가까울수록(=세기가 클수록) 빨리 울린다. 세기 0이면 650ms,
 * 1.0이면 130ms다. 화면 없이도 가까워지는 것을 귀로 알 수 있게 한다.
 */
fun magneticBeepIntervalMs(strength: Double): Long =
    (650.0 - 520.0 * strength.coerceIn(0.0, 1.0)).toLong().coerceIn(120L, 650L)
