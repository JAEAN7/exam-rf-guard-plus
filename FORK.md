# 외부 포크 변경 안내 (1.1.2)

이 저장소는 [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard)를 포크한 **별도 배포본**입니다. 원본에 반영하기 쉽게, 포크 전용 편집은 코드에서 **`// [FORK 1.1.x]`** 주석으로 표시했습니다. **`[FORK`로 검색하면 전부 찾을 수 있습니다.**

## 원본 대비 주요 변경

- **1.1.0** — 금속·자석을 찾는 독립 화면과 자기장 감지 엔진(CUSUM 포함), 세기 비례 삐 소리, 흑백·자동 다크 UI, Android 10–16.
- **1.1.1** — 휴대폰을 흔들거나 걷기만 해도 "자성 물체 의심"이 뜨던 문제 수정. 가속도 이동 차단 + 정지 확인 + 보정 센서.
- **1.1.2** — 감도를 **자동(권장)·표준(최대)·배터리 절약**으로 개편하고 저장, 와이파이 캐시 6초·BLE 100초, 탐색 제한을 끈 기기 10초. 안내는 **단계별 목록 + 똑똑한 버튼**(꺼져 있으면 휴대폰 정보, 켜져 있으면 개발자 옵션)으로 제공하고, **`ui/ThrottleGuide.kt` 한 파일·플래그 하나**로 분리해 원본이 쉽게 뺄 수 있게 함.

## 원본에 반영 요청 — 두 묶음

원본이 어디까지 가져갈지 고를 수 있게 **성격이 다른 두 묶음**으로 나눴습니다.

### A. 정책 안전 — 그대로 반영 요청

- 자기장 감지 기능 전체(별도 파일): `core/MagneticField.kt`, `core/MagneticAnalyzer.kt`, `scanner/MagneticScanner.kt`, `ui/MagneticScreen.kt`, `ui/MagneticPanel.kt`, `core/Geiger.kt`의 `beep()`
- 감도 3택과 저장: `core/SensitivityChoice.kt`, `AppViewModel.kt`, `ui/GuardScreen.kt`
- **탐색 제한 동작**(안내 없이): `core/ScanThrottle.kt` — 사용자가 이미 제한을 꺼 둔 기기에서 10초 주기. 앱이 설정을 바꾸지 않고 공개 API로 읽기만 한다.
- 흑백·다크 UI, Android 10–16, 단일 APK 빌드

### B. 선택 사항 — 어려우면 제외해도 됨

- **개발자 옵션 안내 UI**: `ui/ThrottleGuide.kt` 한 파일과 `app/build.gradle.kts`의 `THROTTLE_GUIDE` 플래그
- 이 묶음은 "사용자가 직접 개발자 옵션에서 탐색 제한을 끄는 방법"을 알려 주는 화면입니다. 앱이 시스템 설정을 바꾸지 않지만, 심사에서 민감하게 볼 수 있으니 **원본 판단에 맡깁니다.**

**제외하는 방법 (둘 중 하나)**

1. `ui/ThrottleGuide.kt` 파일 삭제 + `ui/GuardScreen.kt`의 `ThrottleGuide(...)`, `ThrottleGuideCard()` 호출 두 줄 삭제
2. 그대로 두고 `app/build.gradle.kts`의 `THROTTLE_GUIDE = false` (안내가 아예 그려지지 않음)

**어느 쪽이든 A의 동작은 그대로 유지됩니다.** 안내가 없어도 사용자가 스스로 제한을 끄면 10초 주기로 동작합니다.

> 검증: `THROTTLE_GUIDE = false`로 두고 `:app:compileDebugKotlin`이 통과하는 것을 확인했습니다. 플래그를 끈 배포본도 정상 빌드됩니다.

## 파일별 편집 지점

