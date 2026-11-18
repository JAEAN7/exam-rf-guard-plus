package kr.reformparty.koriyoscan.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 자기장 분석기는 안드로이드에 의존하지 않으므로 여기서 알고리즘 자체를 검증한다.
 * 표본은 100Hz(10ms 간격)로 넣는다. 실제 센서도 이 정도 속도로 들어온다.
 */
class MagneticAnalyzerTest {

    /** 기준이 되는 조용한 자기장(24, -31, 18) → 크기 약 43.14 µT. */
    private val quietX = 24.0
    private val quietY = -31.0
    private val quietZ = 18.0
    private val quietMagnitude = MagneticSample(quietX, quietY, quietZ, 0L).magnitude

    private class Driver(val analyzer: MagneticAnalyzer, val onSample: (MagneticSample) -> MagneticReading) {
        var t = 0L
        fun step(x: Double, y: Double, z: Double, moving: Boolean = false): MagneticReading {
            t += 10
            return onSample(MagneticSample(x, y, z, t, deviceMoving = moving))
        }
        fun run(seconds: Double, x: Double, y: Double, z: Double): MagneticReading {
            var last = MagneticReading()
            repeat((seconds * 100).toInt()) { last = step(x, y, z) }
            return last
        }
        fun runMoving(seconds: Double, x: Double, y: Double, z: Double, moving: Boolean): MagneticReading {
            var last = MagneticReading()
            repeat((seconds * 100).toInt()) { last = step(x, y, z, moving) }
            return last
        }
    }

    private fun driver(analyzer: MagneticAnalyzer): Driver {
        var latest = MagneticReading()
        fun feed(sample: MagneticSample): MagneticReading {
            latest = analyzer.onSample(sample)
            return latest
        }
        return Driver(analyzer) { feed(it) }
    }

    private fun max() = MagneticAnalyzer(MagneticProfile.of(SensitivityMode.MAX))

    @Test fun quietRoomReachesClearWithCorrectBaseline() {
        val d = driver(max())
        val reading = d.run(2.0, quietX, quietY, quietZ)
        assertEquals(MagneticState.CLEAR, reading.state)
        assertTrue("기준값이 실제 크기와 맞아야 한다: ${reading.baseline}", abs(reading.baseline - quietMagnitude) < 0.5)
    }

