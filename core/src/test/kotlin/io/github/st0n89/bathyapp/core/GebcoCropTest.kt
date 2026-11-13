package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.gebco.GebcoCrop
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GebcoCropTest {

    @Test
    fun requestBodyMatchesServiceContract() {
        val json = GebcoCrop.buildRequestBody(-29.0, 38.0, -28.0, 39.0)
        assertEquals(
            "{\"items\":[{\"id\":0,\"grid_id\":1,\"data_source_ids\":[1],\"formats\":[1]," +
                "\"left\":-29.000000,\"right\":-28.000000,\"top\":39.000000,\"bottom\":38.000000}]}",
            json,
        )
    }

    @Test
    fun requestBodyRejectsInvalidBoxes() {
        assertFailsWith<IllegalArgumentException> { GebcoCrop.buildRequestBody(5.0, 10.0, 4.0, 11.0) } // east < west
        assertFailsWith<IllegalArgumentException> { GebcoCrop.buildRequestBody(0.0, 10.0, 1.0, 9.0) } // north < south
        // 121° x 121° exceeds the 14400 deg^2 server limit
        assertFailsWith<IllegalArgumentException> { GebcoCrop.buildRequestBody(-60.0, -60.0, 61.0, 61.0) }
        // 120° x 120° is exactly at the limit and allowed
        assertTrue(GebcoCrop.buildRequestBody(-60.0, -60.0, 60.0, 60.0).contains("\"left\":-60.000000"))
    }

    @Test
    fun awaitFinishedPollsUntilFinished() {
        var calls = 0
        val sleeps = mutableListOf<Unit>()
        GebcoCrop.awaitFinished(
            basketId = "B1",
            timeoutMs = 10_000,
            pollMs = 5,
            fetchStatus = { calls++; if (calls < 3) "processing" else "finished" },
            sleep = { sleeps.add(Unit) },
            now = { 0L },
        )
        assertEquals(3, calls)
        assertEquals(2, sleeps.size) // slept between the first three polls
    }

    @Test
    fun awaitFinishedThrowsOnTimeout() {
        var slept = 0L
        assertFailsWith<IOException> {
            GebcoCrop.awaitFinished(
                basketId = "B2",
                timeoutMs = 1_000,
                pollMs = 100,
                fetchStatus = { "processing" },
                sleep = { slept += it },
                now = { if (slept > 1_000) 2_000L else 0L }, // time jumps past the deadline
            )
        }
        assertTrue(slept > 0)
    }

    @Test
    fun awaitFinishedThrowsOnFailure() {
        assertFailsWith<IOException> {
            GebcoCrop.awaitFinished(
                basketId = "B3",
                timeoutMs = 1_000,
                pollMs = 5,
                fetchStatus = { "error" },
                sleep = {},
                now = { 0L },
            )
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun extractNetCdfPicksTheNcEntry() {
        val nc = "CDF${1.toChar()}fake-elevation-data".toByteArray(Charsets.ISO_8859_1)
        val zipBytes = zip(
            "GEBCO_Grid_documentation.pdf" to "pdf".toByteArray(),
            "gebco_2026_n39.0_s38.0_w-29.0_e-28.0.nc" to nc,
            "GEBCO_Grid_terms_of_use.pdf" to "pdf2".toByteArray(),
        )
        val (name, bytes) = GebcoCrop.extractNetCdf(zipBytes)
        assertEquals("gebco_2026_n39.0_s38.0_w-29.0_e-28.0.nc", name)
        assertTrue(bytes.contentEquals(nc))
        // the entry is a real, readable zip member
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var found: String? = null
            var e = zis.nextEntry
            while (e != null) {
                if (e.name.endsWith(".nc")) found = String(zis.readBytes(), Charsets.ISO_8859_1)
                e = zis.nextEntry
            }
            assertEquals("CDF${1.toChar()}fake-elevation-data", found)
        }
    }

    @Test
    fun extractNetCdfWithoutNcThrows() {
        val zipBytes = zip("GEBCO_Grid_documentation.pdf" to "pdf".toByteArray())
        assertFailsWith<IOException> { GebcoCrop.extractNetCdf(zipBytes) }
    }

    @Test
    fun downloadNetCdfOrchestratesThePipeline() {
        val seen = mutableListOf<String>()
        var enqueuedJson: String? = null
        val nc = "CDF${1.toChar()}data".toByteArray(Charsets.ISO_8859_1)
        val zipBytes = zip("crop.nc" to nc)
        val (name, bytes) = GebcoCrop.downloadNetCdf(
            west = 0.0, south = 0.0, east = 1.0, north = 1.0,
            timeoutMs = 10_000, pollMs = 5,
            onStatus = { seen.add(it) },
            enqueue = { json -> enqueuedJson = json; "BASKET-1" },
            fetchStatus = { "finished" },
            sleep = {},
            now = { 0L },
            downloadBasket = { id -> assertEquals("BASKET-1", id); zipBytes },
        )
        assertEquals("crop.nc", name)
        assertTrue(bytes.contentEquals(nc))
        assertTrue(enqueuedJson!!.contains("\"left\":0.000000"))
        assertTrue(seen.any { it.contains("BASKET-1") })
        assertTrue(seen.any { it.contains("NetCDF bereit") })
    }
}
