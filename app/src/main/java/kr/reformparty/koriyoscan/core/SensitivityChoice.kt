package kr.reformparty.koriyoscan.core

/**
 * 사용자가 고르는 전파 감도 선호. 실제 적용되는 모드는 [chooseSensitivity]가 전원 상태와 함께 정한다.
 *
 * - [AUTO]: 기본값. 충전 중이거나 배터리가 넉넉하면 최대 감지, 배터리가 부족하거나 발열·절전이면 배터리 절약.
 * - [MAX]: 항상 최대 감지. 약하고 간헐적인 신호를 더 잡는다.
 * - [SAVER]: 항상 배터리 절약. 배터리와 발열을 아낀다.
 */
enum class SensitivityPreference(val label: String, val description: String) {
    AUTO("자동(권장)", "충전·배터리·발열 상태를 보고 표준(최대)과 배터리 절약을 자동으로 고릅니다. 20초마다 다시 확인합니다."),
    MAX("표준 (최대)", "평소 쓰는 모드입니다. 약하고 간헐적인 신호를 더 오래 보여 주고 먼 기기도 기억합니다. 배터리와 발열이 늘어납니다."),
    SAVER("배터리 절약", "배터리를 아끼면서 일반적인 신호를 찾습니다. 몇 분에 한 번만 잡히는 간헐 신호는 놓칠 수 있습니다."),
}

/**
 * 자동 감도의 동작 규칙. 화면과 문서에 같은 문구를 쓴다.
 *
 * 규칙을 바꾸면 이 상수와 [chooseSensitivity]를 함께 고친다. 테스트가 둘을 함께 검증한다.
 */
const val AUTO_RULES = "자동 규칙: ① 충전 중이거나 배터리 21% 이상이고 발열·절전이 아니면 표준(최대) ② 배터리 20% 이하(충전 중이 아닐 때)이거나 발열·절전이면 배터리 절약 ③ 20초마다 다시 확인"

/** 자동 모드가 쓰는 기기 전원 상태. */
data class PowerState(
    val charging: Boolean,
    /** 배터리 잔량(%). 알 수 없으면 -1. */
    val capacityPercent: Int,
    /** 발열이 보통 이상이거나 절전 모드면 true. */
    val hotOrSaving: Boolean,
)

/**
 * 선호와 전원 상태로 실제 감도 모드를 고른다.
 *
 * 순수 함수라 센서 없이 테스트로 검증한다. 자동 모드 규칙:
 * - 발열·절전 → 배터리 절약
 * - 충전 중이 아니고 잔량 20% 이하 → 배터리 절약
 * - 그 외 → 최대 감지
 */
fun chooseSensitivity(preference: SensitivityPreference, power: PowerState): SensitivityMode = when (preference) {
    SensitivityPreference.MAX -> SensitivityMode.MAX
    SensitivityPreference.SAVER -> SensitivityMode.STANDARD
    SensitivityPreference.AUTO -> when {
        power.hotOrSaving -> SensitivityMode.STANDARD
        power.capacityPercent in 0..20 && !power.charging -> SensitivityMode.STANDARD
        else -> SensitivityMode.MAX
    }
}