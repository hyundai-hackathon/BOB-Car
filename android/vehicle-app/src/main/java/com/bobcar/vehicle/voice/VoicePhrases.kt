package com.bobcar.vehicle.voice

/**
 * Every sentence the app says, in one place (design-review "음성 안내").
 * Numbers are written the way they should be read ("퍼센트", not "%").
 */
object VoicePhrases {

    /** ① The driver card was answered. */
    fun checkedIn(name: String): String = "${name}님으로 기록합니다."

    /** ② Briefing when the driver boards: battery and whether a drive went unrecorded. null = nothing to say. */
    fun briefing(socPercent: Int?, unrecordedFound: Boolean): String? {
        val parts = buildList {
            socPercent?.let { add("배터리 ${it}퍼센트입니다.") }
            if (unrecordedFound) add("기록되지 않은 운행이 있어요.")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** ③ The trip-end countdown started. */
    const val END_COUNTDOWN = "10초 뒤 운행을 마칩니다."

    /** ④ The driver card closed unanswered: the default was recorded as provisional. */
    fun provisional(name: String): String = "${name}님으로 잠정 기록했어요."

    /** ④ The driver card closed unanswered and there was no default driver. */
    const val PROVISIONAL_NO_DRIVER = "운전자 없이 기록했어요. 운행을 마칠 때 확인해 주세요."
}
