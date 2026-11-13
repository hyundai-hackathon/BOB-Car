package com.bobcar.vehicle.trip

import com.bobcar.vehicle.signal.NaviPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PurposeDefaultsTest {

    private val home = NaviPlace("인천국제공항 제1여객터미널", poiId = "2001", latitude = 37.449, longitude = 126.451)
    private val office = NaviPlace("42dot", poiId = "1001", latitude = 37.485, longitude = 127.036)
    private val pangyo = NaviPlace("판교 테크노밸리", latitude = 37.4013, longitude = 127.1086)

    private fun resolve(destination: NaviPlace?, last: Purpose? = null) =
        PurposeDefaults.resolve(destination, home, office, last)

    @Test
    fun goingHomeIsCommute() {
        val expected = DefaultPurpose(Purpose.COMMUTE, PurposeSource.HOME)
        assertEquals(expected, resolve(NaviPlace("x", poiId = "2001")))
        assertEquals(expected, resolve(home, Purpose.BUSINESS))
        assertEquals(expected, resolve(home, Purpose.PERSONAL))
    }

    @Test
    fun goingToTheOfficeAfterACommuteTripIsCommute() {
        // The car was taken home (the previous trip was the commute home): this is going to work
        assertEquals(DefaultPurpose(Purpose.COMMUTE, PurposeSource.OFFICE_AFTER_COMMUTE), resolve(office, Purpose.COMMUTE))
    }

    @Test
    fun goingToTheOfficeOtherwiseIsBusiness() {
        // Coming back from a customer, or nothing known about the previous trip
        val expected = DefaultPurpose(Purpose.BUSINESS, PurposeSource.OFFICE)
        assertEquals(expected, resolve(office, Purpose.BUSINESS))
        assertEquals(expected, resolve(office, Purpose.PERSONAL))
        assertEquals(expected, resolve(NaviPlace("42DOT")))
    }

    @Test
    fun anyOtherDestinationOrNoneIsBusiness() {
        val expected = DefaultPurpose(Purpose.BUSINESS, PurposeSource.DEFAULT)
        assertEquals(expected, resolve(pangyo))
        assertEquals(expected, resolve(null))
        // The previous purpose is not carried over: PERSONAL is never suggested
        assertEquals(expected, resolve(NaviPlace("동네 마트"), Purpose.PERSONAL))
        assertEquals(expected, resolve(null, Purpose.COMMUTE))
    }

    @Test
    fun bookmarksThatAreNotSetNeverMatch() {
        val expected = DefaultPurpose(Purpose.BUSINESS, PurposeSource.DEFAULT)
        assertEquals(expected, PurposeDefaults.resolve(home, home = null, office = null, lastPurpose = Purpose.COMMUTE))
        assertEquals(expected, PurposeDefaults.resolve(office, home = home, office = null, lastPurpose = Purpose.COMMUTE))
    }

    @Test
    fun samePlaceByPoiIdCoordinatesOrName() {
        assertTrue(PurposeDefaults.samePlace(NaviPlace("다른 이름", poiId = "1001"), office))
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
        assertFalse(PurposeDefaults.samePlace(NaviPlace("42dot 판교", poiId = "9999"), office))
    }

    @Test
    fun distanceNeedsBothCoordinates() {
        assertEquals(null, PurposeDefaults.distanceMeters(NaviPlace("a"), pangyo))
        val d = PurposeDefaults.distanceMeters(pangyo, NaviPlace("b", latitude = 37.4022, longitude = 127.1086))!!
        assertTrue("about 100 m, got $d", d in 95.0..105.0)
    }
}
