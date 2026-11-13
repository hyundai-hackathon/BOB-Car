package com.bobcar.vehicle.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPolicyTest {

    @Test
    fun releaseSendsOnlyOverHttps() {
        assertTrue(canSendTo("https://example.test", debug = false))
        assertFalse(canSendTo("http://example.test", debug = false))
        assertFalse(canSendTo("http://10.0.2.2:8787", debug = false))
    }

    @Test
    fun debugAlsoAcceptsHttpForTheLocalServer() {
        assertTrue(canSendTo("http://10.0.2.2:8787", debug = true))
        assertTrue(canSendTo("https://example.test", debug = true))
    }

    @Test
    fun emptyOrOtherSchemesAreNotSent() {
        listOf(true, false).forEach { debug ->
            assertFalse(canSendTo("", debug))
            assertFalse(canSendTo("   ", debug))
            assertFalse(canSendTo("https://", debug))
            assertFalse(canSendTo("ftp://example.test", debug))
            assertFalse(canSendTo("example.test", debug))
        }
    }
}
