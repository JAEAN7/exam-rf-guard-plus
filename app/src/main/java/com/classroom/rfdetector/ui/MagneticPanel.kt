package com.classroom.rfdetector.ui

import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.core.MagneticEvent
import com.classroom.rfdetector.core.MagneticReading
import com.classroom.rfdetector.core.MagneticState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 자기장 감지 패널.
 *
 * 기본 화면에는 "얼마나 가까운지"만 큰 막대와 문구로 보여 준다. X·Y·Z 값, 알고리즘 동의 수 같은
 * 기술적인 값은 "자세히"를 펼쳤을 때만 보인다. 사용하는 사람이 숫자를 해석하지 않아도 판단할 수 있게 한다.
 */
@Composable
internal fun MagneticPanel(
    reading: MagneticReading,
    history: List<Double>,
    events: List<MagneticEvent>,
    alertOn: Boolean,
    soundOn: Boolean,
    available: Boolean,
    fastestHz: Int,
    uncalibrated: Boolean,
    accuracy: Int,
    accuracyKnown: Boolean,
    onCalibrate: () -> Unit,
    onAlertToggle: (Boolean) -> Unit,
    onSoundToggle: (Boolean) -> Unit,
    onClearEvents: () -> Unit,
) {
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val accent = magneticAccent(reading.state)
    val detected = reading.state == MagneticState.DETECTED

    Surface(
        color = if (detected) RfColors.dangerDim else RfColors.surface,
        shape = RoundedCornerShape(RfRadius.l),
        // 감지 중에는 테두리로도 알린다. 어두운 교실에서 색만으로는 놓치기 쉽다.
        border = BorderStroke(1.dp, if (detected) accent.copy(alpha = 0.7f) else RfColors.line),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(RfSpace.l),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RfGlyph(RfSymbol.MAGNET, size = 20.dp, color = accent)
                Text("자성 물체 확인", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                StatePill(reading.state.label, accent)
            }

            if (!available) {
                Text("이 휴대폰에는 자기장 센서가 없어 이 기능을 쓸 수 없습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                return@Column
            }

            MagneticBar(
                strength = reading.strength,
                accent = accent,
                state = reading.state,
                peak = if (reading.thresholdMicroTesla > 0.0) (reading.peakDelta / reading.thresholdMicroTesla).toFloat() else 0f,
            )
            Text(magneticGuidance(reading, accuracy, accuracyKnown), color = RfColors.textDim, style = MaterialTheme.typography.bodyMedium)

            OutlinedButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) { Text("주변 기준 다시 잡기") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("진동 알림")
                    Text("감지가 확정되면 짧게 두 번 울립니다.", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = alertOn,
                    onCheckedChange = onAlertToggle,
                    modifier = Modifier.semantics { contentDescription = "진동 알림" },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("삐 소리")
                    Text(
                        "실제 금속 탐지기처럼 신호가 세질수록 빨리 울립니다. 미디어 음량을 따릅니다.",
                        color = RfColors.textDim, style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = soundOn,
                    onCheckedChange = onSoundToggle,
                    modifier = Modifier.semantics { contentDescription = "삐 소리" },
                )
            }

            MagneticFoldRow("센서 값 자세히 보기", detailsOpen) { detailsOpen = !detailsOpen }
            if (detailsOpen) {
                if (history.size >= 2) {
                    Text("최근 변화 추이 (가운데 선이 0)", color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
                    MagneticTrace(history, accent)
                }
                MagneticDetails(reading, fastestHz, uncalibrated, accuracy, accuracyKnown)
            }

            MagneticFoldRow("금속 감지 기록 ${events.size}건", logOpen) { logOpen = !logOpen }
            if (logOpen) {
                if (events.isEmpty()) {
                    Text("아직 감지된 변화가 없습니다.", color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
                } else {
                    events.forEach { MagneticEventRow(it) }
                    TextButton(onClick = onClearEvents) { Text("기록 지우기") }
                }
            }

            Text(
                "자성 금속·자석만 잡습니다. 알루미늄·구리·금은 잡히지 않습니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 금속 탐지를 끈 뒤에도 남겨 두는 기록. 켜져 있을 때는 패널 안에 같은 목록이 있다. */
@Composable
internal fun MagneticLog(events: List<MagneticEvent>, onClearEvents: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    // LazyColumn의 item 하나에 여러 요소를 넣으므로 Column으로 묶어야 세로로 쌓인다.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MagneticFoldRow("금속 감지 기록 ${events.size}건", open) { open = !open }
        if (open) {
            Text(
                "감시 중 자기장 변화가 감지된 시각과 최고 변화량입니다. 부정행위의 증거가 아니며, 앱을 닫으면 지워집니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
            events.forEach { MagneticEventRow(it) }
            TextButton(onClick = onClearEvents) { Text("기록 지우기") }
        }
    }
}

private fun magneticAccent(state: MagneticState): Color = when (state) {
    MagneticState.DETECTED -> RfColors.danger
    MagneticState.TRANSIENT -> RfColors.textPrimary
    MagneticState.CLEAR -> RfColors.textMuted
    MagneticState.CALIBRATING, MagneticState.OFFLINE -> RfColors.textMuted
}

/** 상태를 색 점 + 알약으로 보여 준다. 글자만 있을 때보다 한눈에 들어오고, 색만으로 읽지 않아도 된다. */
@Composable
private fun StatePill(text: String, accent: Color) {
    Surface(color = accent.copy(alpha = 0.2f), shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(text, color = RfColors.textPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private fun magneticGuidance(reading: MagneticReading, accuracy: Int, accuracyKnown: Boolean): String = when {
    // 센서 정확도가 낮으면 판정을 믿기 어렵다. 공개 금속 탐지 앱들도 이때 8자 보정을 안내한다.
    // 다만 감지 중에는 이 안내를 띄우지 않는다. 자성 물체가 센서 정확도를 떨어뜨리는 경우가 많아,
    // 정작 "무엇을 확인해야 하는지"를 가리게 되기 때문이다.
    accuracyKnown && accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW &&
        (reading.state == MagneticState.CLEAR || reading.state == MagneticState.CALIBRATING) ->
        "자기 센서 정확도가 낮습니다. 휴대폰을 8자 모양으로 여러 번 돌려 보정한 뒤 ‘주변 기준 다시 잡기’를 누르세요."
    reading.moving -> "지금은 움직이는 중이라 판정하지 않습니다. 의심되는 곳에서 멈추고 1초간 그대로 들고 있으세요."
    reading.state == MagneticState.CALIBRATING -> "가만히 들고 있으면 주변 기준을 잡습니다. 자성 물체에서 떨어진 곳에서 하세요."
    reading.state == MagneticState.DETECTED && reading.durationMs >= 15_000L ->
        "감지가 15초 넘게 이어집니다. 물체를 치웠는데도 계속되면 ‘주변 기준 다시 잡기’를 누르세요."
    reading.state == MagneticState.DETECTED -> "자석이나 철·니켈 물체가 가까이 있을 수 있습니다. 그 자리에서 멈추고 천천히 방향을 바꿔 확인하세요."
    reading.state == MagneticState.TRANSIENT -> "잠깐 흔들렸습니다. 같은 자리에서 멈추고 1초간 그대로 있어 보세요."
    else -> "휴대폰을 낮게 들고 천천히 지나가다가, 반응이 보이면 멈추고 1초간 그대로 들고 있으세요."
}

/** 시스템 정확도 값을 화면 문구로. 아직 콜백이 없으면 null. */
private fun accuracyLabel(accuracy: Int, known: Boolean): String? = if (!known) null else when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "높음"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "보통"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "낮음"
    else -> "믿기 어려움"
}

/** 임계 대비 세기를 막대로만 보여 준다. 숫자는 "자세히"에서만 본다. */
@Composable
private fun MagneticBar(strength: Double, accent: Color, state: MagneticState, peak: Float) {
    val filled = strength.coerceIn(0.0, 1.0).toFloat()
    // 그리기 람다 안에서 전역 색을 읽지 않는다. 테마가 바뀌어도 확실히 다시 그려지도록 미리 읽어 둔다.
    val markerColor = RfColors.textPrimary
    Box(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(RfColors.surfaceHigh, RoundedCornerShape(17.dp))
            .border(1.dp, RfColors.line, RoundedCornerShape(17.dp))
            .semantics { contentDescription = "자기장 변화 정도 ${(filled * 100).toInt()}퍼센트 · ${state.label}" },
    ) {
        if (filled > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth(filled)
                    .height(34.dp)
                    .background(accent, RoundedCornerShape(17.dp)),
            )
        }
        // 임계 위치(세기 0.5) 표시. 점선이라 최고점 표시와 헷갈리지 않는다.
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val x = size.width * 0.5f
            drawLine(
                markerColor.copy(alpha = 0.45f),
                Offset(x, 3f), Offset(x, size.height - 3f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            // 이번 변화에서 가장 세게 잡혔던 지점. 채워진 막대 위에서도 보이도록 밝은 실선으로 그린다.
            if (peak > 0.02f) {
                val px = size.width * peak.coerceIn(0f, 1f)
                drawLine(markerColor.copy(alpha = 0.9f), Offset(px, 0f), Offset(px, size.height), 2.5f)
            }
        }
    }
}

@Composable
private fun MagneticFoldRow(title: String, open: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f), color = RfColors.textDim)
        Text(if (open) "▴" else "▾", color = RfColors.textDim)
    }
}

@Composable
private fun MagneticDetails(reading: MagneticReading, fastestHz: Int, uncalibrated: Boolean, accuracy: Int, accuracyKnown: Boolean) {
    val sensor = buildString {
        append(if (uncalibrated) "보정 안 됨(원본)" else "보정됨")
        append(" · 최대 ")
        append(if (fastestHz > 0) "${fastestHz}Hz" else "모름")
        accuracyLabel(accuracy, accuracyKnown)?.let { append(" · 정확도 ").append(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MagneticValueRow("3축", "X ${fmt(reading.x)} · Y ${fmt(reading.y)} · Z ${fmt(reading.z)} μT")
        MagneticValueRow("자기장 크기", "${fmt(reading.magnitude)} μT")
        MagneticValueRow("적응 기준값", "${fmt(reading.baseline)} μT")
        MagneticValueRow("크기 변화", "${signed(reading.delta)} μT")
        MagneticValueRow("벡터 변화", "${fmt(reading.vectorDelta)} μT")
        MagneticValueRow("변화 속도", "${signed(reading.ratePerSec)} μT/s")
        MagneticValueRow("창 잡음", "${fmt(reading.noise)} μT")
        MagneticValueRow("적용 임계", if (reading.thresholdMicroTesla > 0.0) "${fmt(reading.thresholdMicroTesla)} μT · 센서 잡음에 맞춰 자동 조정" else "기준 잡는 중")
        MagneticValueRow("작은 변화 누적", "${fmt(reading.cusumScore)} · 1.0 이상이면 경보")
        MagneticValueRow("알고리즘 동의", "${reading.agreeing} / ${MagneticReading.ALGORITHMS} · 점수 ${fmt(reading.score)}")
        MagneticValueRow("반복", "${reading.repeats}회 · 지속 ${reading.durationMs / 1000.0}s")
        MagneticValueRow("최고 변화", "${fmt(reading.peakDelta)} μT")
        MagneticValueRow("센서", sensor)
    }
}

@Composable
private fun MagneticValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = RfColors.textDim, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MagneticEventRow(event: MagneticEvent) {
    Surface(color = RfColors.surfaceAlt, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(magneticClock(event.startedAtMs), color = RfColors.textDim, style = MaterialTheme.typography.labelSmall)
            Text(
                "최고 ${fmt(event.peakDeltaMicroTesla)} µT · ${event.durationMs / 1000.0}초 · 반복 ${event.repeats}회",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun magneticClock(timeMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))

private fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

private fun signed(value: Double): String = String.format(Locale.getDefault(), "%+.2f", value)

/** 변화량 추이. 0을 가운데 두고 위아래로 그린다. 기술 화면에서만 쓴다. */
@Composable
internal fun MagneticTrace(values: List<Double>, accent: Color) {
    // 그리기 람다 안에서 전역 색을 읽지 않도록 컴포지션에서 미리 읽는다.
    val baselineColor = RfColors.surfaceAlt
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        if (values.size < 2) return@Canvas
        val peak = values.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.5)
        val mid = size.height / 2f
        drawLine(baselineColor, Offset(0f, mid), Offset(size.width, mid), 2f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = mid - (value / peak).toFloat() * (mid - 2f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = accent, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
