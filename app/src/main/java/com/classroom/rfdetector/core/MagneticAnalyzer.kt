package com.classroom.rfdetector.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 자기장 분석기. 안드로이드에 의존하지 않는 순수 계산이라 단위 테스트로 그대로 검증할 수 있다.
 *
 * 하는 일
 * 1. 잡음 제거: 최근 3표본 중앙값으로 튄 값을 걸러낸 뒤, 시간상수 기반 지수 평활을 한다.
 * 2. 적응형 기준값: 지구 자기장과 휴대폰 자체의 고정 편향을 천천히 따라가며 뺀다. 변화가 감지되는 동안에는
 *    기준값을 멈춰서, 진짜 물체를 서서히 "주변 환경"으로 흡수해 버리지 않는다.
 * 3. 3축 분석: 회전에 강한 [MagneticReading.delta](크기 변화)를 주 판정으로 쓰고,
 *    휴대폰을 거의 돌리지 않을 때만 [MagneticReading.vectorDelta](벡터 변화)를 보조로 쓴다.
 *    지구 자기장에서 휴대폰을 돌리면 크기는 그대로지만 벡터는 완전히 바뀌므로 이 구분이 필수다.
 * 4. 복수 알고리즘: 크기 변화, 벡터 변화, 변화 속도, 안정도(잡음 대비), 창 일치도, 누적 합(CUSUM)을
 *    각각 0~∞ 점수로 내고, 임계를 넘긴 알고리즘이 2개 이상일 때 감지로 본다. 한 알고리즘만 튀면 순간 잡음으로 본다.
 * 5. 누적 검정(CUSUM): 순간 임계에 못 미치는 작은 변화도 같은 방향으로 계속 벗어나면 합이 쌓이게 한다.
 *    손떨림처럼 부호가 오가는 흔들림은 서로 상쇄되어 쌓이지 않으므로, 천천히 다가오는 작은 자석을 잡는 데 쓴다.
 * 6. 반복 확인: 감지가 임계 아래로 내려갔다가 다시 올라오면 반복 횟수를 올린다. 순간 오류와 지속 변화를 구분한다.
 */
class MagneticAnalyzer(profile: MagneticProfile = MagneticProfile.of(SensitivityMode.STANDARD)) {

    private companion object {
        /** 창은 시간 기준으로 자른다. 표본률이 달라도 같은 시간 폭을 본다. */
        const val WINDOW_MS = 600L
        /**
         * 창 링 버퍼 크기. 600ms를 담아야 하는데 기기가 500Hz까지 표본을 주면 300개가 필요하다.
         * 100Hz만 가정한 256으로 두면 빠른 기기에서 창이 조용히 짧아져 판정이 달라진다.
         */
        const val WINDOW_CAPACITY = 1024
        const val MAX_SEED = 64
        /** 재무장에 필요한 최소 조용한 시간. */
        const val REARM_MS = 200L
        /** 기기 잡음 바닥을 따라가는 시간상수. 조용한 구간에서만 갱신한다. */
        const val NOISE_TAU_MS = 5_000L
    }

    private var p = profile

    /** 시간 창(표본 시간·원본 크기·|변화량|). 링 버퍼라 표본마다 할당이 없다. */
    private class Ring(private val capacity: Int) {
        private val times = LongArray(capacity)
        private val values = DoubleArray(capacity)
        private val magnitudes = DoubleArray(capacity)
        private var head = 0
        private var count = 0

        fun clear() {
            head = 0
            count = 0
        }

        fun add(atMs: Long, value: Double, deltaAbs: Double) {
            val index = (head + count) % capacity
            times[index] = atMs
            values[index] = value
            magnitudes[index] = deltaAbs
            if (count < capacity) count++ else head = (head + 1) % capacity
        }

        fun pruneBefore(cutoffMs: Long) {
            while (count > 0 && times[head] < cutoffMs) {
                head = (head + 1) % capacity
                count--
            }
        }

        /** 창 안 원본 값의 표준편차. 손떨림이면 커진다. */
        fun stdDev(): Double {
            if (count < 3) return 0.0
            var sum = 0.0
            var sumSq = 0.0
            for (i in 0 until count) {
                val v = values[(head + i) % capacity]
                sum += v
                sumSq += v * v
            }
            val mean = sum / count
            return sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
        }

