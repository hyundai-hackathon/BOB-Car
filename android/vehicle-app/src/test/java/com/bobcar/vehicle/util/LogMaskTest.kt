package com.bobcar.vehicle.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LogMaskTest {

    @Test
    fun emptyText() {
        assertEquals("(empty)", maskForLog(null))
        assertEquals("(empty)", maskForLog(""))
    }

    @Test
    fun shortNamesShowOnlyTheLength() {
        assertEquals("***(1)", maskForLog("집"))
        assertEquals("***(2)", maskForLog("회사"))
    }

    @Test
    fun longerNamesShowTheFirstCharacterAndLength() {
        assertEquals("인***(6)", maskForLog("인천국제공항"))
    }
}
