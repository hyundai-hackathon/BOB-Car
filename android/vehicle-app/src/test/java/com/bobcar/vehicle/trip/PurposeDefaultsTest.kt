package com.bobcar.vehicle.trip

import com.bobcar.vehicle.signal.NaviPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurposeDefaultsTest {

    private val pangyo = NaviPlace("판교 테크노밸리", latitude = 37.4013, longitude = 127.1086)
    private val coex = NaviPlace("코엑스", poiId = "1001", latitude = 37.5116, longitude = 127.0594)
    private val customers = listOf(pangyo, coex)
    private val home = NaviPlace("인천국제공항 제1여객터미널", poiId = "2001", latitude = 37.449, longitude = 126.451)
    private val office = NaviPlace("42dot", latitude = 37.485, longitude = 127.036)
    private val commute = listOf(home, office)

    private fun resolve(destination: NaviPlace?, last: Purpose? = null) =
        PurposeDefaults.resolve(destination, customers, commute, last)

    @Test
    fun customerDestinationIsBusiness() {
        assertEquals(DefaultPurpose(Purpose.BUSINESS, PurposeSource.CUSTOMER), resolve(NaviPlace("코엑스"), Purpose.PERSONAL))
    }

    @Test
    fun homeOrOfficeBookmarkIsCommute() {
        assertEquals(DefaultPurpose(Purpose.COMMUTE, PurposeSource.COMMUTE_BOOKMARK), resolve(NaviPlace("x", poiId = "2001")))
        assertEquals(DefaultPurpose(Purpose.COMMUTE, PurposeSource.COMMUTE_BOOKMARK), resolve(NaviPlace("42dot")))
    }

    @Test
    fun customerWinsOverBookmark() {
        val both = PurposeDefaults.resolve(NaviPlace("코엑스"), customers, listOf(NaviPlace("코엑스")), null)
        assertEquals(Purpose.BUSINESS, both.purpose)
    }

    @Test
    fun unknownOrNoDestinationKeepsThePreviousPurpose() {
        assertEquals(DefaultPurpose(Purpose.COMMUTE, PurposeSource.LAST_PURPOSE), resolve(NaviPlace("동네 마트"), Purpose.COMMUTE))
        assertEquals(DefaultPurpose(Purpose.PERSONAL, PurposeSource.LAST_PURPOSE), resolve(null, Purpose.PERSONAL))
    }

    @Test
    fun firstTripWithNothingToGoOnIsBusiness() {
        assertEquals(DefaultPurpose(Purpose.BUSINESS, PurposeSource.FALLBACK), resolve(null))
        assertEquals(DefaultPurpose(Purpose.BUSINESS, PurposeSource.FALLBACK), resolve(NaviPlace("동네 마트")))
    }

    @Test
    fun samePlaceByPoiIdCoordinatesOrName() {
        assertTrue(PurposeDefaults.samePlace(NaviPlace("다른 이름", poiId = "1001"), coex))
        // About 50 m away, different name
        assertTrue(PurposeDefaults.samePlace(NaviPlace("판교역 근처", latitude = 37.4017, longitude = 127.1089), pangyo))
        // Spaces and case do not matter
        assertTrue(PurposeDefaults.samePlace(NaviPlace("판교테크노밸리"), pangyo))
        assertTrue(PurposeDefaults.samePlace(NaviPlace("42DOT"), office))
    }

    @Test
    fun differentPlacesDoNotMatch() {
        // About 200 m away
        assertFalse(PurposeDefaults.samePlace(NaviPlace("판교역", latitude = 37.4031, longitude = 127.1086), pangyo))
        assertFalse(PurposeDefaults.samePlace(NaviPlace("코엑스몰", poiId = "9999"), coex))
    }

    @Test
    fun distanceNeedsBothCoordinates() {
        assertEquals(null, PurposeDefaults.distanceMeters(NaviPlace("a"), pangyo))
        val d = PurposeDefaults.distanceMeters(pangyo, NaviPlace("b", latitude = 37.4022, longitude = 127.1086))!!
        assertTrue("about 100 m, got $d", d in 95.0..105.0)
    }
}
