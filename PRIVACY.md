# 개인정보 처리방침

**KoriyoSCAN** (패키지 `kr.reformparty.koriyoscan`, 버전 1.1.2)에 적용됩니다. 최종 갱신 2026년 11월 18일.

> 이 문서는 **이 포크 배포본**의 개인정보 처리방침입니다. 원본 프로젝트 [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard)의 문서가 아니며, 패키지 이름과 내용이 원본과 다를 수 있습니다. 법률 자문이 아니므로, 배포 전에는 해당 지역 규정을 확인하세요.

## 요약

- **계정이 없습니다.** 로그인, 회원가입, 프로필, 결제가 없습니다.
- **광고·분석·추적 SDK를 넣지 않았습니다.**
- **인터넷 권한을 선언하지 않습니다.** 앱이 만든 정보를 밖으로 보낼 수단 자체가 없습니다.
- 개발자를 포함해 **누구에게도 정보를 보내지 않습니다.**
- 기기에 남는 것은 **마지막 빈 교실 기록 한 건**뿐이고, 나머지는 **메모리에만** 있습니다.
- 앱을 지우거나 앱 데이터를 지우면 전부 사라집니다.

## 수집하지 않는 것

다음은 **읽지도, 저장하지도, 보내지도 않습니다.**

- 위치 좌표(GPS·네트워크 위치), 이동 경로
- 계정, 이메일, 전화번호, 이름, 생년월일
- 연락처, 통화 기록, 문자
- 사진, 동영상, 음성·마이크, 카메라, 파일
- 설치한 앱 목록, 사용 기록, 화면 캡처
- 광고 식별자(AAID), IMEI, Android ID 같은 기기 식별자
- 사용 통계·오류 보고(원격 전송)

## 기기에서 읽는 정보 (기기 안에서만 처리)

