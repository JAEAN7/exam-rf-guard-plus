package kr.reformparty.koriyoscan.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kr.reformparty.koriyoscan.core.DistanceClass

/**
 * 흑백 팔레트.
 *
 * 강조색을 두지 않는다. 위계는 색이 아니라 **명도와 선**으로 만든다: 바탕과 카드의 밝기 차,
 * 1dp 헤어라인, 굵은 글씨. 색은 "위험" 하나만 남겨 빨강을 쓰고, 나머지 상태는 흑·백·회색으로 구분한다.
 * 밝은 화면과 어두운 화면을 각각 정의하고, 시스템 설정에 따라 자동으로 고른다.
 */
private class Palette(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHigh: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryDim: Color,
    val danger: Color,
    val dangerDim: Color,
    val dangerText: Color,
    val warning: Color,
    val warnDim: Color,
    val ok: Color,
    val okDim: Color,
    val textPrimary: Color,
    val textDim: Color,
    val textMuted: Color,
)

private val LightPalette = Palette(
    bg = Color(0xFFF5F5F6),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF1F1F2),
    surfaceHigh = Color(0xFFE7E7E9),
    line = Color(0xFFE2E2E5),
    primary = Color(0xFF111113),
    onPrimary = Color(0xFFFFFFFF),
    primaryDim = Color(0xFFECECED),
    danger = Color(0xFFB3261E),
    dangerDim = Color(0xFFFBEDEC),
    dangerText = Color(0xFF8C1D18),
    warning = Color(0xFF111113),
    warnDim = Color(0xFFF1F1F2),
    ok = Color(0xFF111113),
    okDim = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0B0B0C),
    textDim = Color(0xFF55575C),
    textMuted = Color(0xFF6E7075),
)

private val DarkPalette = Palette(
    bg = Color(0xFF0A0A0B),
    surface = Color(0xFF141416),
    surfaceAlt = Color(0xFF1C1C1F),
    surfaceHigh = Color(0xFF26262A),
    // 어두운 화면에서 카드 경계가 묻히지 않도록 밝은 화면보다 선을 조금 더 밝게 둔다.
    line = Color(0xFF34343A),
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF0A0A0B),
    primaryDim = Color(0xFF232326),
    danger = Color(0xFFFF6B6B),
    dangerDim = Color(0xFF2A1616),
    dangerText = Color(0xFFFFB4B4),
    warning = Color(0xFFF4F4F5),
    warnDim = Color(0xFF1C1C1F),
    ok = Color(0xFFF4F4F5),
    okDim = Color(0xFF141416),
    textPrimary = Color(0xFFF4F4F5),
    textDim = Color(0xFFA1A1A6),
    textMuted = Color(0xFF85858C),
)

/**
 * 화면 코드가 쓰는 색.
 *
 * Canvas 그리기 람다는 컴포저블이 아니라서 CompositionLocal을 읽을 수 없다. 그래서 색을 전역 객체에 두고,
 * [AppRfTheme]이 시스템 설정에 맞는 팔레트로 갈아 끼운다. 팔레트는 스냅샷 상태라, 바뀌면 이 값을 읽은
 * 컴포저블과 Canvas가 스스로 다시 그려진다. 화면 트리를 통째로 다시 만들지 않아 입력·화면 상태가 유지된다.
 */
object RfColors {
    // 스냅샷 상태로 둔다. 테마가 바뀌면 이 값을 읽은 컴포저블과 Canvas가 다시 그려진다.
    private var p by mutableStateOf(LightPalette)

    val bg: Color get() = p.bg
    val surface: Color get() = p.surface
    val surfaceAlt: Color get() = p.surfaceAlt
    val surfaceHigh: Color get() = p.surfaceHigh
    val line: Color get() = p.line

    val primary: Color get() = p.primary
    val onPrimary: Color get() = p.onPrimary
    val primaryDim: Color get() = p.primaryDim

    val danger: Color get() = p.danger
    val dangerDim: Color get() = p.dangerDim
    val dangerText: Color get() = p.dangerText

    val warning: Color get() = p.warning
    val warnDim: Color get() = p.warnDim

    val ok: Color get() = p.ok
    val okDim: Color get() = p.okDim

    val textPrimary: Color get() = p.textPrimary
    val textDim: Color get() = p.textDim
    val textMuted: Color get() = p.textMuted

    internal fun use(dark: Boolean) { p = if (dark) DarkPalette else LightPalette }
}

/** 여백 눈금. 화면마다 다른 숫자를 쓰지 않도록 여기 값만 쓴다. */
object RfSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

/** 모서리 눈금. 각진 느낌을 살리려고 크게 굴리지 않는다. */
object RfRadius {
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
}

/**
 * 거리 등급 -> 강조 색.
 * 아주 가까울 때만 빨강을 쓰고, 나머지는 명도로 구분한다.
 */