        /** |변화량|이 [above]를 넘은 표본 비율. 지속적인 변화인지 한순간인지 가른다. */
        fun fractionAbove(above: Double): Double {
            if (count == 0) return 0.0
            var n = 0
            for (i in 0 until count) if (magnitudes[(head + i) % capacity] > above) n++
            return n.toDouble() / count
        }
    }

    private val window = Ring(WINDOW_CAPACITY)
    private val seed = DoubleArray(MAX_SEED)
    private var seedCount = 0
    private var seedSumX = 0.0
    private var seedSumY = 0.0
    private var seedSumZ = 0.0

    private var seeded = false
    private var calibrationPending = true

    private var baseline = 0.0
    private var baselineX = 0.0
    private var baselineY = 0.0
    private var baselineZ = 0.0

    private var smoothMag = 0.0
    private var smoothX = 0.0
    private var smoothY = 0.0
    private var smoothZ = 0.0
    private var prevSmoothMag = 0.0
    private var lastAtMs = 0L

    private var rawA = 0.0
    private var rawB = 0.0
    private var rawC = 0.0
    private var rawCount = 0

    private var unitX = 0.0
    private var unitY = 0.0
    private var unitZ = 0.0
    private var hasUnit = false

    private var overSinceMs = 0L
    private var lastOverMs = 0L
    private var quietSinceMs = 0L
    /** 기준을 잡은 시각. CUSUM 예열 시간을 재는 데 쓴다. */
    private var seededAtMs = 0L
    /** 양방향 CUSUM 누적값. 임계 단위(threshold)로 쌓인다. */
    private var cusumUp = 0.0
    private var cusumDown = 0.0
    /** 조용할 때 잰 기기 잡음 바닥(μT). 기기마다 다른 센서 잡음에 임계를 맞추는 데 쓴다. */
    private var noiseFloor = 0.0
    /** 마지막으로 빠르게 회전한 시각. 회전 직후 축 평활이 따라올 때까지 벡터 분석을 쉬는 데 쓴다. */
    private var lastFastRotationMs = 0L
    /** 마지막으로 기기가 움직인 시각. 멈춘 뒤 일정 시간이 지나야 판정을 시작한다. */
    private var lastMovingMs = 0L
    private var armed = true
    private var runStartMs = 0L
    private var runPeak = 0.0
    private var repeats = 0
    private var state = MagneticState.CALIBRATING

    /** 감도 모드가 바뀌면 임계와 평활 계수가 달라지므로 기준값을 다시 잡는다. */
    fun setProfile(next: MagneticProfile) {
        if (next == p) return
        p = next
        calibrate()
    }

    /** 주변 환경이 바뀌었을 때(다른 교실로 이동 등) 사용자가 기준값을 다시 잡는다. */
    fun calibrate() {
        calibrationPending = true
        seeded = false
        seedCount = 0
        seedSumX = 0.0
        seedSumY = 0.0
        seedSumZ = 0.0
        window.clear()
        hasUnit = false
        rawCount = 0
        lastFastRotationMs = 0L
        lastMovingMs = 0L
        overSinceMs = 0L
        lastOverMs = 0L
        quietSinceMs = 0L
        seededAtMs = 0L
        cusumUp = 0.0
        cusumDown = 0.0
        noiseFloor = 0.0
        armed = true
        runStartMs = 0L
        runPeak = 0.0
        repeats = 0
        state = MagneticState.CALIBRATING
    }

    fun reset() = calibrate()

