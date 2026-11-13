package io.github.st0n89.bathyapp.core.gebco

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Client for the GEBCO download service crop API (https://download.gebco.net).
 *
 * The service cuts the global GEBCO grid (15 arc-second) to any bounding box
 * asynchronously: POST /api/queue submits a "basket", GET
 * /api/queue/status/{id} reports its state, GET /api/queue/download/{id}
 * returns a ZIP that contains the NetCDF (CDF-1, values in metres, positive
 * up) plus documentation PDFs. All endpoints are public, no authentication.
 *
 * The returned NetCDF feeds straight into NetCdf3File/NcGrid.
 */
object GebcoCrop {

    const val BASE_URL = "https://download.gebco.net/api"

    /** Server-side grid id of "GEBCO 2026 Global". */
    const val GRID_GEOBCO_2026 = 1

    /** Server-side data source id: bathymetry (land + ice surface). */
    const val SOURCE_BATHYMETRY = 1

    /** Server-side format id: NetCDF. */
    const val FORMAT_NETCDF = 1

    /** Maximum requestable area in square degrees (server limit). */
    const val MAX_AREA_DEG2 = 14400.0

    /**
     * Request body exactly as the web client submits it
     * (minus optional email/basket metadata).
     */
    fun buildRequestBody(west: Double, south: Double, east: Double, north: Double): String {
        require(east > west && north > south) { "invalid bounding box" }
        val area = (east - west) * (north - south)
        require(area <= MAX_AREA_DEG2) {
            "bounding box too large: ${"%.1f".format(area)} deg^2 > $MAX_AREA_DEG2 deg^2"
        }
        return String.format(
            Locale.ROOT,
            "{\"items\":[{\"id\":0,\"grid_id\":%d,\"data_source_ids\":[%d],\"formats\":[%d]," +
                "\"left\":%.6f,\"right\":%.6f,\"top\":%.6f,\"bottom\":%.6f}]}",
            GRID_GEOBCO_2026, SOURCE_BATHYMETRY, FORMAT_NETCDF,
            west, east, north, south,
        )
    }

    /** Submits a crop basket; returns the basket id. */
    fun enqueue(requestJson: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000): String {
        val conn = (URL("$BASE_URL/queue").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "BathyApp (Android)")
        }
        try {
            conn.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) throw IOException(serverError(body, code))
            val id = Regex("\"basketId\"\\s*:\\s*\"([^\"]+)\"").find(body.toString(Charsets.UTF_8))?.groupValues?.get(1)
                ?: throw IOException("GEBCO: response contains no basketId: ${snippet(body)}")
            return id
        } finally {
            conn.disconnect()
        }
    }

    /** Polls the basket state once; returns the raw status string. */
    fun fetchStatus(basketId: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000): String {
        val conn = open("$BASE_URL/queue/status/${urlEncode(basketId)}")
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) throw IOException(serverError(body, code))
            return Regex("\"status\"\\s*:\\s*\"([^\"]*)\"").find(body.toString(Charsets.UTF_8))?.groupValues?.get(1)
                ?: throw IOException("GEBCO: status response without status field: ${snippet(body)}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Waits until the basket is finished. All side effects (status fetch,
     * sleep, clock) are injectable for unit tests.
     */
    fun awaitFinished(
        basketId: String,
        timeoutMs: Long = 240_000,
        pollMs: Long = 2_500,
        fetchStatus: (String) -> String = ::fetchStatus,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = System::currentTimeMillis,
        onStatus: (String) -> Unit = {},
    ) {
        require(pollMs > 0) { "pollMs must be positive" }
        val deadline = now() + timeoutMs
        while (true) {
            val status = fetchStatus(basketId)
            onStatus(status)
            when (status.lowercase(Locale.ROOT)) {
                "finished" -> return
                "error", "failed" -> throw IOException("GEBCO: basket $basketId failed with status '$status'")
            }
            if (now() >= deadline) throw IOException("GEBCO: timeout after ${timeoutMs / 1000}s waiting for basket $basketId")
            sleep(pollMs)
        }
    }

    /** Downloads the finished basket; returns the ZIP bytes. */
    fun downloadBasket(basketId: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 300_000): ByteArray {
        val conn = open("$BASE_URL/queue/download/${urlEncode(basketId)}")
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code !in 200..299) throw IOException(serverError(body, code))
            if (body.size < 4 || body[0] != 'P'.code.toByte() || body[1] != 'K'.code.toByte()) {
                throw IOException("GEBCO: basket $basketId did not return a ZIP file")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extracts the NetCDF entry from a GEBCO basket ZIP.
     * Returns the entry name and the raw file bytes.
     */
    fun extractNetCdf(zipBytes: ByteArray): Pair<String, ByteArray> {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".nc", ignoreCase = true)) {
                    return entry.name to zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        throw IOException("GEBCO: ZIP contains no NetCDF (.nc) file")
    }

    /**
     * Full pipeline: submit the crop, wait for it, download the ZIP and
     * return the extracted NetCDF (entry name + bytes).
     */
    fun downloadNetCdf(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        timeoutMs: Long = 240_000,
        pollMs: Long = 2_500,
        onStatus: (String) -> Unit = {},
        enqueue: (String) -> String = ::enqueue,
        fetchStatus: (String) -> String = ::fetchStatus,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        now: () -> Long = System::currentTimeMillis,
        downloadBasket: (String) -> ByteArray = ::downloadBasket,
    ): Pair<String, ByteArray> {
        val basketId = enqueue(buildRequestBody(west, south, east, north))
        onStatus("Basket $basketId übermittelt")
        awaitFinished(basketId, timeoutMs, pollMs, fetchStatus, sleep, now) { status ->
            onStatus("GEBCO-Server: $status")
        }
        onStatus("Lade NetCDF …")
        val zip = downloadBasket(basketId)
        val (name, ncBytes) = extractNetCdf(zip)
        onStatus("NetCDF bereit ($name)")
        return name to ncBytes
    }

    private fun open(url: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 60_000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BathyApp (Android)")
        }

    private fun readBody(conn: HttpURLConnection, code: Int): ByteArray {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun snippet(body: ByteArray): String =
        String(body, Charsets.UTF_8).take(200)

    private fun serverError(body: ByteArray, code: Int): String {
        val text = snippet(body)
        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        return buildString {
            append("GEBCO error (HTTP ").append(code).append(')')
            if (msg != null) append(": ").append(msg) else if (text.isNotBlank()) append(" - ").append(text.take(200))
        }
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
