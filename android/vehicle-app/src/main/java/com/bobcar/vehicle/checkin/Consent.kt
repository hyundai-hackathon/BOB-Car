package com.bobcar.vehicle.checkin

import com.bobcar.vehicle.data.DriverConsentEntity
import com.bobcar.vehicle.driving.DrivingState
import com.bobcar.vehicle.signal.Gear

/**
 * The data collection notice shown on the consent card (design-review "수집 동의와 보안").
 * The text lives only here. Raise [VERSION] when the items or purposes change, so every driver is asked again.
 */
object ConsentNotice {
    const val VERSION = 1

    val items = listOf(
        "운행 시각",
        "누적 거리 · 배터리 %",
        "기어 상태",
        "업무 운행의 목적지 · 종료 위치",
        "운전자",
    )

    val purposes = listOf(
        "운행기록부 작성",
        "법인카드 지출 대사",
    )

    const val PERSONAL_NOTE = "개인 운행은 목적지·위치를\n저장하지 않아요"
    const val LATER_NOTE = "나중에를 누르면 기록은 이 차에만 보관되고, 다음 탑승 때 다시 물어봐요"
}

/** Whether this answer lets the driver's records go to the server: agreed to the current notice. */
fun DriverConsentEntity?.isAgreed(): Boolean =
    this != null && status == DriverConsentEntity.STATUS_AGREED && noticeVersion >= ConsentNotice.VERSION

/**
 * The consent card is up when the confirmed driver has not agreed yet and has not answered during this
 * stay in the seat, and the car is stopped in P before or after a trip (CLAUDE.md rule 3).
 * - Only a confirmed driver is asked: a provisional default may not be the person in the seat.
 * - During the end countdown the trip-end card comes first, so it waits until the trip has ended.
 * - Leaving P hides it; it comes back at the next stop in P, or at the next check-in.
 * - [consents] null = not loaded yet: nothing is shown rather than asking someone who already agreed.
 */
fun showsConsentCard(
    state: DrivingState,
    checkin: CheckinState?,
    gear: Gear?,
    consents: List<DriverConsentEntity>?,
): Boolean {
    if (gear != Gear.PARK) return false
    if (state !is DrivingState.CheckingIn && state !is DrivingState.Parked) return false
    val driverId = checkin?.confirmedDriverId ?: return false
    if (driverId in checkin.consentAnswered) return false
    if (consents == null) return false
    return !consents.firstOrNull { it.driverId == driverId }.isAgreed()
}