    fun onSample(s: MagneticSample): MagneticReading {
        val mag = s.magnitude
        if (mag.isNaN() || mag.isInfinite()) return lastReading()

        // 시각이 뒤로 점프하면(수동 시간 변경 등) 누적·유지 시간 계산이 깨진다. 조용히 기준을 다시 잡는다.
        if (lastAtMs != 0L && s.atMs < lastAtMs) {
            calibrateInternal()
            lastAtMs = 0L
        }
        val dtMs = if (lastAtMs == 0L) 0L else (s.atMs - lastAtMs).coerceIn(1L, 500L)
        lastAtMs = s.atMs

        if (calibrationPending) {
            calibrationPending = false
            calibrateInternal()
        }

        // 회전 추정: 직전 단위벡터와의 사잇각. 지구 자기장에서 휴대폰을 돌리면 벡터가 통째로 바뀐다.
        val norm = mag.coerceAtLeast(1e-3)
        val ux = s.x / norm
        val uy = s.y / norm
        val uz = s.z / norm
        var rotationSlow = true
        if (hasUnit) {
            val dot = (ux * unitX + uy * unitY + uz * unitZ).coerceIn(-1.0, 1.0)
            rotationSlow = acos(dot) * 180.0 / PI <= p.rotationDegreesPerSample
        }
        unitX = ux
        unitY = uy
        unitZ = uz
        hasUnit = true

        // 회전 직후에는 축 평활이 아직 새 방향으로 따라오는 중이라, 벡터 변화를 실제 물체 변화로
        // 오해할 수 있다. 마지막 빠른 회전에서 평활 시간의 3배가 지날 때까지 벡터 분석을 쉰다.
        if (!rotationSlow) lastFastRotationMs = s.atMs
        val rotationHold = !rotationSlow ||
            (lastFastRotationMs > 0L && s.atMs - lastFastRotationMs < (p.smoothingTauMs * 3.0).toLong())

        if (!seeded) {
            if (seedCount < p.seedSamples.coerceAtMost(MAX_SEED)) {
                seed[seedCount++] = mag
                seedSumX += s.x
                seedSumY += s.y
                seedSumZ += s.z
            }
            if (seedCount < p.seedSamples.coerceAtMost(MAX_SEED)) {
                return MagneticReading(
                    state = MagneticState.CALIBRATING, magnitude = mag, baseline = baseline,
                    x = s.x, y = s.y, z = s.z, moving = false,
                )
            }
            baseline = medianOf(seed, seedCount)
            baselineX = seedSumX / seedCount
            baselineY = seedSumY / seedCount
            baselineZ = seedSumZ / seedCount
            smoothMag = baseline
            prevSmoothMag = baseline
            smoothX = baselineX
            smoothY = baselineY
            smoothZ = baselineZ
            seeded = true
            seededAtMs = s.atMs
            state = MagneticState.CLEAR
            return MagneticReading(
                state = MagneticState.CLEAR, magnitude = baseline, baseline = baseline,
                x = baselineX, y = baselineY, z = baselineZ, moving = false,
            )
        }

        // 1) 이상치 제거: 최근 3표본의 중앙값
        rawA = rawB
        rawB = rawC
        rawC = mag
        rawCount++
        val input = if (rawCount >= 3) median3(rawA, rawB, rawC) else mag

        // 2) 평활. 크게 움직이는 중에는 시정수를 줄여 반응을 빠르게 한다.
        val jump = abs(mag - smoothMag)
        val moving = s.deviceMoving || rotationHold || jump > p.spikeLimitMicroTesla * 0.4
        if (moving) lastMovingMs = s.atMs
        val tau = if (moving) p.smoothingTauMs * 0.35 else p.smoothingTauMs
        val alpha = if (dtMs == 0L) 1.0 else (dtMs / (dtMs + tau)).coerceIn(0.02, 1.0)
        prevSmoothMag = smoothMag
        smoothMag += alpha * (mag - smoothMag)
        smoothX += alpha * (s.x - smoothX)
        smoothY += alpha * (s.y - smoothY)
        smoothZ += alpha * (s.z - smoothZ)

        // 3) 변화량 (3축 모두 활용)
        val delta = smoothMag - baseline
        val deltaAbs = abs(delta)
        val dx = smoothX - baselineX
        val dy = smoothY - baselineY
        val dz = smoothZ - baselineZ
        val vectorDelta = sqrt(dx * dx + dy * dy + dz * dz)
        val rate = if (dtMs > 0L) (smoothMag - prevSmoothMag) * 1000.0 / dtMs else 0.0

        // 4) 창 통계
        window.add(s.atMs, input, deltaAbs)
        window.pruneBefore(s.atMs - WINDOW_MS)
        val noise = window.stdDev()

        val threshold = effectiveThreshold(noise, deltaAbs, moving, dtMs)

        // 5) CUSUM 누적 검정. 순간 임계에 못 미치는 작은 변화도 같은 방향으로 계속 벗어나면 합이 쌓인다.
        //    손떨림처럼 부호가 오가는 흔들림은 위·아래 누적이 서로 상쇄되어 쌓이지 않는다.
        val warm = seededAtMs > 0L && s.atMs - seededAtMs >= p.cusumWarmupMs
        if (warm && !moving) {
            val z = delta / threshold
            cusumUp = (cusumUp + z - p.cusumDrift).coerceIn(0.0, p.cusumAlarm * 4.0)
            cusumDown = (cusumDown - z - p.cusumDrift).coerceIn(0.0, p.cusumAlarm * 4.0)
        } else if (warm) {
            // 손이 빠르게 움직이는 동안은 쌓지 않고 조금씩 지운다. 움직임이 한 방향으로 쌓이면 오탐이 된다.
            cusumUp *= 0.9
            cusumDown *= 0.9
        } else {
            cusumUp = 0.0
            cusumDown = 0.0
        }
        val cusumScore = max(cusumUp, cusumDown) / p.cusumAlarm

        // 6) 적응형 기준값. 변화가 없고 손이 멈춰 있을 때만 아주 천천히 따라간다.
        //    누적 검정이 쌓이기 시작하면 곧바로 멈춘다. 그러지 않으면 기준값이 천천히 다가오는 작은
        //    물체를 "주변 환경"으로 흡수해, 순간 임계에 못 미치는 변화를 영영 놓친다.
        val recentlyOver = lastOverMs > 0L && s.atMs - lastOverMs < p.holdMs
        val accumulating = cusumUp > 0.0 || cusumDown > 0.0
        if (!moving && deltaAbs < threshold * 0.6 && !recentlyOver && !accumulating) {
            val bAlpha = (dtMs / (dtMs + p.baselineTauMs)).coerceIn(0.0, 0.25)
            baseline += bAlpha * (smoothMag - baseline)
            if (!rotationHold) {
                baselineX += bAlpha * (s.x - baselineX)
                baselineY += bAlpha * (s.y - baselineY)
                baselineZ += bAlpha * (s.z - baselineZ)
            }
        }

        // 7) 복수 알고리즘 점수
        val a1 = deltaAbs / threshold
        val a2 = if (!rotationHold) vectorDelta / (threshold * 1.6) else 0.0
        val a3 = abs(rate) / (threshold * 8.0)
        val stability = (1.0 - noise / (threshold * 0.9)).coerceIn(0.0, 1.0)
        val a4 = a1 * stability
        val fraction = window.fractionAbove(threshold * 0.5)
        val a5 = ((fraction - 0.45) / 0.45).coerceIn(0.0, 1.0)
        val a6 = cusumScore.coerceAtMost(4.0)
        var agreeing = 0
        if (a1 >= 1.0) agreeing++
        if (a2 >= 1.0) agreeing++
        if (a3 >= 1.0) agreeing++
        if (a4 >= 1.0) agreeing++
        if (a5 >= 1.0) agreeing++
        if (a6 >= 1.0) agreeing++
        val score = max(a1, max(a2, max(a3, max(a4, max(a5, a6))))).coerceAtMost(4.0)

        // 8) 지속·반복 판정. 누적이 아주 크면 다른 알고리즘이 동의하지 않아도 지속 변화로 본다.
        //    이 기능은 "자기장을 재는" 것이 아니라 **자성 물체를 찾는** 것이다. 그래서 움직임이 멈춘 뒤
        //    일정 시간 동안 창 잡음이 낮고(정지) 변화가 유지될 때만 판정한다. 손으로 흔들거나 걷는
        //    동안에는 아예 올리지 않는다.
        val wasClear = state == MagneticState.CLEAR
        val stillFor = if (lastMovingMs == 0L) Long.MAX_VALUE else s.atMs - lastMovingMs
        val still = !moving && stillFor >= p.stillRequiredMs
        val fieldStable = noise <= threshold * p.stableNoiseRatio
        val over = still && (
            (fieldStable && agreeing >= 2 && score >= 1.0) ||
                cusumScore >= p.cusumSolo
            )
        val soft = still && agreeing >= 1 && score >= 0.7
        if (over) {
            if (overSinceMs == 0L) {
                overSinceMs = s.atMs
                // 같은 변화가 임계 아래로 내려갔다가 다시 올라올 때만 반복으로 센다.
                if (armed) {
                    armed = false
                    repeats++
                }
            }
            lastOverMs = s.atMs
            state = if (s.atMs - overSinceMs >= p.holdMs) MagneticState.DETECTED else MagneticState.TRANSIENT
        } else {
            overSinceMs = 0L
            // 확정 감지는 근거가 약해져도 임계의 [MagneticProfile.releaseRatio] 위에 있으면 잠깐 유지한다.
            // 가장자리에서 "자성 물체 의심"과 "변화 없음"이 깜빡이는 것을 막는다.
            val sinceOver = if (lastOverMs > 0L) s.atMs - lastOverMs else Long.MAX_VALUE
            val sticky = still && state == MagneticState.DETECTED &&
                sinceOver <= p.stickyHoldMs &&
                deltaAbs >= threshold * p.releaseRatio
            state = when {
                sticky -> MagneticState.DETECTED
                soft -> MagneticState.TRANSIENT
                else -> MagneticState.CLEAR
            }
        }
        // 기록을 묶는 "이번 변화" 구간. 조용해지면 끝난다.
        if (state != MagneticState.CLEAR) {
            if (wasClear || runStartMs == 0L) {
                runStartMs = s.atMs
                runPeak = deltaAbs
            } else {
                runPeak = max(runPeak, deltaAbs)
            }
        } else {
            runStartMs = 0L
            runPeak = 0.0
        }
        if (deltaAbs < threshold * p.releaseRatio) {
            if (quietSinceMs == 0L) quietSinceMs = s.atMs
            if (!armed && s.atMs - quietSinceMs >= REARM_MS) armed = true
        } else {
            quietSinceMs = 0L
        }
        // 오래 조용하면 반복 횟수를 0으로 되돌린다.
        if (state == MagneticState.CLEAR && repeats > 0 && lastOverMs > 0L && s.atMs - lastOverMs > p.repeatWindowMs) {
            repeats = 0
        }

        return MagneticReading(
            state = state,
            magnitude = smoothMag,
            baseline = baseline,
            delta = delta,
            vectorDelta = vectorDelta,
            ratePerSec = rate,
            noise = noise,
            score = score,
            agreeing = agreeing,
            runStartMs = runStartMs,
            moving = moving,
            repeats = repeats,
            durationMs = if (state != MagneticState.CLEAR && runStartMs > 0L) s.atMs - runStartMs else 0L,
            peakDelta = runPeak,
            x = smoothX,
            y = smoothY,
            z = smoothZ,
            strength = (score / 2.0).coerceIn(0.0, 1.0),
            thresholdMicroTesla = threshold,
            cusumScore = cusumScore,
        )
    }

