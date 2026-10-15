package com.lmq.miccontrol

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 界面配色。
 *
 * 为什么不能只用默认配色：默认配色是亮色一套走到底，系统切到深色模式时
 * 界面依然是白底——夜里打开会很刺眼，和系统其他应用也不一致。
 *
 * 这里的做法：
 * 1. 系统支持"取壁纸配色"时（较新系统）就用系统的，跟手机整体风格统一；
 * 2. 否则用自带的一套深浅配色，保证在任何系统上都不会是刺眼的白底。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4A5DA8),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6078),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C4FF),
    onPrimary = Color(0xFF1B2C63),
    secondary = Color(0xFFC4C6DD),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
)

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用深色。默认跟随系统设置。
 */
@Composable
fun MicControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        // 较新的系统能从壁纸取色，优先用它，视觉上更统一
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
