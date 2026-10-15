package com.sdyl.patrol.ui.theme

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/* ---------------- 品牌色：工业安全绿 ---------------- */

private val Primary = Color(0xFF1B6C4A)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFA4F2C5)
private val OnPrimaryContainer = Color(0xFF00210F)

private val Secondary = Color(0xFF4E6355)
private val SecondaryContainer = Color(0xFFD0E8D5)
private val OnSecondaryContainer = Color(0xFF0B1F14)

private val Tertiary = Color(0xFF3B6470)
private val TertiaryContainer = Color(0xFFBFE9F8)

private val ErrorRed = Color(0xFFBA1A1A)
private val ErrorContainer = Color(0xFFFFDAD6)

private val LightSurface = Color(0xFFF6FBF4)
private val LightSurfaceContainer = Color(0xFFEAEFE9)
private val LightOnSurface = Color(0xFF171D19)
private val LightOutline = Color(0xFF717971)

private val DarkPrimary = Color(0xFF89D6AA)
private val DarkOnPrimary = Color(0xFF00391F)
private val DarkPrimaryContainer = Color(0xFF00522F)
private val DarkSurface = Color(0xFF0F1511)
private val DarkSurfaceContainer = Color(0xFF1B211C)
private val DarkOnSurface = Color(0xFFDFE4DD)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    tertiaryContainer = TertiaryContainer,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    surface = LightSurface,
    surfaceContainer = LightSurfaceContainer,
    onSurface = LightOnSurface,
    outline = LightOutline,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    secondary = Color(0xFFB4CCBA),
    secondaryContainer = Color(0xFF374B3E),
    tertiary = Color(0xFFA3CDDA),
    surface = DarkSurface,
    surfaceContainer = DarkSurfaceContainer,
    onSurface = DarkOnSurface,
)

/**
 * MD3E 形状：比标准 M3 更大的圆角。
 * 说明：material3 1.4.0 里 `MaterialExpressiveTheme` / `MotionScheme` 仍是 internal，
 * 因此这里用公开 API 复刻同等视觉（加大圆角 + 动态取色 + 弹簧动效），
 * 待 material3 1.5.0 正式版发布后可一行切回 `MaterialExpressiveTheme(motionScheme = MotionScheme.expressive())`。
 */
private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val SdylTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
}

/** MD3E 的空间化动效：低阻尼弹簧，用于展开 / 切换 */
object SdylMotion {
    fun <T> expressive() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> standard() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

@Composable
fun SdylTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SdylTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
