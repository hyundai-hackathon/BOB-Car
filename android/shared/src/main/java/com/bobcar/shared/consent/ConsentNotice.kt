package com.bobcar.shared.consent

/**
 * The data collection notice (design-review "수집 동의와 보안"), the same on the car's consent card and the
 * phone app's first run. The text lives only here. Raise [VERSION] when the items or purposes change, so every driver is asked again.
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
}
