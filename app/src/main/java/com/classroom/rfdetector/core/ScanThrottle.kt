package com.classroom.rfdetector.core

/**
 * [FORK 1.1.2] 와이파이 탐색 제한 관련 **정책 안전 코어**.
 *
 * 이 파일은 "제한이 꺼진 기기에서는 더 자주 확인한다"는 **동작만** 담는다. 사용자에게 끄는 방법을
 * 알려 주는 화면은 `ui/ThrottleGuide.kt`에 따로 있어, 원본 프로젝트가 플레이 정책만 따르려면
 * 그 파일 하나와 `BuildConfig.THROTTLE_GUIDE`만 끄면 된다. 이 파일은 그대로 두어도 된다.
 */
object ScanThrottle {
    /**
     * 개발자 옵션에서 제한을 끄는 단계. 화면은 번호를 붙여 보여 주고, 문서는 [HOWTO] 한 줄을 쓴다.
     * 기기마다 메뉴 이름이 달라 검색창을 쓰는 방법을 함께 안내한다.
     */
    val HOWTO_STEPS = listOf(
        "아래 ‘개발자 옵션 열기’ 버튼을 누릅니다. 아직 개발자 옵션이 꺼져 있으면 ‘휴대폰 정보’ 화면이 열립니다.",
        "휴대폰 정보 화면에서 ‘빌드 번호’를 7번 탭합니다. 개발자 옵션이 켜졌다는 안내가 뜹니다.",
        "이 앱으로 돌아와 같은 버튼을 다시 누르면 ‘개발자 옵션’이 바로 열립니다.",
        "개발자 옵션 위쪽 검색창에 ‘Wi-Fi’를 입력해 ‘Wi-Fi 스캔 제한 사용’(기기에 따라 ‘Wi-Fi 검색 제한’)을 찾습니다.",
        "그 항목을 꺼짐으로 바꾸고, 앱으로 돌아와 감시를 다시 시작하면 와이파이를 10초 간격으로 확인합니다.",
    )

    /** 문서·한 줄 요약용. 화면은 [HOWTO_STEPS]를 번호와 함께 보여 준다. */
    val HOWTO: String = HOWTO_STEPS.joinToString(" ")
}

/**
 * 와이파이 탐색 요청 주기.
 *
 * Android는 기본적으로 2분에 4회 수준으로 제한한다. 사용자가 개발자 옵션에서 이 제한을 끈 기기
 * (`throttled = false`)에서는 표준(최대)일 때 10초까지 당겨, 받을 수 있는 만큼 받는다.
 * 캐시 읽기는 이 제한과 무관하지만, 이 값은 실제 `startScan` 요청 간격이다.
 *
 * 정책 메모: 앱이 설정을 바꾸지 않는다. 사용자가 직접 바꾼 값을 공개 API로 읽어 주기만 조정한다.
 */
fun wifiScanPeriodMs(mode: SensitivityMode, throttled: Boolean): Long = when {
    mode == SensitivityMode.MAX && !throttled -> 10_000L
    mode == SensitivityMode.MAX -> 22_000L
    !throttled -> 20_000L
    else -> 30_000L
}