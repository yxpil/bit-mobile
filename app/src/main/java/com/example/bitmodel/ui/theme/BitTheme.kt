// yxpil · BIT Mobile
// BIT 黑白胶囊设计主题（与桌面端 src/styles.css 设计令牌同源）：
// 亮色底 neutral-100 / 强调 #171717；暗色底纯黑 / 强调 #F5F5F5；
// 胶囊圆角 999px、柔圆角 16/24dp、柔光阴影；无透明组件。
package com.example.bitmodel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 中性灰阶（与 Tailwind neutral 对齐）
val N50 = Color(0xFFFAFAFA)
val N100 = Color(0xFFF5F5F5)
val N200 = Color(0xFFE5E5E5)
val N400 = Color(0xFFA3A3A3)
val N500 = Color(0xFF737373)
val N700 = Color(0xFF404040)
val N800 = Color(0xFF262626)
val N900 = Color(0xFF171717)

private val LightColors = lightColorScheme(
    background = N100,
    onBackground = N900,
    surface = Color.White,
    onSurface = N900,
    surfaceVariant = N100,
    onSurfaceVariant = N500,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerLow = N50,
    primary = N900,
    onPrimary = Color.White,
    primaryContainer = N900,
    onPrimaryContainer = Color.White,
    secondary = N700,
    onSecondary = Color.White,
    outline = N200,
    outlineVariant = N200,
    error = Color(0xFFB91C1C),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    background = Color.Black,
    onBackground = N100,
    surface = N900,
    onSurface = N100,
    surfaceVariant = N900,
    onSurfaceVariant = N400,
    surfaceContainer = N900,
    surfaceContainerHigh = N800,
    surfaceContainerLow = Color(0xFF0A0A0A),
    primary = N100,
    onPrimary = N900,
    primaryContainer = N100,
    onPrimaryContainer = N900,
    secondary = N400,
    onSecondary = N900,
    outline = N800,
    outlineVariant = N800,
    error = Color(0xFFF87171),
    onError = N900,
)

private val BitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BitTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

/** mode: light / dark / auto（BitStore.themeMode） */
@Composable
fun BitTheme(mode: String = "auto", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = BitShapes,
        typography = BitTypography,
        content = content,
    )
}