    private fun calibrateInternal() {
        seeded = false
        seedCount = 0
        seedSumX = 0.0
        seedSumY = 0.0
        seedSumZ = 0.0
        window.clear()
        rawCount = 0
        lastFastRotationMs = 0L
        lastMovingMs = 0L
        overSinceMs = 0L
        lastOverMs = 0L
        quietSinceMs = 0L
        seededAtMs = 0L
        cusumUp = 0.0
        cusumDown = 0.0
        noiseFloor = 0.0
        armed = true
        runStartMs = 0L
        runPeak = 0.0
        repeats = 0
        state = MagneticState.CALIBRATING
    }

    private fun lastReading() = MagneticReading(state = state, magnitude = smoothMag, baseline = baseline)

    /**
     * 기기별 잡음에 맞춘 실제 임계.
     *
     * 센서 잡음이 큰 기기에서 최대 감지(0.6 μT)를 그대로 쓰면 오탐이 잦아진다. 조용한 구간에서만 잡음
     * 바닥을 천천히 갱신해 두고, 그 잡음의 [MagneticProfile.noiseThresholdFactor]배까지 임계를 올린다.
     * 단, [MagneticProfile.noiseThresholdCap]배를 넘지 않게 해 조용한 기기의 감도를 깎지 않는다.
     * 레이더·소나에서 쓰는 CFAR(일정 오경보율)과 같은 원리다.
     */
    private fun effectiveThreshold(noise: Double, deltaAbs: Double, moving: Boolean, dtMs: Long): Double {
        val base = p.thresholdMicroTesla
        if (!moving && state == MagneticState.CLEAR && deltaAbs < base * 0.6) {
            val alpha = if (dtMs == 0L) 1.0 else (dtMs.toDouble() / (dtMs + NOISE_TAU_MS).toDouble()).coerceIn(0.0, 0.25)
            noiseFloor += alpha * (noise - noiseFloor)
        }
        return max(base, min(noiseFloor * p.noiseThresholdFactor, base * p.noiseThresholdCap))
    }

    private fun median3(a: Double, b: Double, c: Double): Double = when {
        a < b -> if (b < c) b else max(a, c)
        else -> if (a < c) a else max(b, c)
    }

    private fun medianOf(values: DoubleArray, count: Int): Double {
        if (count <= 0) return 0.0
        val copy = values.copyOf(count)
        copy.sort()
        return if (count % 2 == 1) copy[count / 2] else (copy[count / 2 - 1] + copy[count / 2]) / 2.0
    }
}