    @Test fun sustainedSmallChangeIsDetected() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        // +2 µT면 크기가 1.14 µT 올라간다. 최대 감지 임계 0.6 µT를 넘는다.
        val reading = d.run(1.5, quietX + 2.0, quietY, quietZ)
        assertEquals(MagneticState.DETECTED, reading.state)
        assertTrue("여러 알고리즘이 동의해야 한다: ${reading.agreeing}", reading.agreeing >= 2)
        assertTrue(reading.delta > 0.6)
    }

    @Test fun standardModeIgnoresWhatMaximumModeCatches() {
        val standard = driver(MagneticAnalyzer(MagneticProfile.of(SensitivityMode.STANDARD)))
        standard.run(1.0, quietX, quietY, quietZ)
        val ignored = standard.run(1.5, quietX + 1.0, quietY, quietZ)
        assertNotEquals("표준 모드는 1 µT 변화를 감지로 올리면 안 된다", MagneticState.DETECTED, ignored.state)

        val sensitive = driver(max())
        sensitive.run(1.0, quietX, quietY, quietZ)
        val caught = sensitive.run(1.5, quietX + 1.0, quietY, quietZ)
        assertEquals(MagneticState.DETECTED, caught.state)
    }

    @Test fun singleSpikeIsTransientAndDoesNotMoveTheBaseline() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        val spike = d.step(quietX + 20.0, quietY, quietZ)
        assertNotEquals("한 표본만 튄 것은 감지로 확정하면 안 된다", MagneticState.DETECTED, spike.state)
        val after = d.run(0.6, quietX, quietY, quietZ)
        assertEquals(MagneticState.CLEAR, after.state)
        assertTrue("튄 값이 기준값을 끌고 가면 안 된다: ${after.baseline}", abs(after.baseline - quietMagnitude) < 0.3)
    }

    @Test fun handWobbleDoesNotMoveTheBaseline() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        val random = Random(42)
        repeat(300) {
            val wobble = (random.nextDouble() - 0.5) * 6.0
            d.step(quietX + wobble, quietY + wobble, quietZ)
        }
        val reading = d.run(0.5, quietX, quietY, quietZ)
        assertTrue("손떨림으로 기준값이 밀리면 안 된다: ${reading.baseline}", abs(reading.baseline - quietMagnitude) < 1.0)
    }

    @Test fun repeatedPassesAreCounted() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        d.run(1.0, quietX + 2.0, quietY, quietZ)
        d.run(1.2, quietX, quietY, quietZ)
        val second = d.run(1.0, quietX + 2.0, quietY, quietZ)
        assertTrue("같은 자리를 두 번 지나가면 반복으로 세야 한다: ${second.repeats}", second.repeats >= 2)
    }

    @Test fun calibrationRecapturesTheNewBaseline() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        // 다른 교실로 옮긴 것처럼 주변 자기장이 통째로 달라졌다.
        val movedX = 40.0
        val movedMagnitude = MagneticSample(movedX, quietY, quietZ, 0L).magnitude
        d.analyzer.calibrate()
        val first = d.step(movedX, quietY, quietZ)
        assertEquals(MagneticState.CALIBRATING, first.state)
        val settled = d.run(1.0, movedX, quietY, quietZ)
        assertEquals(MagneticState.CLEAR, settled.state)
        assertTrue("새 주변값으로 기준을 다시 잡아야 한다: ${settled.baseline}", abs(settled.baseline - movedMagnitude) < 0.5)
    }

    @Test fun rotatingThePhoneDoesNotTrigger() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        // 크기는 그대로 두고 방향만 90도씩 돌린다. 지구 자기장에서 실제로 일어나는 일이다.
        val r = quietMagnitude
        val orientations = listOf(
            Triple(r, 0.0, 0.0),
            Triple(0.0, r, 0.0),
            Triple(-r, 0.0, 0.0),
            Triple(0.0, -r, 0.0),
        )
        var reading = MagneticReading()
        repeat(50) {
            val (x, y, z) = orientations[it % orientations.size]
            reading = d.run(0.1, x, y, z)
        }
        assertEquals("휴대폰을 돌리는 것만으로 감지하면 안 된다", MagneticState.CLEAR, reading.state)
    }

    @Test fun slowSubThresholdShiftIsCaughtByAccumulation() {
        val d = driver(MagneticAnalyzer(MagneticProfile.of(SensitivityMode.STANDARD)))
        // 기준을 잡고 누적 검정 예열(1.5초)이 끝날 때까지 조용히 둔다.
        d.run(2.0, quietX, quietY, quietZ)

        // +1.5 μT는 크기를 0.85 μT만 올려 표준 임계 1.5 μT에는 못 미친다.
        // 순간 점수만 보면 놓치지만, 같은 방향으로 계속 벌어지므로 누적 검정이 잡아야 한다.
        val peak = d.run(3.0, quietX + 1.5, quietY, quietZ)
        assertEquals("순간 임계에 못 미쳐도 지속되면 누적으로 잡아야 한다", MagneticState.DETECTED, peak.state)
        assertTrue("누적 점수가 경보 기준을 넘어야 한다: ${peak.cusumScore}", peak.cusumScore >= 1.0)
    }

    @Test fun accumulationReleasesAfterTheObjectLeaves() {
        val d = driver(MagneticAnalyzer(MagneticProfile.of(SensitivityMode.STANDARD)))
        d.run(2.0, quietX, quietY, quietZ)
        d.run(3.0, quietX + 1.5, quietY, quietZ)
        val cleared = d.run(2.0, quietX, quietY, quietZ)
        assertEquals("물체가 떠나면 다시 조용해져야 한다", MagneticState.CLEAR, cleared.state)
        assertTrue("누적도 함께 풀려야 한다: ${cleared.cusumScore}", cleared.cusumScore < 1.0)
    }

    @Test fun alternatingWobbleDoesNotAccumulate() {
        val d = driver(MagneticAnalyzer(MagneticProfile.of(SensitivityMode.STANDARD)))
        d.run(2.0, quietX, quietY, quietZ)
        var reading = MagneticReading()
        repeat(300) {
            val shift = if (it % 2 == 0) 1.5 else -1.5
            reading = d.step(quietX + shift, quietY, quietZ)
        }
        assertEquals("부호가 오가는 손떨림은 누적으로 잡으면 안 된다", MagneticState.CLEAR, reading.state)
    }

    @Test fun quietDeviceKeepsProfileThreshold() {
        val d = driver(max())
        val reading = d.run(3.0, quietX, quietY, quietZ)
        assertEquals("잡음이 없으면 최대 감지 임계 0.6 μT를 그대로 써야 한다", 0.6, reading.thresholdMicroTesla, 1e-9)
    }

    @Test fun noisyDeviceRaisesTheEffectiveThreshold() {
        val d = driver(max())
        // ±0.6 μT가 표본마다 번갈아 들어오는, 잡음이 큰 센서를 흉내 낸다.
        // 잡음 바닥은 5초 시간상수로 천천히 따라가므로 충분히 오래 둔다.
        var reading = MagneticReading()
        repeat(1500) { i ->
            val wobble = if (i % 2 == 0) 0.6 else -0.6
            reading = d.step(quietX + wobble, quietY, quietZ)
        }
        assertTrue(
            "잡음이 크면 임계를 올려 오탐을 줄여야 한다: ${reading.thresholdMicroTesla}",
            reading.thresholdMicroTesla > 0.6,
        )
    }

    @Test fun confirmedDetectionSticksThroughBriefDip() {
        val d = driver(max())
        d.run(0.4, quietX, quietY, quietZ)          // 기준 잡기(40표본)
        d.run(1.8, quietX + 2.0, quietY, quietZ)    // 정지 확인·유지 시간을 넘겨 확정
        // 물체가 조금 멀어져 순간 점수는 임계 아래로 내려가지만, 확정 감지는 잠깐 유지한다.
        val dipped = d.run(0.4, quietX + 0.7, quietY, quietZ)
        assertEquals("확정 감지는 잠깐 약해져도 유지해야 한다", MagneticState.DETECTED, dipped.state)
        val cleared = d.run(1.5, quietX, quietY, quietZ)
        assertEquals("물체가 떠난 뒤에는 풀려야 한다", MagneticState.CLEAR, cleared.state)
    }

    @Test fun shakingWhileMovingDoesNotTrigger() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        var reading = MagneticReading()
        repeat(250) { i ->
            val seconds = i * 0.01
            // 3Hz로 ±3 μT 흔드는 상황. 손으로 흔들면 대략 이런 변화가 들어온다.
            val shift = 3.0 * sin(2 * PI * 3.0 * seconds)
            reading = d.step(quietX + shift, quietY, quietZ, moving = true)
        }
        assertEquals("흔드는 동안에는 자석으로 판정하지 않는다", MagneticState.CLEAR, reading.state)
    }

    @Test fun strongMagnetAlertsAfterMotionStops() {
        val d = driver(max())
        d.run(1.0, quietX, quietY, quietZ)
        // 움직이는 동안에는 일부러 알리지 않는다.
        d.runMoving(0.5, quietX + 10.0, quietY, quietZ, moving = true)
        // 멈춘 뒤 같은 자리에 물체가 있으면 정적인 변화로 확정한다.
        val reading = d.run(2.0, quietX + 10.0, quietY, quietZ)
        assertEquals("멈춘 뒤 지속되는 강한 변화는 자성 물체로 알린다", MagneticState.DETECTED, reading.state)
    }

    @Test fun beepIntervalShortensWithStrength() {
        assertEquals("신호가 없으면 가장 느리다", 650L, magneticBeepIntervalMs(0.0))
        assertEquals("중간 세기", 390L, magneticBeepIntervalMs(0.5))
        assertEquals("가장 가까우면 가장 빠르다", 130L, magneticBeepIntervalMs(1.0))
        assertEquals("범위를 벗어난 값은 잘라 낸다", 650L, magneticBeepIntervalMs(-1.0))
        assertEquals(130L, magneticBeepIntervalMs(2.0))
    }
}
