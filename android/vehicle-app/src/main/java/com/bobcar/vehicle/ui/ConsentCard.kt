package com.bobcar.vehicle.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bobcar.vehicle.checkin.ConsentNotice

/**
 * Data collection consent, asked once per driver at their first check-in (design-review "수집 동의와 보안").
 * No Figma frame yet: built from the V3 layout and [BobTheme]. Only shown in P ([com.bobcar.vehicle.checkin.showsConsentCard]).
 * Until the driver agrees, their records stay on this vehicle and are not sent to the server.
 */
@Composable
fun ConsentCard(driverName: String, onAgree: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .bobBackground()
            .padding(start = BobDimens.PageMargin, end = BobDimens.PageMargin, top = 112.dp),
    ) {
        NoticeChip()
        Spacer(Modifier.height(26.dp))
        Text("운행 기록 수집에 동의해 주세요", style = BobType.Title, maxLines = 1)
        Spacer(Modifier.height(16.dp))
        Text("$driverName 님은 이 차를 처음 이용하세요", style = BobType.Subtitle, maxLines = 1)
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            NoticeBox("수집 항목", ConsentNotice.items, note = null, modifier = Modifier.weight(1f))
            NoticeBox("이용 목적", ConsentNotice.purposes, note = ConsentNotice.PERSONAL_NOTE, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BobPrimaryButton("동의", onAgree, icon = Icons.Filled.Check, minWidth = 308.dp)
            BobSecondaryButton("나중에", onLater, minWidth = 284.dp)
        }
        Spacer(Modifier.height(30.dp))
        Text(ConsentNotice.LATER_NOTE, style = BobType.Body)
    }
}

@Composable
private fun NoticeChip() {
    Row(
        modifier = Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(BobDimens.ChipRadius))
            .background(BobColors.ChipFill)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = BobColors.ChipText, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("처음 이용 안내", style = BobType.Label, color = BobColors.ChipText)
    }
}

@Composable
private fun NoticeBox(title: String, lines: List<String>, note: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(BobDimens.CardRadius)
    Column(
        modifier = modifier
            .heightIn(min = 400.dp)
            .clip(shape)
            .background(BobColors.Card)
            .border(BorderStroke(1.dp, BobColors.Outline), shape)
            .padding(horizontal = 48.dp, vertical = 40.dp),
    ) {
        Text(title, style = BobType.Label)
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { Text("·  $it", style = BobType.ListName) }
        }
        if (note != null) {
            Spacer(Modifier.height(36.dp))
            Text(note, style = BobType.Body, color = BobColors.TextSubtle)
        }
    }
}

@Preview(widthDp = 1680, heightDp = 1210)
@Composable
private fun ConsentCardPreview() {
    BobTheme {
        ConsentCard("김민준", onAgree = {}, onLater = {})
    }
}
