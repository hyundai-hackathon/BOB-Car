package com.bobcar.vehicle.voice

import com.bobcar.vehicle.checkin.Briefing
import com.bobcar.vehicle.checkin.Unrecorded
import com.bobcar.vehicle.driving.DrivingEvent

/** What the trip recording tells the voice guide (drivers are named; the voice says the name). */
sealed interface Announcement {
    /** ① The driver card was answered with this driver. */
    data class CheckedIn(val driverName: String) : Announcement

    /** ② The driver boarded: the same briefing the driver card shows. */
    data class Boarded(val briefing: Briefing) : Announcement

    /** ④ The driver card closed unanswered; [driverName] null = no default driver. */
    data class Provisional(val driverName: String?) : Announcement
}

/**
 * Decides which sentence to say for what (design-review "음성 안내", 4 places) and hands it to [speaker].
 * Guidance only, never a question: it is allowed while driving. A new sentence cuts off the previous one.
 * - ① driver card answered → "○○님으로 기록합니다"
 * - ② boarded → battery % and an unrecorded drive (the odometer stays on screen only)
 * - ③ end countdown started → "10초 뒤 운행을 마칩니다"; cancelled ([계속 운행], gear out of P) → silence
 * - ④ driver card closed unanswered → "○○님으로 잠정 기록했어요" (or no default driver)
 */
class VoiceGuide(private val speaker: Speaker) {

    fun announce(announcement: Announcement) {
        when (announcement) {
            is Announcement.CheckedIn ->
                speaker.say(VoicePhrases.checkedIn(announcement.driverName), LABEL_CHECKED_IN)
            is Announcement.Boarded -> {
                val unrecorded = announcement.briefing.unrecorded is Unrecorded.Found
                VoicePhrases.briefing(announcement.briefing.socPercent, unrecorded)
                    ?.let { speaker.say(it, LABEL_BRIEFING) }
            }
            is Announcement.Provisional -> {
                val name = announcement.driverName
                if (name != null) speaker.say(VoicePhrases.provisional(name), LABEL_PROVISIONAL)
                else speaker.say(VoicePhrases.PROVISIONAL_NO_DRIVER, LABEL_PROVISIONAL_NO_DRIVER)
            }
        }
    }

    /** Register with DrivingMonitor.addEventListener (signal thread). */
    val onDrivingEvent: (DrivingEvent) -> Unit = { event ->
        when (event) {
            is DrivingEvent.EndCountdownStarted -> speaker.say(VoicePhrases.END_COUNTDOWN, LABEL_END_COUNTDOWN)
            is DrivingEvent.EndCountdownCancelled -> speaker.silence()
            else -> Unit
        }
    }

    companion object {
        const val LABEL_CHECKED_IN = "checked-in"
        const val LABEL_BRIEFING = "briefing"
        const val LABEL_END_COUNTDOWN = "end-countdown"
        const val LABEL_PROVISIONAL = "provisional"
        const val LABEL_PROVISIONAL_NO_DRIVER = "provisional-no-driver"
    }
}
