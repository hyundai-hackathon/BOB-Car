package com.bobcar.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bobcar.mobile.R
import com.bobcar.mobile.data.Driver
import com.bobcar.mobile.data.Me
import com.bobcar.mobile.data.Problem
import com.bobcar.shared.consent.ConsentNotice

/*
 * First run (no Figma frame: designed in the M1–M4 style). The phone shows one person's records, so the
 * driver picks themself once, then agrees to collection (the same notice as the car's card). A driver who
 * already agreed in the car skips the second step.
 */

/** "본인을 선택해 주세요": the staff list, one tap selects, [다음] links this phone to that person. */
@Composable
fun PickScreen(drivers: List<Driver>, busy: Boolean, problem: Problem?, onPick: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding().navigationBarsPadding()) {
        ScreenHeader(sub = "BOB-Car 시작하기", title = "본인을 선택해 주세요")
        Text(
            "선택한 사람의 운행 기록만 이 휴대폰에 보여요",
            style = MType.Small,
            modifier = Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 12.dp),
        )
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = MDimens.Gutter)) {
            items(drivers, key = { it.id }) { driver ->
                val chosen = driver.id == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(MDimens.RadiusMd))
                        .clickable { selected = driver.id }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Initial(driver.name, chosen)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(driver.name, style = MType.RowTitle, color = if (chosen) MColors.Accent400 else MColors.Text)
                        driver.department?.let { Text(it, style = MType.Sub) }
                    }
                    if (chosen) PhIcon(R.drawable.ic_ph_check_circle, MColors.Accent, size = 24.dp)
                }
            }
        }
        ProblemLine(problem)
        Box(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 16.dp, bottom = 28.dp)) {
            BlockButton("다음", onClick = { selected?.let(onPick) }, enabled = selected != null && !busy)
        }
    }
}

@Composable
private fun Initial(name: String, chosen: Boolean) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (chosen) MColors.Accent800 else MColors.Neutral800),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1), style = MType.Button, color = if (chosen) MColors.Accent100 else MColors.Neutral100)
    }
}

/** Collection consent on the phone: items and purposes (shared notice text), [동의] or pick someone else. */
@Composable
fun ConsentScreen(me: Me, busy: Boolean, problem: Problem?, onAgree: () -> Unit, onChooseAgain: () -> Unit) {
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ScreenHeader(sub = "${me.name} 님 · 처음 이용 안내", title = "운행 기록 수집에\n동의해 주세요")
            Column(
                Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NoticeCard("수집 항목", ConsentNotice.items, note = null)
                NoticeCard("이용 목적", ConsentNotice.purposes, note = ConsentNotice.PERSONAL_NOTE.replace("\n", " "))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    PhIcon(R.drawable.ic_ph_info, MColors.Neutral400, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
                    Text("동의 전에는 차에서 기록한 운행이 서버로 올라오지 않아 여기에서 볼 수 없어요. 차에서 이미 동의했다면 이 단계는 나오지 않아요.", style = MType.Sub)
                }
            }
        }
        ProblemLine(problem)
        Column(Modifier.padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("동의", onAgree, Modifier.fillMaxWidth(), enabled = !busy, minHeight = 52.dp, icon = R.drawable.ic_ph_check)
            MButton("다른 사람으로 다시 선택", onChooseAgain, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = !busy)
        }
    }
}

@Composable
private fun NoticeCard(title: String, lines: List<String>, note: String?) {
    MCard {
        Text(title, style = MType.Kicker)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(R.drawable.ic_ph_check, MColors.Accent, size = 16.dp)
                    Text(line, style = MType.Body)
                }
            }
        }
        if (note != null) Text(note, style = MType.Sub)
    }
}

/** Start-up: waiting for the server, or why it could not be reached, with [다시 시도]. */
@Composable
fun StartScreen(problem: Problem?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().screenBackground().statusBarsPadding().padding(MDimens.Gutter),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (problem == null) {
            CircularProgressIndicator(color = MColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text("서버에 연결하는 중", style = MType.Sub)
        } else {
            PhIcon(R.drawable.ic_ph_cloud_slash, MColors.Neutral400, size = 40.dp)
            Spacer(Modifier.height(16.dp))
            Text(problemText(problem), style = MType.Body, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            MButton("다시 시도", onRetry, icon = R.drawable.ic_ph_arrow_clockwise)
        }
    }
}

/** One line under a screen when the last call failed. */
@Composable
fun ProblemLine(problem: Problem?) {
    if (problem == null) return
    Row(
        Modifier.fillMaxWidth().padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(R.drawable.ic_ph_warning_circle, MColors.Accent300, size = 18.dp)
        Text(problemText(problem), style = MType.Sub, color = MColors.Accent300)
    }
}

fun problemText(problem: Problem): String = when (problem) {
    Problem.OFFLINE -> "서버에 연결할 수 없어요. 네트워크를 확인해 주세요"
    Problem.NOT_CONFIGURED -> "이 빌드에는 서버가 설정되어 있지 않아요"
    Problem.NOT_LINKED -> "본인 선택이 필요해요"
    Problem.NO_CONSENT -> "수집 동의가 필요해요"
    Problem.REFUSED -> "이 운행은 지금 바꿀 수 없어요"
    Problem.SERVER -> "서버 응답을 읽지 못했어요. 잠시 뒤 다시 시도해 주세요"
}
