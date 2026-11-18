# 빌드·구조·검증 안내

> 이 문서는 [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard)를 포크해 **1.1.0 업데이트**를 적용한 별도 배포본의 개발 문서입니다. 원본 프로젝트의 공식 문서가 아닙니다.

앱이 하는 일과 한계는 [README](../README.md)에 있습니다. 이 문서는 코드를 확인하거나 고치려는 사람을 위한 설명입니다.

## 빌드 환경

- JDK 17 이상(검증에는 JDK 21 사용), Android SDK Platform 36과 Build Tools
- 지원 범위: `minSdk 29`(Android 10) ~ `targetSdk 36`(Android 16). `compileSdk`도 36이다. AGP 8.7.3은 API 36을 아직 검증 목록에 두지 않아 `gradle.properties`의 `android.suppressUnsupportedCompileSdk=36`으로 경고만 끈다. AGP를 8.9.1 이상으로 올리면 그 줄은 지운다.
- 컴파일·대상 SDK 35, 최소 SDK 29(Android 10)
- 도구 버전은 [버전 카탈로그](../gradle/libs.versions.toml)와 [wrapper 설정](../gradle/wrapper/gradle-wrapper.properties)에 고정돼 있습니다.

프로젝트 루트의 `local.properties`에 SDK 경로를 지정합니다. Android Studio가 만들어 주기도 합니다. 이 파일은 Git에서 제외합니다.

```properties
sdk.dir=C:/Android/Sdk
```

```shell
./gradlew testDebugUnitTest assembleDebug lintDebug
```

| 결과 | 경로 |
| --- | --- |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 테스트 보고서 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| Lint 보고서 | `app/build/reports/lint-results-debug.html` |

디버그 빌드는 준비 화면에서 비행기 모드 단계를 건너뜁니다. 비행기 모드를 켜면 무선 ADB가 끊기기 때문입니다. 측정 화면에는 "비행기 모드가 꺼져 있습니다" 경고가 그대로 뜨며, 디버그 빌드는 실제 시험 감독에 쓰면 안 됩니다. 배포판은 비행기 모드를 켜야 측정을 시작할 수 있습니다.

직접 빌드한 APK는 배포판과 서명이 달라 배포판 위에 덮어 깔리지 않습니다. 정식 서명 빌드는 [RELEASE.ko.md](RELEASE.ko.md)를 참고하세요.

## 구성

| 구성 | 역할 |
| --- | --- |
| `MainActivity` | 화면 전환(감독관 화면·금속 탐지·위치 찾기·안내), 권한 요청, 화면 켜 두기, 금속 탐지 센서의 포그라운드 일시 중지·재개 |
| `AppViewModel` | 측정·감시 상태, 빈 교실 기록 저장, 화면 밖 일시 중지와 재개, 비행기 모드·무음 확인 |
| `scanner/BleScanner` | BLE 알림 신호 연속 수신. 실제 패킷 시각을 검사하고, 시스템이 탐색을 낮추기 전에 다시 겁니다. |
| `scanner/WifiScanner` | 30초 간격 탐색 요청, 완료 알림에서 새 결과만 받기 |
| `core/DeviceTracker`, `ObservationPeriods` | 무선 종류+주소별 누적 관측, 수신 구간, 최근/대기/기록 상태 |
| `core/SignalGroups` | 주소 대신 이름·종류로 묶기, 빈 교실 때보다 늘어난 묶음 판정 |
| `core/SignalSnapshot`, `SnapshotStore` | 빈 교실 기록과 그 JSON 저장 |
| `core/SignalHints`, `SignalDetails` | 알림 신호에서 종류 단서와 부가 정보 읽기 |
| `core/DeviceIdentity`, `AudioNameCatalog`, `WearableNameCatalog` | 회사 코드·주소 등록부·이름 규칙으로 제조사와 제품군 후보 찾기 |
| `core/RssiFilter`, `DistanceEstimate` | 수신 세기 평활, 가정에 기반한 거리 어림 |
| `core/Geiger`, `SilentMode` | 위치 찾기 진동, 측정·감시 중 방해 금지 |
| `sim/RadioSimulator` | 가상 전파. 화면에서는 켤 수 없고 자동 테스트만 씁니다. |
| `ui/GuardScreen` | 감독관 화면, 위치 찾기, 안내 |
| `ui/MagneticScreen`, `ui/MagneticPanel` | 금속·자석 탐지 독립 화면과 표시 패널. 전파 감시와 별개로 켜고 끈다. |