측정·감시 중 다음 값을 **기기 안에서** 읽습니다. 모두 화면 표시와 비교를 위한 것이며, 아래 [저장](#기기에-저장되는-정보)에 적은 항목만 파일로 남습니다.

| 대상 | 읽는 값 | 쓰임 | 파일 저장 |
| --- | --- | --- | --- |
| 와이파이 접속점 | 주소(BSSID), 이름(SSID), 수신 세기(dBm), 주파수, 회사 코드(OUI), 이름 숨김 여부, 탐색 시각 | 빈 교실 기록과 비교, 위치 찾기 | 마지막 빈 교실 기록에 포함 |
| 저전력 블루투스(BLE) | 주소, 이름, 수신 세기, 회사 코드, 광고 지문(서비스 UUID·Appearance·회사 코드·서비스 데이터 앞부분으로 만든 SHA-256 앞 8바이트) | 같은 기기 묶기, 새 전파 판정 | 마지막 빈 교실 기록에 포함 |
| 클래식 블루투스(BR/EDR) | 주소, 이름, 수신 세기, 기기 종류(Class of Device) | BLE 광고를 내지 않는 구형 기기 탐지 | 마지막 빈 교실 기록에 포함 |
| 자기 센서(자기장 센서) | 3축 값(X·Y·Z, μT) | 자석·철·니켈 물체 판별 | **파일 저장 안 함**(메모리만) |

- **위치 좌표는 읽지 않습니다.** 다만 Android가 와이파이·블루투스 탐색 결과를 주려면 위치 권한과 위치 서비스가 켜져 있어야 하므로, 앱은 권한을 받고 **위치 서비스가 켜졌는지 확인만** 합니다.
- 기기 이름에는 개인 이름이 들어 있을 수 있습니다(예: "○○의 AirPods", "○○의 핫스팟"). 그래서 기록을 다룰 때는 주의가 필요합니다.

## 기기에 저장되는 정보

기록 파일 하나와 앱 설정값 하나만 저장합니다.

- 파일: 앱 전용 저장소 `files/signal-snapshots.json` (다른 앱이 읽을 수 없음)
- 형식: JSON `version = 3`, 원자적 쓰기(저장 중 실패해도 이전 파일이 남음)
- 개수: **마지막 빈 교실 기록 1건**. 새로 저장하면 이전 것을 대체합니다.
- 기록에 들어가는 항목
  - `area` — 기록 이름(빈 교실)
  - `capturedAtMs` — 측정 시각
  - `simulation` — 자동 테스트로 만든 기록인지 여부
  - `groupMaxCounts` — 묶음별로 동시에 가장 많이 잡힌 수
  - `signals[]` — 잡힌 기기마다 `address`, `source`(WIFI/BLE/CLASSIC), `name`, `rssi`, `manufacturer`, `presence`, `manufacturerIds[]`, `hints[]`, `fingerprint`
- 백업 제외: `allowBackup=false`, `fullBackupContent=false`, `dataExtractionRules`로 두어 **기기 백업·이전 대상에서 빠집니다.**
- 앱 밖으로 내보내는 기능(공유·내보내기·클라우드)은 없습니다.

감도 선택(자동/최대/절약)을 기억하기 위해 **앱 설정값 하나**(`shared_prefs/koriyo-settings.xml`)를 더 저장합니다. 개인정보가 아닌 앱 설정이며, 앱을 지우면 함께 사라집니다.

**지우는 방법**

1. 앱을 삭제(제거)하거나,
2. Android 설정 → 앱 → KoriyoSCAN → 저장공간 → **데이터 지우기**

## 메모리에만 있는 정보

다음은 파일로 저장하지 않고 앱 화면용으로만 쓰며, **앱을 닫으면 사라집니다.**

- 시험 중 감지 기록: 최대 200건
- 자성 물체(자석) 판정 기록과 추이 그래프: 최근 표본 48개(약 5초)
- "확인함" 목록, 진동 알림 여부, 마지막 감지 시각

## 전송·제3자 제공

- **없습니다.** 앱에 인터넷 권한이 없어 어떤 정보도 기기 밖으로 전송할 수 없습니다.
- 광고·분석·추적·크래시 보고 SDK를 넣지 않았습니다. `AndroidX`·`Jetpack Compose`는 화면을 그리는 라이브러리이며, 데이터를 모으지 않습니다.
- 위치를 기록하지 않으므로 위치 기반 서비스에 제공하지 않습니다.
- 제조사·기기 종류 조회는 **앱에 포함된 정적 등록부**로 하며, 실시간 조회를 하지 않습니다.

## 권한

| 권한 | 쓰는 곳 | 거부하면 |
| --- | --- | --- |
| 근처 기기(블루투스 탐색·연결) | BLE·클래식 블루투스 알림 수신, 블루투스 상태 확인 (Android 12+) | 블루투스 탐지 불가 |
| 블루투스, 블루투스 관리 | 같은 기능 (Android 10–11, 설치 시 허용) | 블루투스 탐지 불가 |
| 정확한 위치, 대략적 위치 | 와이파이·블루투스 탐색 결과를 받기 위해 Android가 요구 | **와이파이 탐색 결과를 받을 수 없음**(위치는 기록하지 않음) |
| 근처 Wi-Fi 기기 | 와이파이 탐색 결과 (Android 13+) | 와이파이 탐색 결과를 받을 수 없음 |
| Wi-Fi 상태 확인·변경 | 와이파이 탐색 요청 | 와이파이 탐지 불가 |
| 방해 금지 접근 | 측정·감시 중 전화·알림·알람 무음 | 무음 전환이 불가해 측정을 시작할 수 없음 |
| 진동 | 위치 찾기 진동, 새 전파 알림, 자성 물체 감지 알림 | 화면 표시만 남고 진동 없음 |
| 자기장 고속 표본(Android 12+) | 자기장 센서를 기기가 허용하는 최고 속도로 받기 | 자동 허용되는 일반 권한(허용 창구 없음). 없으면 기기 기본 속도로 받음 |

**인터넷 권한은 선언하지 않습니다.** 제조사 정보는 앱에 포함된 등록부로 조회합니다.

## 자성 물체(금속·자석) 기능

- 자기 센서는 **권한도, 무선도 필요 없습니다.** 비행기 모드에서도 동작합니다.
- 자석·철·니켈 물체를 찾는 데만 쓰고, 측정값과 추이는 **화면에 보여 주기만** 하며 파일로 저장하지 않습니다.
- 움직이는 동안에는 판정하지 않습니다. 멈춘 뒤 지속되는 정적인 자기 변화만 자성 물체로 봅니다.
- 알루미늄·구리·금 같은 비자성 금속은 원리적으로 잡히지 않습니다.

## 보안

- 기록은 앱 전용 저장소에 두어 다른 앱이 읽을 수 없습니다.
- 네트워크로 나가지 않으므로 전송 중 노출 위험이 없습니다.
- 화면을 저장하거나 다른 앱으로 보내는 기능이 없습니다.

## 계정·어린이

- 계정, 프로필, 로그인, 결제가 없습니다.
- 만 14세 미만 아동을 대상으로 하지 않으며, 아동의 개인정보를 별도로 수집하지 않습니다.

## 포크 안내

- 이 배포본은 원본 [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard)를 포크해 **1.1.0 기능 업데이트와 1.1.1 버그 수정**을 적용한 **별도 배포본**입니다.
- 원본 프로젝트나 원저작자의 공식 배포·보증과 무관하며, **원본의 개인정보 처리방침과 다를 수 있습니다.** 원본을 쓸 때는 원본 저장소의 문서를 확인하세요.
- 패키지 이름이 다릅니다. 이 배포본은 `kr.reformparty.koriyoscan`입니다.

## 원본 프로젝트에 반영 요청

이 배포본의 변경을 원본 [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard)에 **반영해 주시기를 부탁드립니다.**

- 범위: 금속·자석 독립 화면, 자기장 감지 엔진(CUSUM 포함), 흔들기 오탐 수정, 흑백·자동 다크 UI, Android 10–16 대응, 단일 APK 빌드
- 검토 단위: 감지 엔진(`core/MagneticAnalyzer.kt`), 독립 화면(`ui/MagneticScreen.kt`·`ui/MagneticPanel.kt`), UI 테마(`ui/Theme.kt`), 플랫폼 설정(매니페스트·빌드 설정)
- 검증: JDK 17.0.21 + Android SDK 36에서 단위 테스트 **111개 통과**, `assembleDebug` 성공, Lint 오류 0건
- 채택 여부와 시점은 원본 관리자가 결정합니다. 이 저장소의 변경은 원본의 공식 변경이 아닙니다.
- 이 개인정보 처리방침은 **이 배포본에만** 적용됩니다. 원본이 이 변경을 받아들이면 원본의 방침에 맞게 다시 검토해야 합니다.

## 문의

이 저장소의 이슈 트래커에 남겨 주세요. 화면을 첨부할 때는 **기기 이름·주소·장소가 보이지 않게 가려 주세요.**

## 변경

- 이 문서가 바뀌면 이 파일과 [CHANGELOG.md](CHANGELOG.md)에 반영하고, 커밋 기록에 남깁니다.
- 기능이나 권한이 달라져 이 내용이 사실과 달라지면, 같은 릴리스에서 문서를 함께 고칩니다.
- 현재 적용 버전: **1.1.1** (2026-11-18)

---

## English summary

KoriyoSCAN (package `kr.reformparty.koriyoscan`, version 1.1.2) **collects no personal data**. It declares **no internet permission**, has no accounts, ads or analytics, and shares nothing with the developer or third parties.

What it reads on the device: Wi-Fi access points (BSSID, SSID, RSSI, frequency, OUI, hidden-SSID flag), BLE devices (address, name, RSSI, company ID, advertisement fingerprint), Bluetooth Classic (BR/EDR) devices (address, name, RSSI, Class of Device), and the magnetometer (X/Y/Z in µT). **Location coordinates are neither read nor stored**; location permission and services are used only because Android requires them to return Wi-Fi and Bluetooth scan results.

The only items persisted are the **last empty-classroom snapshot** (`files/signal-snapshots.json`, JSON version 3, written atomically, one record only, excluded from device backup) and the **sensitivity choice** (`shared_prefs/koriyo-settings.xml`), which is an app setting, not personal data. Detection records (up to 200) and magnetic history (48 samples) are kept **in memory only** and disappear when the app closes. Deleting the app or clearing its data removes everything.

**Upstream request.** These 1.1.0/1.1.1 changes are offered to the original project [`reform-dev-team/exam-rf-guard`](https://github.com/reform-dev-team/exam-rf-guard) for review and possible merge. Adoption is up to the upstream maintainers; this repository is not an official upstream release, and this privacy policy applies to this fork only.