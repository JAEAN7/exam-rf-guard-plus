package com.classroom.rfdetector.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 지예거 모드: 화면을 안 봐도 진동 주기로 근접을 알린다.
 * 바로 옆 350ms / 가까움 800ms / 중간 1.6s / 멀다 3s 주기로 짧게 울린다.
 */
class GeigerController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    /** 연속 삐 소리에 재사용하는 톤 생성기. 처음 울릴 때 만든다. */
    private var tone: ToneGenerator? = null

    @Volatile
    private var cls: DistanceClass = DistanceClass.UNKNOWN

    fun setClass(c: DistanceClass) {
        cls = c
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val period = when (cls) {
                    DistanceClass.VERY_CLOSE -> 350L
                    DistanceClass.CLOSE -> 800L
                    DistanceClass.NEAR -> 1600L
                    DistanceClass.FAR -> 3000L
                    DistanceClass.UNKNOWN -> 0L
                }
                if (period == 0L) {
                    delay(400)
                    continue
                }
                val dur = if (cls == DistanceClass.VERY_CLOSE) 130L else 60L
                runCatching {
                    vibrator.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                delay(period)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { vibrator.cancel() }
    }

    /** 새 전파나 금속 변화를 처음 확인했을 때 한 번만 짧게 두 번 울린다. 소리는 [beep]가 따로 낸다. */
    fun pulse() {
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 140L, 110L, 140L), -1))
        }
    }

    /**
     * 짧은 "삐". 금속 탐지기의 연속 경보에 쓴다.
     *
     * 매번 ToneGenerator를 만들면 비싸므로 하나를 만들어 재사용한다. 소리는 미디어 스트림으로 내고,
     * 휴대폰 미디어 음량을 따른다. 진동은 여기서 울리지 않는다([pulse]와 따로 켜고 끈다).
     */
    fun beep(durationMs: Int = 60) {
        val generator = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
            .getOrNull()
            ?.also { tone = it }
            ?: return
        runCatching { generator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs) }
    }

    /** 톤 생성기를 정리한다. 화면을 닫거나 앱이 끝날 때 부른다. */
    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