| 파일 | 내용 |
| --- | --- |
| `core/ForkInfo.kt` (신규) | 포크 이름·버전·변경 요약. 안내 화면이 그대로 읽는다. |
| `core/SensitivityChoice.kt` (신규) | 감도 선호 enum, 자동 규칙 `chooseSensitivity`, `AUTO_RULES` |
| `core/ScanThrottle.kt` (신규) | 정책 안전 코어: `wifiScanPeriodMs`, 안내 문구 상수 `HOWTO` |
| `ui/ThrottleGuide.kt` (신규, B 묶음) | 탐색 제한 안내 UI와 `BuildConfig.THROTTLE_GUIDE` 분기. 개발자 옵션 바로 열기 버튼 |
| `core/Evidence.kt` | 캐시 훑기 6초(표준(최대))·30초(절약), BLE 재등록 100초, `ScanHealth.wifiThrottled`. `[FORK]` 표시 |
| `AppViewModel.kt` | 감도 선호 상태·저장, `readPowerState`, 20초 자동 재평가, `wifiThrottled` 추적·즉시 재시작 |
| `ui/GuardScreen.kt` | `RfSensitivityPicker`, `rfModeLabel`, 안내 화면의 포크 안내 카드 |
| `MainActivity.kt` | 머리말 부제에 포크 이름·버전 |
| `app/build.gradle.kts` | `versionCode 22`, `versionName "1.1.2-fork"`, `THROTTLE_GUIDE` 플래그 |

## 반영하는 방법

1. `[FORK`로 검색해 편집 지점을 확인합니다.
2. 자기장 감지 기능은 **별도 파일**이라 그대로 복사합니다. 공용 파일(`AppViewModel`, `Evidence.kt`, `GuardScreen.kt`, `MainActivity.kt`)은 `[FORK]` 블록 단위로 떼어 검토합니다.
3. B 묶음(안내 UI)은 위 두 방법 중 하나로 제외합니다.
4. 검증: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 36). **120개 테스트**, Lint 오류 0건.

## 배포 계획과 입장

- **지금은 GitHub Releases로만 배포합니다.** Zapstore나 F-Droid는 지금 당장 하려는 것이 아니라, **필요해지면 검토**하는 선택지입니다.
- 원본이 **Google Play에만 집중하기로** 하면, 그때는 이 변경을 개인 포크로 분리해 따로 배포하겠습니다. 이 분리는 **불만이나 문제 제기 때문이 아닙니다.** 이 기능을 **더 좋게 만들고 싶어서**입니다. 원본이나 관리자에게 서운한 점은 없습니다.
- 오히려 **함께 하고 싶습니다.** 정책 때문에 어렵다면 아쉽지만, 앞으로도 계속 협력하고 싶습니다. A 묶음(정책 안전)만이라도 반영되기를 바랍니다.
- 반영이 어렵더라도 **간접적으로라도 돕고 싶습니다.** 이슈로 제보하거나, 실기기 테스트 결과·로그·화면을 공유하거나, 문서·번역·정리 작업을 함께 하는 일이면 언제든 하겠습니다.
- **원본 프로젝트와의 협력, 간접적인 합류, 소통을 환영합니다.** 코드를 반영하는 방식이 아니어도, 의견을 나누고 함께 맞춰 가는 일이면 좋겠습니다. 연락은 이 저장소의 이슈로 주세요.
- 원본이 **플레이에만 집중해 이 안내를 넣지 못하더라도 계속 협력하고 소통하고 싶습니다.** 정책 때문에 선택이 달라도 관계를 끊으려는 것이 아닙니다.
- 포크와 원본이 **나뉘게 되면, 원본 쪽에서 이 포크를 함께 소개해 주면 좋겠습니다.** 예를 들어 README나 릴리스 노트에 "탐색 제한 안내가 필요한 사용자는 이 포크를 참고하세요" 정도의 한 줄이면 충분합니다. 물론 이 소개 여부는 원본이 결정합니다.

**이유**: 탐색 제한 끄기는 Android가 개발자 옵션으로 사용자에게 열어 둔 배터리·성능 설정이고, 앱은 공개 API(`WifiManager.isScanThrottleEnabled()`)로 그 상태를 읽어 주기만 조정합니다. 앱이 설정을 바꾸거나 숨은 API를 쓰지 않습니다. 그럼에도 심사에서 민감하게 볼 수 있어 **안내 UI는 분리**해 두었습니다.