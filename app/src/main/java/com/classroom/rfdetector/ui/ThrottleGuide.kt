package com.classroom.rfdetector.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.classroom.rfdetector.BuildConfig
import com.classroom.rfdetector.core.ScanThrottle

/**
 * [FORK 1.1.2] 와이파이 탐색 제한 안내.
 *
 * **원본 프로젝트가 플레이 정책만 따르려면 이 파일 하나를 빼면 된다.** 그러면
 * `BuildConfig.THROTTLE_GUIDE`가 없어져 컴파일이 안 되므로, 아래 둘 중 하나만 하면 된다.
 * 1. 이 파일 삭제 + `ui/GuardScreen.kt`의 `ThrottleGuide(...)`, `ThrottleGuideCard()` 두 호출 삭제
 * 2. 그대로 두고 `app/build.gradle.kts`의 `THROTTLE_GUIDE`를 `false`로 (아무것도 그리지 않음)
 *
 * 안내가 없어져도 **동작**(제한이 꺼진 기기에서 10초 주기)은 `core/ScanThrottle.kt`에 남아 있다.
 */
@Composable
internal fun ThrottleGuide(throttled: Boolean) {
    if (!BuildConfig.THROTTLE_GUIDE) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (throttled) "지금은 Android가 와이파이 탐색을 제한하고 있어 최대 22초 간격으로 확인합니다."
            else "이 기기는 탐색 제한이 꺼져 있어 최대 10초 간격으로 확인합니다.",
            color = if (throttled) RfColors.textDim else RfColors.ok,
            style = MaterialTheme.typography.labelSmall,
        )
        if (throttled) {
            TextButton(
                onClick = { open = !open },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
            ) {
                Text("탐색 제한 끄기 (선택)", Modifier.weight(1f), color = RfColors.textDim, style = MaterialTheme.typography.labelLarge)
                Text(if (open) "▴" else "▾", color = RfColors.textMuted, style = MaterialTheme.typography.labelLarge)
            }
            if (open) {
                Text(
                    "추천하는 선택입니다. 끄면 와이파이를 10초 간격으로 확인해 더 잘 잡습니다. 다만 복잡하게 느껴지거나 부담되면 그대로 두셔도 됩니다. 일부 기기에는 이 설정이 없고, 배터리 사용이 늘 수 있습니다.",
                    color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
                )
                HowToList()
                OpenDeveloperOptionsButton()
            }
        }
    }
}
/** 안내 화면에 들어가는 카드 버전. 감도 카드와 문구가 어긋나지 않게 같은 상수를 쓴다. */
@Composable
internal fun ThrottleGuideCard() {
    if (!BuildConfig.THROTTLE_GUIDE) return
    Surface(color = RfColors.surface, shape = RoundedCornerShape(RfRadius.l), border = BorderStroke(1.dp, RfColors.line)) {
        Column(Modifier.fillMaxWidth().padding(RfSpace.l), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("더 잘 잡으려면 (선택)", fontWeight = FontWeight.Bold)
            Text(
                "개발자 옵션에서 Android의 와이파이 탐색 제한을 끄면, 이 앱이 와이파이를 10초 간격으로 확인해 더 잘 잡습니다. 추천하지만 선택입니다. 설정이 복잡하게 느껴지거나 부담되면 그대로 두셔도 됩니다. 일부 기기에는 이 설정이 없고, 배터리 사용이 늘 수 있습니다.",
                color = RfColors.textDim, style = MaterialTheme.typography.bodySmall,
            )
            Text(ScanThrottle.HOWTO, color = RfColors.textMuted, style = MaterialTheme.typography.bodySmall)
            HowToList()
            OpenDeveloperOptionsButton()
        }
    }
}

/** 단계별 안내. 번호를 붙여 한 줄씩 보여 주고, 검색창 팁을 덧붙인다. */
@Composable
private fun HowToList() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ScanThrottle.HOWTO_STEPS.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${index + 1}.", color = RfColors.textMuted, style = MaterialTheme.typography.labelSmall)
                Text(step, Modifier.weight(1f), color = RfColors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "팁: 개발자 옵션 화면 위쪽 검색창을 쓰면 ‘스캔’만 입력해도 항목이 바로 나옵니다.",
            color = RfColors.textDim, style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 개발자 옵션이 이미 켜져 있으면 **개발자 옵션 화면**을, 아직 꺼져 있으면 **휴대폰 정보(빌드 번호) 화면**을
 * 바로 연다. 한 버튼으로 두 단계를 처리해 사용자가 설정을 뒤질 필요가 없다.
 */
@Composable
private fun OpenDeveloperOptionsButton() {
    val context = LocalContext.current
    TextButton(onClick = {
        val action = if (isDeveloperOptionsEnabled(context)) {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        } else {
            Settings.ACTION_DEVICE_INFO_SETTINGS
        }
        val opened = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }, contentPadding = PaddingValues(horizontal = 2.dp)) {
        Text("개발자 옵션 열기", style = MaterialTheme.typography.labelLarge)
    }
    Text(
        "버튼을 누르면 개발자 옵션이 열립니다. 아직 꺼져 있으면 ‘휴대폰 정보’가 열리니, ‘빌드 번호’를 7번 탭한 뒤 같은 버튼을 다시 눌러 주세요.",
        color = RfColors.textDim, style = MaterialTheme.typography.labelSmall,
    )
}

/** 시스템 설정에서 개발자 옵션이 켜져 있는지. 읽기만 하며, 값이 없으면 꺼진 것으로 본다. */
private fun isDeveloperOptionsEnabled(context: android.content.Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
}.getOrDefault(false)