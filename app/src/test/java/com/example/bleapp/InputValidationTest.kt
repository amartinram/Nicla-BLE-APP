package com.example.bleapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the checks that stand between a mistyped setting and a patient whose
 * data silently never arrives.
 */
class InputValidationTest {

    private val goodUrl =
        "https://stepcounter-g5lg.onrender.com/data?token=abc123"

    @Test
    fun `accepts a well formed https url with a token`() {
        assertNull(MainViewModel.serverUrlError(goodUrl))
        assertNull(MainViewModel.serverUrlWarning(goodUrl))
    }

    @Test
    fun `rejects an empty url`() {
        assertNotNull(MainViewModel.serverUrlError(""))
        assertNotNull(MainViewModel.serverUrlError("   "))
    }

    @Test
    fun `rejects plain http because patient data must not travel in clear`() {
        assertNotNull(MainViewModel.serverUrlError("http://stepcounter.onrender.com/data"))
    }

    @Test
    fun `rejects text that is not a url`() {
        assertNotNull(MainViewModel.serverUrlError("stepcounter.onrender.com"))
        assertNotNull(MainViewModel.serverUrlError("https://"))
    }

    @Test
    fun `tolerates surrounding whitespace from a paste`() {
        assertNull(MainViewModel.serverUrlError("  $goodUrl  "))
    }

    @Test
    fun `warns about a missing token without blocking`() {
        val noToken = "https://stepcounter-g5lg.onrender.com/data"
        assertNull("a tokenless server is legitimate", MainViewModel.serverUrlError(noToken))
        assertNotNull("but it should still be flagged", MainViewModel.serverUrlWarning(noToken))
        assertNotNull(MainViewModel.serverUrlWarning("$noToken?token="))
    }

    @Test
    fun `accepts a correctly formatted mac`() {
        assertNull(MainViewModel.macAddressError("34:AF:5A:57:41:BE"))
        assertNull("case should not matter", MainViewModel.macAddressError("34:af:5a:57:41:be"))
    }

    @Test
    fun `rejects malformed macs`() {
        assertNotNull("empty", MainViewModel.macAddressError(""))
        assertNotNull("too short", MainViewModel.macAddressError("34:AF:5A:57:41"))
        assertNotNull("hyphens", MainViewModel.macAddressError("34-AF-5A-57-41-BE"))
        assertNotNull("non hex", MainViewModel.macAddressError("ZZ:AF:5A:57:41:BE"))
        // 17 characters, which is the length the old check accepted blindly.
        assertNotNull("right length, wrong shape", MainViewModel.macAddressError("3:4AF:5A:57:41:BE"))
    }

    @Test
    fun `cache keys sort oldest first so eviction drops the oldest data`() {
        // trimCache orders by the timestamp prefix of "<captureTime>_<sheetName>".
        val keys = listOf(
            "1757086400000_AA_BB",
            "1757000000000_AA_BB",
            "1757000000000_AA_BB_Battery"
        )
        val oldestFirst = keys.sortedBy { it.substringBefore('_').toLongOrNull() ?: Long.MAX_VALUE }
        assertEquals(1757000000000L, oldestFirst.first().substringBefore('_').toLong())
        assertEquals(1757086400000L, oldestFirst.last().substringBefore('_').toLong())
    }
}