## 수신과 저장의 규칙

- `radioKey`는 무선 종류와 대문자로 맞춘 주소의 조합입니다. BLE와 와이파이는 주소 문자열이 같아도 별개입니다.
- 콜백에서 바로 추적기에 기록하고 화면용 집계는 100ms마다 갱신합니다. 화면 갱신 속도가 탐지율을 뜻하지는 않습니다.
- BLE는 탐색 시작 전·미래·0 이하의 시각을 가진 패킷을 버립니다. 늦게 도착한 패킷은 실제 관측 시각을 유지합니다.
- 필터 없는 BLE 탐색은 시작 몇 분 뒤 시스템이 결과를 거의 주지 않는 모드로 바꿉니다. 4분마다, 또는 30초 넘게 조용하면 다시 겁니다.
- 와이파이는 완료 알림이 온 탐색에서 이번 측정 시작 이후의 결과만 받습니다. 같은 주소의 같거나 이전 시각은 다시 세지 않습니다.
- 와이파이 캐시 훑기는 신선도 상한(`wifiCacheFreshMs`, 60초)과 되살리기 상한(`wifiCacheWindowMs`, 표준 90초·최대 180초)을 나눠 씁니다. 신선도 상한을 넘긴 캐시 결과는 받지 않습니다. 받으면 최근 창을 이미 벗어나 태어나자마자 "사라짐"이 되고, 목록에는 뜨지 않으면서 내부 상태만 어지럽히기 때문입니다.
- BLE 15초, 와이파이 90초를 넘는 공백을 수신 구간의 경계로 봅니다.
- 최대 감지에서는 표본이 지워진 뒤에도 기기별 1분 버킷으로 "몇 분에 걸쳐 몇 번" 잡혔는지 기억합니다(`recallHorizonMs`, 15분). 기억으로만 유지되는 기기는 목록에 "잠시 끊김"으로 남지만, 상단 개수·경고·진동·감지 기록에서는 빠집니다.
- 클래식 탐색은 한 번에 표준 12초, 최대 감지 20초를 붙잡습니다. 표준 inquiry가 10.24초라 12초 아래로는 내리지 않습니다.
- 클래식 기기의 종류는 기기가 알린 Class of Device에서 읽습니다. 값에 서비스 비트(오디오·영상 등)가 함께 실리므로 `and 0x1FFC`로 걷어낸 뒤 대분류·소분류를 봅니다. 소분류 코드는 `소분류 << 2`이고, 비디오 카메라 `0x30`·캠코더 `0x34`가 카메라입니다. 한 탐색 회차 안에서 같은 주소가 다시 보고되면 한 번만 셉니다.
- 클래식 주소는 회전하지 않으므로 묶음 지문에 주소 전체를 씁니다. 제조사 앞 3옥텟만 쓰면 같은 회사 기기가 한 묶음으로 뭉쳐, 하나를 "확인함"으로 뺄 때 다른 기기까지 사라집니다.
- 와이파이 탐색 제한(`WifiManager.isScanThrottleEnabled`, Android 11 이상)이 꺼진 기기에서는 탐색 주기를 더 당깁니다. 제한이 걸린 기기에서는 잦은 요청이 거절되어 오류만 쌓이므로 기본 주기를 유지합니다.
- 화면이 꺼지거나 앱이 뒤로 가면(`ON_STOP`) 수신을 멈추고, 돌아오면(`ON_START`) 같은 감시를 이어 갑니다. 비어 있던 횟수와 시간은 `ScanSession`에 남습니다. 백그라운드 수신 서비스는 없습니다.
- 묶음 판정 규칙은 [README의 설명](../README.md#같은-기기를-새-전파로-세지-않는-방법)과 [설계 문서](design/same-device-grouping.md)를 참고하세요.

## 자기장 감지

- `core/MagneticAnalyzer.kt`는 **안드로이드에 의존하지 않는다.** 표본 배열을 넣으면 판정이 나오므로 `MagneticAnalyzerTest`에서 센서 없이 알고리즘을 검증한다. `scanner/MagneticScanner.kt`는 센서 등록과 표본 변환만 맡는다.
- 센서는 `TYPE_MAGNETIC_FIELD`(보정)를 우선한다. 보정 센서는 기기 고정 편향(하드 아이언)을 빼서 **휴대폰을 돌려도 |B|가 거의 일정**하다. 보정되지 않은 센서는 회전할 때 크기가 함께 흔들려 오탐이 났다. 보정 센서가 없으면 보정되지 않은 센서로 내려간다.
- 표본은 `Sensor.minDelay`(기기가 허용하는 최소 주기)로 받는다. 표본이 많을수록 평균 잡음이 줄어 미세 변화를 더 잘 본다. 화면 반영은 금속 탐지 전용 10Hz 주기(`magneticTickJob`)로 제한한다.
- 주 판정은 `|B| - 적응기준값`(회전에 강함)이다. 3축 벡터 변화는 **회전이 느릴 때만** 보조로 쓴다. 지구 자기장에서 휴대폰을 돌리면 크기는 그대로지만 벡터는 최대 2|B|까지 바뀌기 때문이다. 회전은 직전 단위벡터와의 사잇각으로 추정한다.
- **이동 중 판정 보류**: `MagneticScanner`가 가속도 센서(선형 가속도 우선, 없으면 가속도)로 흔들기·걷기를 감지해 `MagneticSample.deviceMoving`에 넣는다. 동적 가속도가 2.5 m/s²를 넘거나 EMA가 0.9를 넘으면 0.5초 동안 이동으로 본다. 분석기는 `moving`이면 기준값 동결·CUSUM 중지에 더해 **판정 자체를 하지 않는다.** 멈춘 뒤 `stillRequiredMs`(0.3–0.35초)가 지나고 창 잡음이 임계의 0.5배 이하일 때만 `over`를 계산한다. 이 정지·안정 조건이 없으면 손으로 흔들기만 해도 "자성 물체 의심"이 떴다(실기기 확인).
- 적응 기준값은 변화가 없고 손이 멈춰 있을 때만 아주 천천히 따라간다. 감지 중에는 멈춰서 진짜 물체를 "주변 환경"으로 흡수하지 않는다. 큰 도약(`spikeLimitMicroTesla`)은 손 움직임으로 보고 기준값 갱신에서 제외한다.
- 6개 알고리즘(크기·벡터·속도·안정도·창 일치도·누적 합)의 점수를 각각 내고, 임계를 넘긴 것이 2개 이상이며 `holdMs` 이상 이어질 때만 `DETECTED`로 올린다. `releaseRatio`로 히스테리시스를 두고, 임계 아래로 내려갔다가 다시 올라오면 반복 횟수를 센다.
- **누적 합(CUSUM)** 은 Page(1954)의 양방향 누적 합 검정이다. `z = delta / threshold`로 정규화하고 `S+ = max(0, S+ + z - cusumDrift)`, `S- = max(0, S- - z - cusumDrift)`를 매 표본 갱신한다. `max(S+, S-) / cusumAlarm`이 1.0을 넘으면 한 표, `cusumSolo` 이상이면 다른 알고리즘이 동의하지 않아도 지속 변화로 본다. 부호가 오가는 손떨림은 두 합이 상쇄되어 쌓이지 않고, 같은 방향으로 계속 벌어지는 작은 변화만 쌓인다. 기준을 잡은 뒤 `cusumWarmupMs` 동안은 센서 예열 흔들림을 피하려고 쌓지 않고, `moving`인 동안에는 0.9씩 지운다.
- 창 링 버퍼는 1024표본이다. 600ms 창을 담아야 하는데 500Hz 기기는 300표본이 필요하다. 256으로 두면 빠른 기기에서 창이 조용히 짧아져 판정이 달라진다.
- 표본 시각이 뒤로 점프하면(수동 시계 변경 등) 누적·유지 시간이 깨지므로 조용히 기준을 다시 잡는다.
- 센서 정확도는 `MagneticScanner.accuracy`가 `onAccuracyChanged`를 받아 저장한다. 화면은 `LOW`·`UNRELIABLE`일 때 8자 보정과 기준 재설정을 안내하고, 콜백을 아직 받지 못한 상태(`accuracyKnown = false`)는 경고하지 않는다.
- 임계는 고정값이 아니라 **기기 잡음에 맞춰 조정**한다(`effectiveThreshold`). 조용하고(`!moving`, `CLEAR`, `deltaAbs < 0.6×임계`) 변화가 없을 때만 창 표준편차를 5초 시간상수로 따라가 `noiseFloor`를 만들고, `max(기본 임계, min(noiseFloor × factor, 기본 임계 × cap))`을 쓴다. 잡음 0.5 μT 기기에서 최대 감지 0.6 μT를 그대로 쓰면 오탐이 나므로, 레이더·소나의 CFAR처럼 잡음에 맞춰 문턱을 올린다. 상한(`cap`)이 있어 조용한 기기의 감도는 그대로다.
- ⚠️ 잡음 바닥 계수는 `dtMs.toDouble() / (dtMs + NOISE_TAU_MS).toDouble()`로 계산한다. `Long / Long`은 0이 되어 **임계가 영원히 기본값에 고정**된다. 실제 빌드에서 이 오류를 확인하고 고쳤다.
- 확정 감지는 `stickyHoldMs` 동안 `deltaAbs ≥ 임계 × releaseRatio`이면 유지한다. 근거가 약해질 때 바로 `CLEAR`로 떨어져 표시가 깜빡이는 것을 막는다. `lastOverMs`를 늘리지 않으므로 기준값 적응은 정상적으로 재개된다.
- 회전 직후에는 `rotationHold`로 벡터 분석을 잠시 쉰다. 마지막 빠른 회전에서 `smoothingTauMs × 3`(약 0.5–0.8초) 동안 축 평활이 새 방향을 따라오는 중이라, 3축 벡터 차이가 실제 물체 변화처럼 커진다. 이 구간을 감지에 쓰면 휴대폰만 돌려도 "순간 변화"가 뜬다. `rotatingThePhoneDoesNotTrigger` 테스트가 이 동작을 지킨다.
- 적응형 기준값은 **누적 검정이 쌓이기 시작하면 즉시 멈춘다**(`cusumUp > 0 || cusumDown > 0`). 0.5 같은 문턱을 두면 기준값이 먼저 흡수해 버려 누적이 자라지 못한다(시뮬레이션으로 확인). 기준값이 천천히 다가오는 물체를 "주변 환경"으로 먹어버리면 CUSUM의 존재 이유가 사라진다. 누적이 0으로 풀리면 기준값 적응도 다시 시작된다.
- 화면 배치는 `MagneticScreen.kt`의 "지금 해야 할 일" 순서를 따른다. 측정 중에는 실시간 패널(상태·막대)이 맨 위, 시작/종료는 하단 고정, 감도·설명은 아래에 둔다. 상태는 `StatePill`(색 점 + 텍스트)로 색만으로 읽지 않아도 되게 했고, 감지 중에는 카드 배경과 테두리를 함께 바꾼다.
- **삐 소리**는 `magneticBeepJob`이 10Hz로 갱신되는 `magneticReading`을 읽어 `TRANSIENT`/`DETECTED`일 때만 `Geiger.beep()`을 부른다. 간격은 `650 - 520×strength`(120–650ms)라 세기가 클수록 빨라지고, 조용할 때는 울리지 않는다. `Geiger`는 `ToneGenerator` 하나를 재사용하고 `shutdown()`에서 `release()`한다. 진동(`pulse`)과 소리(`beep`)는 서로 다른 토글이라 따로 끌 수 있다.
- 참고한 공개 구현: `ianposediooo/Metal-Detector-Android-App`(Kotlin, GPL-3.0)은 `|B|` 절대값(50/70/400/800 μT)으로 판정하고 센서 정확도를 읽어 보정을 안내한다. 절대값은 지구 자기장이 25–65 μT로 지역마다 달라 그대로 쓸 수 없어, 이 앱은 적응형 기준값 대비 변화량을 쓴다. `CR4SH3UR/metal-detector`(Flutter)는 첫 표본을 기준으로 잡고 `|변화|`에 5/20/60 μT 임계를 둔다. CUSUM은 누적 합 관리도 표준 기법을 그대로 따른다.
- 감도 모드별 값은 `MagneticProfile.of(mode)`에 모아 두었다. RF 쪽 `SensitivityProfile`과 별도 파일인 이유는 임계·평활 계수의 단위와 튜닝 근거가 완전히 다르기 때문이다. 금속 탐지 감도는 전파 감도(`sensitivity`)와 별도 상태(`magneticMode`)로 기억하며, `setSensitivity()`는 자기장 분석기에 손대지 않는다.
- 기록(`MagneticEvent`)은 감지 구간(`runStartMs`)을 키로 갱신하고, 최고 변화량이 임계를 넘은 구간만 남긴다. 손떨림으로 생긴 약한 흔들림이 기록을 채우지 않게 하기 위해서다.
- **전파 감시와 완전히 분리된 화면·수명주기**다. `MainActivity`의 네 번째 화면(`MagneticScreen`)이며, `startMagnetic()`/`stopMagnetic()`이 `magneticActive`를 관리한다. `begin()`(전파 측정)과 `stopScan()`은 자기장 센서를 건드리지 않으므로 빈 교실 기록·비행기 모드·방해 금지·무선 상태와 무관하게 켤 수 있고, 감시 중에 함께 켜 둘 수도 있다. `ON_STOP`에서는 `pauseMagnetic()`으로 센서만 떼고, `ON_START`의 `resumeMagnetic()`이 사용자가 켜 둔 상태를 이어 간다. 금속 기록은 전파 감지 기록처럼 앱을 닫으면 사라진다.

빈 교실 기록은 마지막 하나만 앱 전용 저장소에 JSON으로 둡니다. 임시 파일에 쓰고 동기화한 뒤 교체합니다. 파일 형식 번호는 3이며, 이전 배포판이 쓴 파일(1–3)도 읽습니다. 모르는 단서 이름과 빈 항목은 무시합니다. 백업과 기기 간 이전 대상에서 제외합니다.

## 거리 모델

평활한 수신 세기와 아래 모델을 씁니다.

```text
d = 10 ^ ((A - RSSI) / (10 × n))
n = 2.7
A = 1m 기준 수신 세기. 현재는 항상 −59 dBm 가정값
```

BLE 알림 신호의 송신 출력 값은 1m 기준 수신 세기가 아니므로 쓰지 않습니다. 그래서 지금 실제 수신 경로는 모두 가정값으로 계산합니다. 결과는 어림이며 거리 정확도를 보장하지 않습니다. 근거는 [Bluetooth SIG의 RSSI 설명](https://www.bluetooth.com/blog/proximity-and-rssi/)을 참고하세요.

## 검증 범위

자동 테스트는 묶음 판정, 추적, 저장, 식별, 스캐너 콜백, 감독관 화면 흐름(가상 전파)을 다룹니다. 다음은 자동 테스트가 대신하지 못합니다.

- 실제 무선 환경에서의 수신률과 누락률
- 기종·Android 버전별 권한 화면과 시스템의 탐색 제한
- 발열과 배터리 소모

실기기 결과를 알려 줄 때는 기종, Android 버전, 앱 버전, 켠 기기와 그 상태, 잡힌 시각과 안 잡힌 시각을 구분해 적어 주세요. 개인 이름이나 실제 현장을 알 수 있는 자료는 그대로 올리지 마세요.

## 저장소에 올리지 않는 것

`local.properties`, 서명 키, 빌드 출력, 실제 현장에서 받은 기록. 사용자에게 보이는 설명에는 근거 없는 탐지율, AI 판정, 정확한 위치 보장을 넣지 않습니다.

## UI 규칙

- 색은 `RfColors`만 쓴다. 바탕 `bg`, 카드 `surface`, 보조 `surfaceAlt`, 선 `line`. 강조색은 두지 않는다: 위계는 명도와 1dp 선, 글자 굵기로 만든다. 빨강(`danger`)은 감지·문제에만 쓴다. 본문 `textPrimary`, 보조 `textDim`, 작은 라벨 `textMuted`.
- 테마는 `isSystemInDarkTheme()`를 따른다. 밝은/어두운 팔레트가 따로 있고, 상태바·내비게이션 바 아이콘 밝기도 함께 바꾼다. Canvas 그리기 람다는 컴포저블이 아니라 CompositionLocal을 읽을 수 없으므로 `RfColors`는 스냅샷 상태로 둔 전역 팔레트를 가리킨다. 팔레트가 바뀌면 이 값을 읽은 컴포저블과 Canvas만 다시 그려지고, `rememberSaveable` 화면 상태는 유지된다.
- **Canvas 그리기 람다 안에서는 `RfColors`를 직접 읽지 않는다.** 컴포지션에서 `val lineColor = RfColors.primary`처럼 읽어 넘긴다. draw 시점 상태 관찰(Compose 1.5+)에 기대지 않고, 어떤 버전에서도 테마 전환 시 확실히 다시 그린다.
- Android 15의 엣지투엣지 강제에 대응해 `MainActivity`가 `enableEdgeToEdge()`를 호출하고, 내용은 `WindowInsets.safeDrawing`만큼 안쪽에 둔다. 상태바·내비바 **색 지정은 하지 않는다**(Android 15에서 무시됨). 아이콘 밝기만 `WindowInsetsControllerCompat`으로 맞춘다.
- 여백은 `RfSpace`(4/8/12/16/20/28), 모서리는 `RfRadius`(8/12/16/20). 화면마다 다른 숫자를 새로 만들지 않는다.
- 카드는 `surface` + 1dp `line` 테두리. 경고·위험 카드는 `warnDim`/`dangerDim` 바탕에 같은 색 30% 테두리. 목록 행은 `surface`, 안쪽 기록 행은 `surfaceAlt`로 층을 나눈다.
- 화면 머리말은 `MainActivity`가 그리고 제목 + 한 줄 부제를 둔다. 본문과는 1dp 선으로 나눈다.
- 하단 주 동작은 `surface` 바 + 위쪽 1dp 선. 주 버튼은 `primary` 채움(밝은 화면은 검정, 어두운 화면은 흰색, 높이 54dp, 모서리 12dp), 보조 버튼은 외곽선, 종료 버튼은 `surfaceAlt` 채움.
- 라벨은 자간을 조금 벌리고(0.2–0.3sp) 제목은 살짝 당긴다(−0.1sp에서 −0.2sp). `bodySmall` 줄 간격은 18sp.
