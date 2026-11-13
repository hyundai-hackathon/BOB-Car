package com.bobcar.vehicle.gateway

import com.bobcar.vehicle.signal.NaviPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaviPlaceTest {

    @Test
    fun emptyEntriesAreDropped() {
        // HOME / OFFICE bookmarks come with an empty name until the driver sets them (day1-check 14-5)
        assertNull(naviPlace("", "", 0.0, 0.0))
        assertNull(naviPlace("  ", "123", 37.4, 127.1))
        assertNull(naviPlace(null, null, 0.0, 0.0))
    }

    @Test
    fun placeKeepsNamePoiAndCoordinates() {
        assertEquals(
            NaviPlace("판교 테크노밸리", "411311000001", 37.4, 127.1),
            naviPlace(" 판교 테크노밸리 ", "411311000001", 37.4, 127.1),
        )
    }

    @Test
    fun zeroCoordinatesAndBlankPoiMeanNotSet() {
        assertEquals(NaviPlace("코엑스", null, null, null), naviPlace("코엑스", " ", 0.0, 0.0))
    }
}
