package kr.reformparty.koriyoscan.core

/**
 * 이 배포본이 원본과 다르다는 사실과 버전을 한곳에 모아 둔다.
 *
 * 원본에 반영하기 쉽게 하려고 포크 전용 편집은 코드에서 `[FORK]` 주석으로 표시한다.
 * 어떤 파일을 어떻게 바꿨는지는 `FORK.md`에 모아 두었다. `[FORK]`로 검색하면 전부 찾을 수 있다.
 */
object ForkInfo {
    /** 화면에 쓰는 이름. 원본과 구분하기 위해 붙인다. */
    const val LABEL = "외부 포크"

    /** 표시용 버전. `app/build.gradle.kts`의 versionName과 맞춘다. */
    const val VERSION = "1.1.2"

    /** 원본 저장소. 원본에 반영을 요청할 때 쓴다. */
    const val UPSTREAM = "reform-dev-team/exam-rf-guard"

    /** 원본 대비 주요 변경. 안내 화면에 그대로 보여 준다. */
    val NOTES = listOf(
        "금속·자석을 찾는 독립 화면과 자기장 감지 엔진",
        "휴대폰을 흔들거나 걷기만 해도 자성 물체로 뜨던 문제 수정",
        "감도 자동(권장)·표준(최대)·배터리 절약, 선택 저장",
        "개발자 옵션에서 탐색 제한을 끈 기기는 와이파이를 10초마다 확인",
        "흑백 UI와 시스템 다크 모드, Android 10~16 대응",
    )
}