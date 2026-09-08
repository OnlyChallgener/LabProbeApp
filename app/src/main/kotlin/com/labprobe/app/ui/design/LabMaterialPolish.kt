package com.labprobe.app

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Reference-screen-only Android material layer. No typography or layout values live here. */
@Immutable
data class LabMaterialColors(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInset: Color,
    val glassTint: Color,
    val glassFallback: Color,
    val glassBorder: Color,
    val outline: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
)

private val LabMaterialLight = LabMaterialColors(
    backgroundTop = Color(0xFFDFE5EA),
    backgroundBottom = Color(0xFFE9EFF3),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceInset = Color(0xFFF1F5F8),
    glassTint = Color(0xCCFFFFFF),
    glassFallback = Color(0xFFF8FAFC),
    glassBorder = Color(0xB3FFFFFF),
    outline = Color(0x6679848E),
    ink = Color(0xFF18212A),
    inkMuted = Color(0xFF5F6B75),
    accent = Color(0xFF1978AE),
)

@Immutable
data class LabMaterialState(
    val enabled: Boolean,
    val colors: LabMaterialColors,
    val hazeState: HazeState?,
)

private val LocalLabMaterialState = compositionLocalOf {
    LabMaterialState(false, LabMaterialLight, null)
}

object LabMaterialPolish {
    val enabled: Boolean @Composable get() = LocalLabMaterialState.current.enabled
    val colors: LabMaterialColors @Composable get() = LocalLabMaterialState.current.colors
    val hazeState: HazeState? @Composable get() = LocalLabMaterialState.current.hazeState
}

private val materialReferenceRoutes = setOf("home", "devices", "tool_ping", "device_detail")

fun isMaterialReferenceRoute(route: String): Boolean = route in materialReferenceRoutes

@Composable
fun LabMaterialReferenceTheme(
    route: String,
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled = isMaterialReferenceRoute(route)
    if (!enabled) {
        Box(Modifier.fillMaxSize(), content = content)
        return
    }

    val colors = LabMaterialLight
    val hazeState = rememberHazeState()
    val inheritedTypography = MaterialTheme.typography
    val scheme = lightColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7E8F1),
        onPrimaryContainer = colors.ink,
        secondary = colors.accent,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCEAF0),
        onSecondaryContainer = colors.ink,
        tertiary = colors.accent,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE0EAEE),
        onTertiaryContainer = colors.ink,
        background = colors.backgroundBottom,
        surface = colors.surface,
        surfaceTint = Color.Transparent,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surfaceRaised,
        surfaceContainerLow = colors.surfaceInset,
        surfaceVariant = colors.surfaceInset,
        outline = colors.outline,
        onSurface = colors.ink,
        onSurfaceVariant = colors.inkMuted,
        error = Color(0xFFC93F4A),
    )

    MaterialTheme(colorScheme = scheme, typography = inheritedTypography) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalLabMaterialState provides LabMaterialState(true, colors, hazeState),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Source and effects are siblings. Glass never captures itself or
                // another glass surface, which also bounds the captured workload.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.backgroundTop, colors.backgroundBottom),
                            ),
                        )
                        .hazeSource(state = hazeState),
                )
                Box(Modifier.matchParentSize(), content = content)
            }
        }
    }
}

/**
 * Static frosted material: background blur + restrained tint + one rounded shadow.
 * Android 11 and below intentionally receive the same-shaped opaque translucent fallback.
 */
fun Modifier.labFrostedSurface(
    shape: Shape,
    elevation: Dp = 2.dp,
    blurRadius: Dp = 16.dp,
): Modifier = composed {
    val state = LocalLabMaterialState.current
    if (!state.enabled || state.hazeState == null) return@composed this
    val colors = state.colors
    val style = remember(colors, blurRadius) {
        HazeStyle(
            backgroundColor = colors.glassFallback,
            tints = listOf(HazeTint(colors.glassTint)),
            blurRadius = blurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(colors.glassFallback),
        )
    }
    this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = true,
            ambientColor = Color.Black.copy(alpha = .04f),
            spotColor = Color(0x100284C7),
        )
        .hazeEffect(state = state.hazeState, style = style) {
            blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }
        .border(1.dp, colors.glassBorder, shape)
}

fun Modifier.labStaticFrostedSurface(shape: Shape): Modifier = composed {
    val state = LocalLabMaterialState.current
    if (!state.enabled) return@composed this
    this
        .clip(shape)
        .background(state.colors.glassFallback.copy(alpha = .94f))
        .border(0.5.dp, state.colors.glassBorder, shape)
}
