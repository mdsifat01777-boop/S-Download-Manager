package com.flowfetch.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Background and Surface Palette
val BackgroundDark = Color(0xFF0A0D14)
val SurfaceDark = Color(0xFF141721)
val SurfaceVariantDark = Color(0xFF1D2230)
val SurfaceTintedDark = Color(0xFF262C3E)
val SurfaceCard = Color(0xFF181B26)

// Accent and Brand Colors
val ElectricIndigo = Color(0xFF6366F1)
val BrightViolet = Color(0xFF8B5CF6)
val NeonCyan = Color(0xFF06B6D4)
val NeonPink = Color(0xFFEC4899)

// Feedback & Status Colors
val StatusSuccess = Color(0xFF10B981)
val StatusWarning = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
val StatusPaused = Color(0xFF64748B)

// Text Colors
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF64748B)

// Gradients
val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(ElectricIndigo, BrightViolet, NeonCyan)
)

val CardBorderGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x406366F1),
        Color(0x208B5CF6),
        Color(0x1006B6D4)
    )
)

val ButtonGradient = Brush.horizontalGradient(
    colors = listOf(ElectricIndigo, BrightViolet)
)

val AccentGlowGradient = Brush.radialGradient(
    colors = listOf(ElectricIndigo.copy(alpha = 0.25f), Color.Transparent)
)

