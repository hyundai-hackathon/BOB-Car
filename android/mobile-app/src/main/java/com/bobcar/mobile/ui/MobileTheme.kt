package com.bobcar.mobile.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Visual style of the phone screens, from the BOB-Car design file (Figma "Mobile" M1–M4,
 * design/BOB-Car_Screens.html: 412 × 892 dp, 1 px = 1 dp). New screens take colors, type and controls from here.
 */

object MColors {
    val Bg = Color(0xFF161826)
    val Surface = Color(0xFF232532)
    val Text = Color(0xFFE9E9ED)
    /** Hairline borders (text at 16%) */
    val Divider = Color(0x29E9E9ED)
    val Accent = Color(0xFF9184D9)
    val Accent100 = Color(0xFFF5F4FF)
    val Accent300 = Color(0xFFD2CEFD)
    val Accent400 = Color(0xFFB5ABFC)
    val Accent800 = Color(0xFF423A6A)
    val Neutral100 = Color(0xFFF3F5FE)
    val Neutral300 = Color(0xFFCFD3E5)
    val Neutral400 = Color(0xFFB2B6CA)
    val Neutral800 = Color(0xFF3F424D)
}

object MType {
    val H2 = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = MColors.Text)
    val Sub = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = MColors.Neutral400)
    val Section = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.96.sp, color = MColors.Neutral400)
    val Label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = MColors.Neutral400)
    val RowTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = MColors.Text)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = MColors.Text)
    val Small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = MColors.Neutral300)
    val Button = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val Kicker = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp, color = MColors.Accent)
    val CardTime = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium, color = MColors.Text)
    val Stat = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = MColors.Text)
}

object MDimens {
    val Gutter = 24.dp
    val RadiusMd = 8.dp
    val RadiusLg = 14.dp
}

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MColors.Accent,
            background = MColors.Bg,
            surface = MColors.Surface,
            onSurface = MColors.Text,
            onBackground = MColors.Text,
            outline = MColors.Divider,
        ),
        content = content,
    )
}

/** Screen background: surface fading into the page color by 40% (170°). */
fun Modifier.screenBackground(): Modifier = background(Brush.verticalGradient(0f to MColors.Surface, 0.4f to MColors.Bg))

@Composable
fun PhIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

/** Screen title block: small line above, 30 sp title. */
@Composable
fun ScreenHeader(sub: String?, title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 20.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sub != null) Text(sub, style = MType.Sub)
            Text(title, style = MType.H2)
        }
        trailing?.invoke()
    }
}

enum class ButtonKind { Primary, Secondary, Ghost }

/** .btn: transparent, 1 dp border (accent for primary, divider for secondary), 8 dp corners. */
@Composable
fun MButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
    textStyle: TextStyle = MType.Button,
    @DrawableRes icon: Int? = null,
) {
    val shape = RoundedCornerShape(MDimens.RadiusMd)
    val content = when (kind) {
        ButtonKind.Primary, ButtonKind.Ghost -> MColors.Accent
        ButtonKind.Secondary -> MColors.Text
    }
    val border = when (kind) {
        ButtonKind.Primary -> MColors.Accent
        ButtonKind.Secondary -> MColors.Divider
        ButtonKind.Ghost -> Color.Transparent
    }
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = if (kind == ButtonKind.Ghost) 12.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) PhIcon(icon, content, size = 20.dp)
        Text(label, style = textStyle, color = content, textAlign = TextAlign.Center)
    }
}

/** Full-width main action at the bottom of a card or screen ("저장", "다음"). */
@Composable
fun BlockButton(label: String, onClick: () -> Unit, enabled: Boolean = true, minHeight: Dp = 52.dp, kind: ButtonKind = ButtonKind.Primary) =
    MButton(label, onClick, Modifier.fillMaxWidth(), kind, enabled, minHeight, MType.Button.copy(fontSize = 16.sp))

enum class TagKind { Accent, Neutral, Outline }

@Composable
fun Tag(text: String, kind: TagKind) {
    val shape = RoundedCornerShape(6.dp)
    val (bg, fg) = when (kind) {
        TagKind.Accent -> MColors.Accent800 to MColors.Accent100
        TagKind.Neutral -> MColors.Neutral800 to MColors.Neutral100
        TagKind.Outline -> Color.Transparent to MColors.Accent
    }
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .then(if (kind == TagKind.Outline) Modifier.border(1.dp, MColors.Accent, shape) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.22.sp, color = fg)
    }
}

/** 3-way segmented control (.seg): one bordered strip, the chosen option outlined in accent. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(MDimens.RadiusMd)
    Row(modifier.fillMaxWidth().clip(shape).border(1.dp, MColors.Divider, shape)) {
        options.forEach { (value, label) ->
            val chosen = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(if (chosen) Modifier.border(1.dp, MColors.Accent, shape) else Modifier)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 15.sp, color = if (chosen) MColors.Accent else MColors.Text)
            }
        }
    }
}

/** .card: surface, 14 dp corners, 18 dp padding, 14 dp gaps. */
@Composable
fun MCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MDimens.RadiusLg)).background(MColors.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** Label (12 sp) above a value or a control. */
@Composable
fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MType.Label)
        content()
    }
}

/** Label above a 22 sp value (M3 stats, M4 grid). */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MType.Label)
        Text(value, style = MType.Stat, maxLines = 1)
    }
}

/** Two equal columns. */
@Composable
fun TwoColumns(gap: Dp = 12.dp, content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