fun classAccent(cls: DistanceClass): Color = when (cls) {
    DistanceClass.VERY_CLOSE -> RfColors.danger
    DistanceClass.CLOSE -> RfColors.textPrimary
    DistanceClass.NEAR -> RfColors.textDim
    DistanceClass.FAR -> RfColors.textMuted
    DistanceClass.UNKNOWN -> RfColors.textMuted
}

@Composable
fun AppRfTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    RfColors.use(dark)

    // Android 15부터는 상태바·내비바 색 지정이 무시된다. 창은 enableEdgeToEdge로 그리고,
    // 여기서는 아이콘 밝기만 화면 테마에 맞춘다(Android 10에서도 같은 경로).
    val view = LocalView.current
    SideEffect {
        // 일부 기기·테스트 환경에는 창 제어가 없을 수 있다. 테마 적용 실패로 앱이 죽지 않게 감싼다.
        runCatching {
            val window = view.context.findActivity()?.window ?: return@runCatching
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    // 테마가 바뀌면 RfColors를 읽은 화면과 Canvas가 스스로 다시 그려진다.
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = koreanTypography,
        content = content,
    )
}

private val LightScheme = lightColorScheme(
    primary = LightPalette.primary,
    onPrimary = LightPalette.onPrimary,
    primaryContainer = LightPalette.primaryDim,
    onPrimaryContainer = LightPalette.textPrimary,
    secondary = LightPalette.primary,
    onSecondary = LightPalette.onPrimary,
    secondaryContainer = LightPalette.primaryDim,
    onSecondaryContainer = LightPalette.textPrimary,
    outline = Color(0xFFD2D2D6),
    outlineVariant = LightPalette.line,
    background = LightPalette.bg,
    onBackground = LightPalette.textPrimary,
    surface = LightPalette.surface,
    onSurface = LightPalette.textPrimary,
    surfaceVariant = LightPalette.surfaceAlt,
    onSurfaceVariant = LightPalette.textDim,
    surfaceContainer = LightPalette.surface,
    surfaceContainerHigh = LightPalette.surfaceAlt,
    error = LightPalette.danger,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.primary,
    onPrimary = DarkPalette.onPrimary,
    primaryContainer = DarkPalette.primaryDim,
    onPrimaryContainer = DarkPalette.textPrimary,
    secondary = DarkPalette.primary,
    onSecondary = DarkPalette.onPrimary,
    secondaryContainer = DarkPalette.primaryDim,
    onSecondaryContainer = DarkPalette.textPrimary,
    outline = DarkPalette.line,
    outlineVariant = DarkPalette.line,
    background = DarkPalette.bg,
    onBackground = DarkPalette.textPrimary,
    surface = DarkPalette.surface,
    onSurface = DarkPalette.textPrimary,
    surfaceVariant = DarkPalette.surfaceAlt,
    onSurfaceVariant = DarkPalette.textDim,
    surfaceContainer = DarkPalette.surface,
    surfaceContainerHigh = DarkPalette.surfaceAlt,
    error = DarkPalette.danger,
    onError = DarkPalette.onPrimary,
)

/** Compose 트리에서 Activity를 찾는다. ContextThemeWrapper로 감싸인 경우도 있다. */
private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 한글이 낱말 중간("기록합니/다")에서 끊기지 않게 어절 단위로 줄을 바꾼다. Android 14 이상에서 적용되고, 그 아래에서는 기존처럼 글자 단위로 끊긴다. */
private fun TextStyle.byPhrase() = copy(lineBreak = LineBreak(LineBreak.Strategy.Simple, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase))

/**
 * 제목은 자간을 살짝 당겨 단단하게, 작은 라벨은 자간을 조금 벌려 읽기 쉽게 한다.
 * bodySmall은 줄 간격을 18sp로 올려 긴 안내문이 답답하지 않게 했다.
 */
private val koreanTypography = Typography().run {
    copy(
        displayLarge = displayLarge.byPhrase(), displayMedium = displayMedium.byPhrase(), displaySmall = displaySmall.byPhrase(),
        headlineLarge = headlineLarge.byPhrase(), headlineMedium = headlineMedium.byPhrase(), headlineSmall = headlineSmall.byPhrase(),
        titleLarge = titleLarge.byPhrase().copy(letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.byPhrase().copy(letterSpacing = (-0.1).sp),
        titleSmall = titleSmall.byPhrase(),
        bodyLarge = bodyLarge.byPhrase(),
        bodyMedium = bodyMedium.byPhrase(),
        bodySmall = bodySmall.byPhrase().copy(lineHeight = 18.sp),
        labelLarge = labelLarge.byPhrase().copy(letterSpacing = 0.2.sp),
        labelMedium = labelMedium.byPhrase().copy(letterSpacing = 0.3.sp),
        labelSmall = labelSmall.byPhrase().copy(letterSpacing = 0.3.sp),
    )
}