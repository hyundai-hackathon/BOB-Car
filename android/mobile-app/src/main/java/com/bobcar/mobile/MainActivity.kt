package com.bobcar.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bobcar.mobile.data.Check
import com.bobcar.mobile.data.PhoneTrip
import com.bobcar.mobile.ui.CheckScreen
import com.bobcar.mobile.ui.MColors
import com.bobcar.mobile.ui.MDimens
import com.bobcar.mobile.ui.PhIcon
import com.bobcar.mobile.ui.ProblemLine
import com.bobcar.mobile.ui.RecordsScreen
import com.bobcar.mobile.ui.TripDetailScreen
import com.bobcar.mobile.ui.TripText
import com.bobcar.mobile.ui.VehicleScreen
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bobcar.mobile.ui.ConsentScreen
import com.bobcar.mobile.ui.MType
import com.bobcar.mobile.ui.MobileTheme
import com.bobcar.mobile.ui.PickScreen
import com.bobcar.mobile.ui.StartScreen
import com.bobcar.mobile.ui.screenBackground

class MainActivity : ComponentActivity() {

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark screens: light status and navigation bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MobileTheme {
                val state by model.state.collectAsState()
                App(state, model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

@Composable
private fun App(state: UiState, model: AppModel) {
    when (state.stage) {
        Stage.LOADING, Stage.ERROR -> StartScreen(state.problem.takeIf { state.stage == Stage.ERROR }, model::start)
        Stage.PICK -> PickScreen(state.drivers, state.busy, state.problem, model::pick)
        Stage.CONSENT -> ConsentScreen(state.me!!, state.busy, state.problem, model::agree, model::choose)
        Stage.MAIN -> MainScreens(state, model)
    }
}

/** The three tabs (기록 · 확인 · 차량) with the bottom bar, and M3 over them when a trip is open. */
@Composable
private fun MainScreens(state: UiState, model: AppModel) {
    val text = remember { TripText() }
    val me = state.me ?: return
    val open = state.openTrip
    BackHandler(enabled = open != null || state.tab != Tab.RECORDS) {
        if (open != null) model.close() else model.selectTab(Tab.RECORDS)
    }
    val openTrip: (PhoneTrip) -> Unit = { trip ->
        // An unrecorded drive nobody has taken is settled on its 확인 card (whose drive, then purpose)
        if (trip.check == Check.UNRECORDED) model.selectTab(Tab.CHECK) else model.open(trip.tripDocId)
    }
    Column(Modifier.fillMaxSize().screenBackground().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when {
                open != null -> TripDetailScreen(
                    trip = open,
                    me = me,
                    drivers = state.drivers,
                    busy = state.busy,
                    backLabel = if (state.tab == Tab.CHECK) "확인" else "운행 기록",
                    text = text,
                    onBack = model::close,
                    onSave = { change -> model.save(open.tripDocId, change, closeAfter = true) },
                    onNeedDrivers = model::loadDrivers,
                )
                state.tab == Tab.RECORDS -> RecordsScreen(state.trips, state.vehicle, text, { model.selectTab(Tab.CHECK) }, openTrip, model::refresh)
                state.tab == Tab.CHECK -> CheckScreen(
                    trips = state.toCheck,
                    me = me,
                    drivers = state.drivers,
                    busy = state.busy,
                    text = text,
                    onSave = { trip, change -> model.save(trip.tripDocId, change) },
                    onEdit = { model.open(it.tripDocId) },
                    onNeedDrivers = model::loadDrivers,
                )
                else -> VehicleScreen(state.vehicle, text, model::refresh)
            }
        }
        ProblemLine(state.problem)
        Notice(state.notice, model::noticeShown)
        if (open == null) BottomBar(state.tab, state.toCheck.size, model::selectTab)
        else Spacer(Modifier.navigationBarsPadding())
    }
}

/** A short note after saving; fades by itself. */
@Composable
private fun Notice(message: String?, onShown: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(2_500)
        onShown()
    }
    Row(
        Modifier.fillMaxWidth().padding(start = MDimens.Gutter, end = MDimens.Gutter, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(R.drawable.ic_ph_check_circle, MColors.Accent, size = 18.dp)
        Text(message, style = MType.Small)
    }
}

/** 기록 · 확인 · 차량: surface bar, 24 dp icons, 12 sp labels, the chosen one in accent 400. */
@Composable
private fun BottomBar(selected: Tab, toCheck: Int, onSelect: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MColors.Surface).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
    ) {
        listOf(
            Triple(Tab.RECORDS, R.drawable.ic_ph_list_bullets, "기록"),
            Triple(Tab.CHECK, R.drawable.ic_ph_check_square, "확인"),
            Triple(Tab.VEHICLE, R.drawable.ic_ph_car_profile, "차량"),
        ).forEach { (tab, icon, label) ->
            val color = if (tab == selected) MColors.Accent400 else MColors.Neutral400
            Column(
                // No clip here: the badge sits partly above the icon
                Modifier.weight(1f).heightIn(min = 52.dp).clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                Box {
                    PhIcon(icon, color, size = 24.dp)
                    if (tab == Tab.CHECK && toCheck > 0) CountBadge(toCheck, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp))
                }
                Text(label, fontSize = 12.sp, color = color)
            }
        }
    }
}

/** Number on the 확인 icon: accent dot, page-color digits ("9+" above 9). */
@Composable
private fun CountBadge(count: Int, modifier: Modifier) {
    Box(
        modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(RoundedCornerShape(9.dp)).background(MColors.Accent).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else count.toString(), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = MColors.Bg)
    }
}
