package com.classroom.rfdetector.core

/**
 * 광고 데이터에서 읽은 종류 단서. 기기가 스스로 알린 값이라 후보일 뿐 인증이 아니다.
 * 선언 순서가 대표 표시의 우선순위다(구체적인 것 먼저).
 */
enum class SignalHint(val label: String) {
    GLASSES("스마트 안경"),
    EARPHONES("무선 이어폰·헤드셋"),
    APPLE_AUDIO("Apple·Beats 이어폰"),
    WATCH("스마트워치"),
    HEARING_AID("보청기"),
    PHONE("휴대폰"),
    APPLE_ACTIVE("켜져 있는 Apple 기기 · 아이폰·아이패드·워치 등"),
    COMPUTER("노트북·컴퓨터"),
    WINDOWS("Windows 기기"),
    FAST_PAIR("블루투스 액세서리 · 이어폰 등"),
    TAG("위치 추적 태그"),
    CAMERA("카메라"),
    APPLE_FIND_MY("나의 찾기 신호 · 에어태그나 꺼 둔 Apple 기기"),
    BEACON("고정 비콘"),
    HOTSPOT("휴대폰 핫스팟 후보"),
    WIFI_DIRECT("Wi-Fi Direct 기기 · 휴대폰·프린터 등"),
}

val Set<SignalHint>.primary: SignalHint? get() = minByOrNull { it.ordinal }

private const val APPLE = 0x004C
private const val MICROSOFT = 0x0006
private const val FAST_PAIR_UUID = 0xFE2C

/** BLE 광고 원본(AD 구조 나열)에서 Appearance, 서비스 UUID, 제조사 데이터를 읽는다. 잘린 구조는 무시한다. */
fun bleHints(advertisement: ByteArray?): Set<SignalHint> {
    if (advertisement == null) return emptySet()
    val hints = sortedSetOf<SignalHint>()
    var index = 0
    while (index < advertisement.size) {
        val length = advertisement[index].toInt() and 0xFF
        if (length == 0 || index + length >= advertisement.size) break
        val type = advertisement[index + 1].toInt() and 0xFF
        val data = advertisement.copyOfRange(index + 2, index + 1 + length)
        fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        when (type) {
            0x19 -> if (data.size >= 2) when (u16(0) shr 6) {
                0x01 -> hints += SignalHint.PHONE
                0x02 -> hints += SignalHint.COMPUTER
                0x03 -> hints += SignalHint.WATCH
                0x07 -> hints += SignalHint.GLASSES
                0x08 -> hints += SignalHint.TAG
                0x25 -> hints += SignalHint.EARPHONES
                0x29 -> hints += SignalHint.HEARING_AID
            }
            0x02, 0x03 -> if ((0 until data.size - 1 step 2).any { u16(it) == FAST_PAIR_UUID }) hints += SignalHint.FAST_PAIR
            0x16 -> if (data.size >= 2 && u16(0) == FAST_PAIR_UUID) hints += SignalHint.FAST_PAIR
            0xFF -> if (data.size >= 3) when (u16(0)) {
                APPLE -> hints += appleHints(data.copyOfRange(2, data.size))
                MICROSOFT -> if (data[2].toInt() == 0x01) hints += SignalHint.WINDOWS
            }
        }
        index += length + 1
    }
    return hints
}

/** Apple 제조사 데이터는 [종류][길이][내용]이 이어진다. */
private fun appleHints(payload: ByteArray): Set<SignalHint> {
    val hints = mutableSetOf<SignalHint>()
    var index = 0
    while (index + 1 < payload.size) {
        when (payload[index].toInt() and 0xFF) {
            0x02 -> hints += SignalHint.BEACON
            0x07 -> hints += SignalHint.APPLE_AUDIO
            0x05, 0x0C, 0x0E, 0x0F, 0x10 -> hints += SignalHint.APPLE_ACTIVE
            0x12 -> hints += SignalHint.APPLE_FIND_MY
        }
        index += 2 + (payload[index + 1].toInt() and 0xFF)
    }
    return hints
}

private val hotspotName = Regex("iPhone|iPad|Galaxy|AndroidAP|Pixel[ _]|Redmi|Xiaomi|POCO|HUAWEI|OnePlus|hotspot|핫스팟|테더링", RegexOption.IGNORE_CASE)

/** 휴대폰 기본 핫스팟 이름과 Wi-Fi Direct 이름 규칙. 이름은 바꿀 수 있으므로 없다고 핫스팟이 아닌 것은 아니다. */
fun wifiHints(ssid: String?): Set<SignalHint> = when {
    ssid.isNullOrBlank() -> emptySet()
    ssid.startsWith("DIRECT-") -> setOf(SignalHint.WIFI_DIRECT)
    hotspotName.containsMatchIn(ssid) -> setOf(SignalHint.HOTSPOT)
    else -> emptySet()
}

/**
 * 클래식 블루투스 기기가 스스로 알린 Class of Device에서 종류를 읽는다.
 *
 * 값은 24비트다: 0~1비트 형식, 2~7비트 소분류, 8~12비트 대분류, 13~23비트 서비스.
 * 기기는 오디오·영상 같은 **서비스 비트까지 함께 알리므로**, 받은 값을 그대로 비교하면
 * 거의 맞지 않는다(예: 헤드폰이 0x200418). 서비스 비트를 걷어내고 대분류·소분류만 본다.
 *
 * 소분류 표(코드 값 = 소분류 << 2):
 * - 0x04 헤드셋, 0x08 핸즈프리, 0x10 마이크, 0x18 헤드폰, 0x1C 휴대용 오디오 → 귀에 쓰는 오디오
 * - 0x20 카오디오, 0x24 셋톱박스, 0x28 HiFi, 0x2C VCR → 오디오지만 귀에 쓰는 것이 아니다
 * - 0x30 비디오 카메라, 0x34 캠코더 → 카메라
 *
 * 건강 기기(대분류 0x09)는 보청기가 아니라 혈압계·체온계 같은 기기이므로 단정하지 않는다.
 */
fun classicHints(majorClass: Int?, minorClass: Int?): Set<SignalHint> {
    val major = (majorClass ?: 0) and 0x1F00
    val code = (minorClass ?: 0) and 0x1FFC
    return when (major) {
        0x0100 -> setOf(SignalHint.COMPUTER)
        0x0200 -> setOf(SignalHint.PHONE)
        0x0400 -> when (code) {
            // 귀에 쓰는 오디오. 스피커·카오디오·셋톱·VCR은 이어폰이 아니므로 뺐다.
            0x0404, 0x0408, 0x0410, 0x0418, 0x041C -> setOf(SignalHint.EARPHONES)
            // 비디오 카메라(0x30)·캠코더(0x34). 예전에는 이어폰으로 잘못 분류했다.
            0x0430, 0x0434 -> setOf(SignalHint.CAMERA)
            else -> setOf(SignalHint.FAST_PAIR)
        }
        // 이미지 대분류(0x06xx). 카메라만 카메라로 부르고, 스캐너·프린터는 단정하지 않는다.
        0x0600 -> if (code == 0x0600 || code == 0x0608) setOf(SignalHint.CAMERA) else emptySet()
        // 웨어러블: 손목시계와 안경만 구분한다. 페이저·재킷·헬멧을 시계로 부르지 않는다.
        0x0700 -> when (code) {
            0x0704 -> setOf(SignalHint.WATCH)
            0x0714 -> setOf(SignalHint.GLASSES)
            else -> emptySet()
        }
        else -> emptySet()
    }
}
