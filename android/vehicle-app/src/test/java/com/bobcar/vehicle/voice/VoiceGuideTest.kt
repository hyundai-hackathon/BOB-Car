package com.bobcar.vehicle.voice

import com.bobcar.vehicle.checkin.Briefing
import com.bobcar.vehicle.driving.CancelReason
import com.bobcar.vehicle.driving.DrivingEvent
import com.bobcar.vehicle.driving.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceGuideTest {

    /** Records what would be said ("say:<label>:<text>") or "silence". */
    private class FakeSpeaker : Speaker {
        val calls = mutableListOf<String>()
        override fun say(text: String, label: String) {
            calls += "say:$label:$text"
        }

        override fun silence() {
            calls += "silence"
        }
    }

    private val speaker = FakeSpeaker()
    private val guide = VoiceGuide(speaker)

    @Test
    fun answeredDriverCardSaysTheName() {
        guide.announce(Announcement.CheckedIn("김민준"))

        assertEquals(listOf("say:checked-in:김민준님으로 기록합니다."), speaker.calls)
    }

    @Test
    fun briefingSaysBatteryAndAnUnrecordedDriveButNotTheOdometer() {
        guide.announce(Announcement.Boarded(Briefing.of(socPercent = 68, odometerKm = 12_368f, lastRecordedOdometerKm = 12_362f)))

        assertEquals(listOf("say:briefing:배터리 68퍼센트입니다. 기록되지 않은 운행이 있어요."), speaker.calls)
    }

    @Test
    fun briefingWithoutAnUnrecordedDriveSaysOnlyTheBattery() {
        guide.announce(Announcement.Boarded(Briefing.of(socPercent = 68, odometerKm = 12_362f, lastRecordedOdometerKm = 12_362f)))
        guide.announce(Announcement.Boarded(Briefing.of(socPercent = 68, odometerKm = 12_362f, lastRecordedOdometerKm = null)))

        assertEquals(
            listOf("say:briefing:배터리 68퍼센트입니다.", "say:briefing:배터리 68퍼센트입니다."),
            speaker.calls,
        )
    }

    @Test
    fun briefingWithNothingKnownSaysNothing() {
        guide.announce(Announcement.Boarded(Briefing.of(socPercent = null, odometerKm = null, lastRecordedOdometerKm = null)))

        assertEquals(emptyList<String>(), speaker.calls)
    }

    @Test
    fun endCountdownIsAnnouncedAndSilencedWhenCancelled() {
        guide.onDrivingEvent(DrivingEvent.EndCountdownStarted(at = 1_000, deadline = 11_000))
        guide.onDrivingEvent(DrivingEvent.EndCountdownCancelled(at = 3_000, reason = CancelReason.CONTINUE_TAPPED))

        assertEquals(listOf("say:end-countdown:10초 뒤 운행을 마칩니다.", "silence"), speaker.calls)
    }

    @Test
    fun otherDrivingEventsSayNothing() {
        guide.onDrivingEvent(DrivingEvent.Boarded(at = 1_000))
        guide.onDrivingEvent(DrivingEvent.TripStarted(at = 2_000, mergedWithPrevious = false))
        guide.onDrivingEvent(DrivingEvent.TripEnded(at = 3_000, reason = EndReason.COUNTDOWN))

        assertEquals(emptyList<String>(), speaker.calls)
    }

    @Test
    fun provisionalDriverIsNamedOrMissing() {
        guide.announce(Announcement.Provisional("이서연"))
        guide.announce(Announcement.Provisional(null))

        assertEquals(
            listOf(
                "say:provisional:이서연님으로 잠정 기록했어요.",
                "say:provisional-no-driver:운전자 없이 기록했어요. 운행을 마칠 때 확인해 주세요.",
            ),
            speaker.calls,
        )
    }

    @Test
    fun phrasesAreFixed() {
        assertEquals("10초 뒤 운행을 마칩니다.", VoicePhrases.END_COUNTDOWN)
        assertEquals("배터리 5퍼센트입니다.", VoicePhrases.briefing(5, unrecordedFound = false))
        assertEquals("기록되지 않은 운행이 있어요.", VoicePhrases.briefing(null, unrecordedFound = true))
        assertNull(VoicePhrases.briefing(null, unrecordedFound = false))
    }
}
