package kr.reformparty.koriyoscan.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kr.reformparty.koriyoscan.AppViewModel
import kr.reformparty.koriyoscan.core.SensitivityMode

/**
 * 금속·자석 탐지 화면.
 *
 * 전파 감시 화면과 **완전히 분리된 화면**이다. 자기장 센서는 권한도, 무선도, 비행기 모드도
 * 필요 없으므로 여기서 따로 켜고 끈다. 빈 교실 기록을 만들지 않았거나 시험 감시 중이 아니어도
 * 바로 쓸 수 있고, 전파 감시와 동시에 켜 둘 수도 있다.
 *
 * 배치는 "지금 해야 할 일" 순서다. 실시간 상태·막대가 맨 위에 오고, 시작/종료 버튼은 화면 아래에
 * 고정해 엄지로 바로 누를 수 있게 한다. 감도와 설명은 그 아래로 내린다.
 */
@Composable
internal fun MagneticScreen(vm: AppViewModel) {
    val reading by vm.magneticReading.collectAsState()
    val history by vm.magneticHistory.collectAsState()
    val events by vm.magneticEvents.collectAsState()
    val alertOn by vm.magneticAlert.collectAsState()
    val soundOn by vm.magneticSound.collectAsState()
    val active by vm.magneticActive.collectAsState()
    val mode by vm.magneticMode.collectAsState()
    val simulation by vm.simMode.collectAsState()
    // 가상 전파로 도는 자동 테스트에서는 센서가 없어도 같은 분석 경로를 돌려 화면을 확인한다.
    val available = vm.magneticAvailable || simulation

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = RfSpace.xl, end = RfSpace.xl, top = RfSpace.m, bottom = RfSpace.l),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!available) {
                item {
                    Surface(color = RfColors.dangerDim, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.danger.copy(alpha = 0.3f))) {
                        Text(
                            "이 휴대폰에는 자기장 센서가 없어 금속 탐지를 쓸 수 없습니다.",
                            Modifier.fillMaxWidth().padding(RfSpace.l),
                            color = RfColors.dangerText, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item { MagneticNotesCard() }
                return@LazyColumn
            }

            // 켜기 전에만 무엇을 하는 화면인지 설명한다. 측정 중에는 같은 말을 반복하지 않는다.
            if (!active) {
                item {
                    Surface(color = RfColors.surface, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.line)) {
                        Column(Modifier.fillMaxWidth().padding(RfSpace.l), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RfGlyph(RfSymbol.MAGNET, size = 26.dp, color = RfColors.primary)
                                Text("금속·자석 찾기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
Text(
                            "전파가 아니라 휴대폰의 자기 센서로 철·니켈 제품과 자석을 찾습니다. " +
                                "무선을 꺼도 되고 권한도 필요 없으며, 전파 감시와 따로 켜고 끕니다.",
                            color = RfColors.textDim, style = MaterialTheme.typography.bodyMedium,
                        )
                        }
                    }
                }
            }

            if (active) {
                item {
                    MagneticPanel(
                        reading = reading,
                        history = history,
                        events = events,
                        alertOn = alertOn,
                        soundOn = soundOn,
                        available = available,
                        fastestHz = vm.magneticFastestHz,
                        uncalibrated = vm.magneticUncalibrated,
                        accuracy = vm.magneticAccuracy,
                        accuracyKnown = vm.magneticAccuracyKnown,
                        onCalibrate = { vm.calibrateMagnetic() },
                        onAlertToggle = { vm.setMagneticAlert(it) },
                        onSoundToggle = { vm.setMagneticSound(it) },
                        onClearEvents = { vm.clearMagneticEvents() },
                    )
                }
            } else {
                item {
                    Surface(color = RfColors.surfaceAlt, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.line)) {
                        Text(
                            "금속 탐지가 꺼져 있습니다. 아래 시작을 누르면 1~2초 동안 주변 기준을 잡은 뒤 작은 변화를 살핍니다.",
                            Modifier.fillMaxWidth().padding(RfSpace.l),
                            color = RfColors.textDim, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (events.isNotEmpty()) item {
                    MagneticLog(events) { vm.clearMagneticEvents() }
                }
            }

            // 감도와 설명은 측정 중에 자주 만지는 값이 아니므로 실시간 표시 아래에 둔다.
            item {
                Surface(color = RfColors.surface, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.line)) {
                    Column(Modifier.fillMaxWidth().padding(RfSpace.l), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("탐지 감도", fontWeight = FontWeight.SemiBold)
                        MagneticSensitivityPicker(mode) { vm.setMagneticMode(it) }
                        if (active) Text(
                            "감도를 바꾸면 주변 기준을 다시 잡습니다. 잠시 가만히 들고 있으면 곧 측정이 이어집니다.",
                            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            item { MagneticNotesCard() }
        }

        // 화면 아래에 고정한다. 걷거나 손을 뻗은 상태에서도 한 손으로 바로 끌 수 있어야 한다.
        if (available) {
            Surface(color = RfColors.surface) {
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(RfColors.line))
                    Column(Modifier.fillMaxWidth().padding(start = RfSpace.xl, end = RfSpace.xl, top = RfSpace.s, bottom = RfSpace.m)) {
                        if (active) {
                            OutlinedButton(
                                onClick = { vm.stopMagnetic() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(RfRadius.m),
                            ) {
                                Text("측정 끝내기", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Button(
                                onClick = { vm.startMagnetic() },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                                shape = RoundedCornerShape(RfRadius.m),
                                colors = ButtonDefaults.buttonColors(containerColor = RfColors.primary, contentColor = RfColors.onPrimary),
                            ) {
                                Text("금속 탐지 시작", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 금속 탐지 감도. 전파 화면의 감도와 별도로 기억한다. */
@Composable
private fun MagneticSensitivityPicker(current: SensitivityMode, onSelect: (SensitivityMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SensitivityMode.entries.forEach { mode ->
                FilterChip(
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(
            when (current) {
                SensitivityMode.STANDARD -> "잡음과 손떨림에 덜 흔들리는 대신, 아주 작은 변화는 놓칠 수 있습니다."
                SensitivityMode.MAX -> "더 작은 자석까지 잡지만, 손떨림과 주변 잡음도 함께 감지로 올라올 수 있습니다."
            },
            color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 한계와 쓰는 법. 화면 맨 아래에 한 번만 둔다(패널 안내와 중복하지 않는다). */
@Composable
private fun MagneticNotesCard() {
    Surface(color = RfColors.surface, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.line)) {
        Column(Modifier.fillMaxWidth().padding(RfSpace.l), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("알아 둘 점", fontWeight = FontWeight.Bold)
            Text(
                "철·니켈 같은 자성 금속과 자석만 잡습니다. 알루미늄·구리·금 같은 비자성 금속은 잡히지 않습니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "움직이는 동안에는 판정하지 않습니다. 물체가 의심되는 곳에서 1초간 멈춰 주세요.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "휴대폰을 1~5cm 안쪽으로 가져가야 하며, 잡히는 거리는 물체 크기와 자성에 따라 크게 달라집니다. " +
                    "잡은 위치에서 방향을 바꿔 가며 세기가 가장 커지는 쪽을 찾으세요.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "전파 감시와 별개라서, 감시를 켜 둔 채로도 이 화면에서 금속을 확인할 수 있습니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}