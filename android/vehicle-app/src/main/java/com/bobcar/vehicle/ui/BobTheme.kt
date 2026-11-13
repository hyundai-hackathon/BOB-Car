package com.bobcar.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Visual style from the BOB-Car Figma file ("BOB-Car 화면 디자인", page "화면 (PNG)").
 * Values were measured from the frames (1680 × 1210 px = the app area on the emulator, 1 px = 1 dp).
 * New screens take their colors, type and buttons from here.
 */

object BobColors {
    val BgTop = Color(0xFF232532)
    val BgBottom = Color(0xFF161826)
    /** Button fill */
    val Control = Color(0xFF1A1C2A)
    /** List card fill */
    val Card = Color(0xFF181A28)
    val Outline = Color(0xFF3A3C48)
    val Accent = Color(0xFF9184D9)
    /** Highlighted numbers (e.g. unrecorded trips) */
    val AccentSoft = Color(0xFFD2CEFD)
    val SelectedFill = Color(0xFF2B2741)
    val SelectedSubtext = Color(0xFFAFABD6)
    val ChipFill = Color(0xFF423A6A)
    val ChipText = Color(0xFFF5F4FF)
    val TextPrimary = Color(0xFFE9E9ED)
    val TextSecondary = Color(0xFFB2B6CA)
    val TextSubtle = Color(0xFFCFD3E5)
    val Icon = Color(0xFF9397AB)
}

object BobType {
    /** Card question, two lines ("김민준 님이 / 운전하시나요?") */
    val Hero = TextStyle(fontSize = 96.sp, lineHeight = 108.sp, color = BobColors.TextPrimary)
    /** Confirmed driver name */
    val HeroName = TextStyle(fontSize = 110.sp, lineHeight = 124.sp, color = BobColors.TextPrimary)
    val Title = TextStyle(fontSize = 80.sp, lineHeight = 92.sp, color = BobColors.TextPrimary)
    val Subtitle = TextStyle(fontSize = 44.sp, lineHeight = 56.sp, color = BobColors.TextSubtle)
    val ListName = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, color = BobColors.TextPrimary)
    val Button = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium)
    val ButtonSmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, color = BobColors.TextSecondary)
    val Label = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, color = BobColors.TextSecondary)
    val Stat = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, color = BobColors.TextPrimary)
}

object BobDimens {
    /** Left / right page margin */
    val PageMargin = 88.dp
    val ControlRadius = 8.dp
    val CardRadius = 10.dp
    val ChipRadius = 6.dp
}

/** App theme: dark Material scheme in the Figma colors (for anything not styled by hand). */
@Composable
fun BobTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BobColors.Accent,
            background = BobColors.BgBottom,
            surface = BobColors.BgBottom,
            onSurface = BobColors.TextPrimary,
            onBackground = BobColors.TextPrimary,
            outline = BobColors.Outline,
        ),
        content = content,
    )
}

/** Page background: dark diagonal gradient. */
fun Modifier.bobBackground(): Modifier = background(
    Brush.linearGradient(
        colors = listOf(BobColors.BgTop, BobColors.BgBottom),
        start = Offset.Zero,
        end = Offset(900f, 1210f),
    ),
)

/** Main action: accent outline and text ("확인"). */
@Composable
fun BobPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    minWidth: Dp = 280.dp,
    minHeight: Dp = 120.dp,
    textStyle: TextStyle = BobType.Button,
) = BobButton(label, onClick, modifier, icon, BobColors.Accent, BobColors.Accent, minWidth, minHeight, textStyle)

/** Other action: grey outline, light text ("다른 사람"). */
@Composable
fun BobSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    minWidth: Dp = 280.dp,
    minHeight: Dp = 120.dp,
    textStyle: TextStyle = BobType.Button,
) = BobButton(label, onClick, modifier, icon, BobColors.Outline, BobColors.TextPrimary, minWidth, minHeight, textStyle)

@Composable
private fun BobButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    border: Color,
    content: Color,
    minWidth: Dp,
    minHeight: Dp,
    textStyle: TextStyle,
) {
    val shape = RoundedCornerShape(BobDimens.ControlRadius)
    Row(
        modifier = modifier
            .widthIn(min = minWidth)
            .heightIn(min = minHeight)
            .clip(shape)
            .background(BobColors.Control)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = textStyle, color = content)
    }
}
