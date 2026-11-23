package io.github.st0n89.bathyapp.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.st0n89.bathyapp.core.data.FileChannelSource
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs
import io.github.st0n89.bathyapp.core.export.Exporters
import io.github.st0n89.bathyapp.core.gebco.GebcoCrop
import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.geo.GlobeMath
import io.github.st0n89.bathyapp.core.nc3.NcGrid
import io.github.st0n89.bathyapp.core.nc3.NetCdf3File
import io.github.st0n89.bathyapp.core.profile.Profiler
import io.github.st0n89.bathyapp.core.profile.ProfilePoint
import io.github.st0n89.bathyapp.core.profile.ProfileStats
import io.github.st0n89.bathyapp.core.tiff.GeoTiffReader
import io.github.st0n89.bathyapp.core.tiff.GeoTiffWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

enum class DataSource { EMODNET, GEBCO, LOCAL_FILE }

sealed interface AppScreen {
    data object Home : AppScreen
    data object Map : AppScreen
    data object Profile : AppScreen
}

data class AppUiState(
    val screen: AppScreen = AppScreen.Home,
    val source: DataSource = DataSource.GEBCO,
    val coverageIndex: Int = 0,
    val west: String = "3.0",
    val south: String = "53.5",
    val east: String = "9.0",
    val north: String = "57.5",
    val widthPx: String = "600",
    val loading: Boolean = false,
    val loadingNote: String? = null,
    val error: String? = null,
    val grid: GeoGrid? = null,
    val gridNote: String? = null,
    val localFileName: String? = null,
    val localInfo: String? = null,
    val localReady: Boolean = false,
    val gebcoInfo: String? = null,
    /** Optional online high-resolution satellite layer on the globe (ESRI World Imagery). */
    val satelliteOnline: Boolean = false,
    val pointA: DoubleArray? = null,
    val pointB: DoubleArray? = null,
    val profile: List<ProfilePoint>? = null,
    val profileStats: ProfileStats? = null,
)

/** Handle to a local GEBCO-style NetCDF or GeoTIFF bathymetry file. */
class LocalFileHandle constructor(
    private val ncFile: NetCdf3File?,
    private val ncGrid: NcGrid?,
    private val tiffGrid: GeoGrid?,
    private val fallbackCopy: File?,
) {
    val west: Double
        get() = ncGrid?.west ?: tiffGrid!!.west
    val south: Double
        get() = ncGrid?.south ?: tiffGrid!!.south
    val east: Double
        get() = ncGrid?.east ?: tiffGrid!!.east
    val north: Double
        get() = ncGrid?.north ?: tiffGrid!!.north

    fun info(): String =
        if (ncGrid != null) {
            "NetCDF grid '${ncGrid.dataVar.name}' ${ncGrid.nCols}x${ncGrid.nRows}"
        } else {
            "GeoTIFF grid ${tiffGrid!!.nx}x${tiffGrid.ny}"
        }

    fun readSubgrid(west: Double, south: Double, east: Double, north: Double, maxCols: Int): GeoGrid =
        if (ncGrid != null) {
            ncGrid.readSubgrid(west, south, east, north, maxCols)
        } else {
            val g = tiffGrid!!
            // in-memory crop of the (already loaded) TIFF grid
            val c0 = ((west - g.west) / g.dx).toInt().coerceIn(0, g.nx - 1)
            val c1 = ((east - g.west) / g.dx).toInt().coerceIn(0, g.nx - 1)
            val r0 = ((g.north - north) / g.dy).toInt().coerceIn(0, g.ny - 1)
            val r1 = ((g.north - south) / g.dy).toInt().coerceIn(0, g.ny - 1)
            val (rc0, rc1) = if (c0 <= c1) c0 to c1 else c1 to c0
            val (rr0, rr1) = if (r0 <= r1) r0 to r1 else r1 to r0
            val w = rc1 - rc0 + 1
            val h = rr1 - rr0 + 1
            val v = FloatArray(w * h) { i -> g.values[(rr0 + i / w) * g.nx + (rc0 + i % w)] }
            GeoGrid(
                w, h, v,
                g.west + rc0 * g.dx,
                g.north - (rr1 + 1) * g.dy,
                g.west + (rc1 + 1) * g.dx,
                g.north - rr0 * g.dy,
            )
        }

    fun close() {
        try {
            ncFile?.close()
        } catch (_: Exception) {
        }
        fallbackCopy?.delete()
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val prefs = application.getSharedPreferences("bathyapp_prefs", Context.MODE_PRIVATE)

    init {
        if (prefs.getBoolean(PREF_SATELLITE_ONLINE, false)) {
            update { it.copy(satelliteOnline = true) }
        }
    }

    /** Toggles the optional online satellite layer (persisted across restarts). */
    fun setSatelliteOnline(on: Boolean) {
        prefs.edit().putBoolean(PREF_SATELLITE_ONLINE, on).apply()
        update { it.copy(satelliteOnline = on) }
    }

    private var localHandle: LocalFileHandle? = null

    /** Cached GEBCO online crop (NetCDF in the app cache dir). */
    private var gebcoHandle: LocalFileHandle? = null
    private var gebcoBounds: DoubleArray? = null
    private var gebcoFile: File? = null

    private fun update(block: (AppUiState) -> AppUiState) {
        _state.value = block(_state.value)
    }

    fun setSource(s: DataSource) = update { it.copy(source = s, error = null) }

    fun setCoverageIndex(i: Int) = update { it.copy(coverageIndex = ((i % EmodnetWcs.COVERAGES.size) + EmodnetWcs.COVERAGES.size) % EmodnetWcs.COVERAGES.size) }

    fun setWest(v: String) = update { it.copy(west = v) }
    fun setSouth(v: String) = update { it.copy(south = v) }
    fun setEast(v: String) = update { it.copy(east = v) }
    fun setNorth(v: String) = update { it.copy(north = v) }
    fun setWidthPx(v: String) = update { it.copy(widthPx = v) }

    fun goHome() = update { it.copy(screen = AppScreen.Home, error = null) }

    /**
     * Loads the currently visible map section (pan beyond the loaded grid):
     * updates the coordinate fields so Home reflects the new region, then
     * runs the normal load path. Stays on the map screen.
     */
    fun loadVisibleGrid(west: Double, south: Double, east: Double, north: Double) {
        update {
            it.copy(
                west = fmt(west), south = fmt(south),
                east = fmt(east), north = fmt(north),
            )
        }
        loadGrid()
    }

    fun goToMap() = update { if (it.grid != null) it.copy(screen = AppScreen.Map, error = null) else it }

    /**
     * Moves the selection bbox centre to (lat, lon), keeping the current
     * lon/lat spans (used by the globe picker: tap sets the region).
     */
    fun selectCenter(lat: Double, lon: Double) {
        update { st ->
            val spanLon = spanLonOf(st)
            val spanLat = spanLatOf(st)
            val b = GlobeMath.bboxAround(GlobeMath.normLat(lat), GlobeMath.normLon(lon), spanLon, spanLat)
            st.copy(west = fmt(b[0]), south = fmt(b[1]), east = fmt(b[2]), north = fmt(b[3]))
        }
    }

    /**
     * Scales the selection bbox about its centre. k = 1 corresponds to the
     * default 6° x 4° region; k is clamped so the GEBCO crop limit holds.
     */
    fun scaleSelection(k: Double) {
        update { st ->
            val spanLon = (6.0 * k).coerceIn(0.002, 170.0)
            val spanLat = (4.0 * k).coerceIn(0.002, 170.0)
            val cLon = centreLonOf(st)
            val cLat = centreLatOf(st)
            val b = GlobeMath.bboxAround(cLat, cLon, spanLon, spanLat)
            st.copy(west = fmt(b[0]), south = fmt(b[1]), east = fmt(b[2]), north = fmt(b[3]))
        }
    }

    /**
     * Sets the raw selection spans (in degrees) around the current centre,
     * used by the globe's corner-handle resize gesture.
     */
    fun setSpans(spanLon: Double, spanLat: Double) {
        update { st ->
            val sLon = spanLon.coerceIn(0.002, 170.0)
            val sLat = spanLat.coerceIn(0.002, 170.0)
            val b = GlobeMath.bboxAround(centreLatOf(st), centreLonOf(st), sLon, sLat)
            st.copy(west = fmt(b[0]), south = fmt(b[1]), east = fmt(b[2]), north = fmt(b[3]))
        }
    }

    private fun spanLonOf(st: AppUiState): Double {
        val w = st.west.trim().toDoubleOrNull() ?: return 6.0
        val e = st.east.trim().toDoubleOrNull() ?: return 6.0
        return (e - w).coerceIn(0.002, 170.0)
    }

    private fun spanLatOf(st: AppUiState): Double {
        val s = st.south.trim().toDoubleOrNull() ?: return 4.0
        val n = st.north.trim().toDoubleOrNull() ?: return 4.0
        return (n - s).coerceIn(0.002, 170.0)
    }

    private fun centreLonOf(st: AppUiState): Double {
        val w = st.west.trim().toDoubleOrNull() ?: return 6.0
        val e = st.east.trim().toDoubleOrNull() ?: return 9.0
        return GlobeMath.normLon((w + e) / 2.0)
    }

    private fun centreLatOf(st: AppUiState): Double {
        val s = st.south.trim().toDoubleOrNull() ?: return 53.5
        val n = st.north.trim().toDoubleOrNull() ?: return 57.5
        return GlobeMath.normLat((s + n) / 2.0)
    }

    fun setPoint(lon: Double, lat: Double) = update { st ->
        when {
            st.pointA == null -> st.copy(pointA = doubleArrayOf(lon, lat), profile = null, profileStats = null)
            st.pointB == null -> st.copy(pointB = doubleArrayOf(lon, lat), profile = null, profileStats = null)
            else -> st.copy(pointB = doubleArrayOf(lon, lat), profile = null, profileStats = null)
        }
    }

    fun clearPoints() = update { it.copy(pointA = null, pointB = null, profile = null, profileStats = null) }

    fun computeProfile(samples: Int = 1000) {
        val st = _state.value
        val grid = st.grid ?: return
        val a = st.pointA ?: return
        val b = st.pointB ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val pts = Profiler.sample(grid, a[0], a[1], b[0], b[1], samples)
            val stats = Profiler.stats(pts)
            _state.value = _state.value.copy(profile = pts, profileStats = stats, screen = AppScreen.Profile)
        }
    }

    // ---------------- loading ----------------

    private fun parse(state: AppUiState): Result {
        val w = state.west.trim().toDoubleOrNull() ?: return Result(error = "West invalid")
        val s = state.south.trim().toDoubleOrNull() ?: return Result(error = "Süd invalid")
        val e = state.east.trim().toDoubleOrNull() ?: return Result(error = "Ost invalid")
        val n = state.north.trim().toDoubleOrNull() ?: return Result(error = "Nord invalid")
        if (e <= w) return Result(error = "Ost muss größer als West sein")
        if (n <= s) return Result(error = "Nord muss größer als Süd sein")
        val cols = state.widthPx.trim().toIntOrNull() ?: return Result(error = "Gitterbreite invalid")
        if (cols !in 2..EmodnetWcs.MAX_COLS) return Result(error = "Gitterbreite muss 2..${EmodnetWcs.MAX_COLS} sein")
        return Result(bbox = doubleArrayOf(w, s, e, n), cols = cols)
    }

    private class Result(val bbox: DoubleArray? = null, val cols: Int = 0, val error: String? = null)

    fun loadGrid() {
        val st = _state.value
        val parsed = parse(st)
        if (parsed.error != null) {
            update { it.copy(error = parsed.error) }
            return
        }
        val bbox = parsed.bbox!!
        val cols = parsed.cols
        update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val grid: GeoGrid = when (st.source) {
                    DataSource.EMODNET -> {
                        val coverage = EmodnetWcs.COVERAGES[st.coverageIndex].id
                        EmodnetWcs.loadGrid(coverage, bbox[0], bbox[1], bbox[2], bbox[3], cols) { note ->
                            _state.value = _state.value.copy(loadingNote = note)
                        }
                    }
                    DataSource.GEBCO -> loadGebcoGrid(bbox[0], bbox[1], bbox[2], bbox[3], cols)
                    DataSource.LOCAL_FILE -> {
                        val handle = localHandle ?: throw IllegalStateException("Keine Datei geladen")
                        handle.readSubgrid(bbox[0], bbox[1], bbox[2], bbox[3], cols)
                    }
                }
                val note = when (st.source) {
                    DataSource.EMODNET -> "EMODnet Bathymetry WCS (${EmodnetWcs.COVERAGES[st.coverageIndex].id})"
                    DataSource.GEBCO -> _state.value.gebcoInfo ?: "GEBCO 2026 (online crop)"
                    DataSource.LOCAL_FILE -> "local file: ${_state.value.localFileName}"
                } + ", " + Exporters.extentNote(grid)
                _state.value = _state.value.copy(
                    loading = false, loadingNote = null, grid = grid, gridNote = note,
                    screen = AppScreen.Map, pointA = null, pointB = null,
                    profile = null, profileStats = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, loadingNote = null, error = e.message ?: e.toString())
            }
        }
    }

    // ---------------- GEBCO online crop ----------------

    /**
     * Loads a grid from GEBCO 2026 (https://download.gebco.net). The first
     * request asks the server to cut a crop for the bbox (+0.25° margin);
     * the returned NetCDF is cached locally, so follow-up requests inside
     * the cached area are served without any network access.
     */
    private fun loadGebcoGrid(west: Double, south: Double, east: Double, north: Double, cols: Int): GeoGrid {
        val bounds = gebcoBounds
        val cached = gebcoHandle
        if (cached != null && bounds != null &&
            west >= bounds[0] && east <= bounds[2] && south >= bounds[1] && north <= bounds[3]
        ) return cached.readSubgrid(west, south, east, north, cols)

        update { it.copy(loadingNote = "Frage GEBCO-Crop an (Server braucht einen Moment) …") }
        val margin = 0.25
        val cw = (west - margin).coerceIn(-180.0, 180.0)
        val ce = (east + margin).coerceIn(-180.0, 180.0)
        val cs = (south - margin).coerceIn(-90.0, 90.0)
        val cn = (north + margin).coerceIn(-90.0, 90.0)
        val (name, ncBytes) = GebcoCrop.downloadNetCdf(cw, cs, ce, cn, onStatus = { note ->
            update { s -> s.copy(loadingNote = note) }
        })

        // replace the previous crop cache
        val oldFile = gebcoFile
        gebcoHandle?.close()
        oldFile?.delete()

        val cache = File(getApplication<Application>().cacheDir, "gebco_crop.nc")
        FileOutputStream(cache).use { it.write(ncBytes) }
        val nc = NetCdf3File(FileChannelSource(RandomAccessFile(cache, "r").channel))
        val ncGrid = NcGrid.open(nc)
        gebcoHandle = LocalFileHandle(nc, ncGrid, null, null)
        gebcoBounds = doubleArrayOf(ncGrid.west, ncGrid.south, ncGrid.east, ncGrid.north)
        gebcoFile = cache
        update {
            it.copy(
                gebcoInfo = "GEBCO 2026 online crop ($name), " +
                    "${fmt(ncGrid.west)}°…${fmt(ncGrid.east)}°E, ${fmt(ncGrid.south)}°…${fmt(ncGrid.north)}°N",
            )
        }
        return gebcoHandle!!.readSubgrid(west, south, east, north, cols)
    }

    // ---------------- local files ----------------

    fun openLocalFile(context: Context, uri: Uri) {
        update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val name = queryName(resolver, uri) ?: "datei"
                val handle = openHandle(context, uri)
                localHandle?.close()
                localHandle = handle
                val info = "${handle.info()}: W ${fmt(handle.west)} … E ${fmt(handle.east)}, S ${fmt(handle.south)} … N ${fmt(handle.north)}"
                _state.value = _state.value.copy(
                    loading = false, localReady = true, localFileName = name, localInfo = info,
                    source = DataSource.LOCAL_FILE,
                    west = fmt(handle.west), south = fmt(handle.south),
                    east = fmt(handle.east), north = fmt(handle.north),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString())
            }
        }
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.4f", v)

    private fun queryName(resolver: android.content.ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun openHandle(context: Context, uri: Uri): LocalFileHandle {
        val resolver = context.contentResolver
        try {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
            pfd.use { fd ->
                val channel = FileInputStream(fd.fileDescriptor).channel
                val src = FileChannelSource(channel)
                return readHandle(src, null)
            }
        } catch (e: Exception) {
            // fallback: stream-copy to cache (some providers have no seekable fd)
            val cache = File(context.cacheDir, "bathy_import.bin")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cache).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Datei konnte nicht gelesen werden")
            val raf = RandomAccessFile(cache, "r")
            return readHandle(FileChannelSource(raf.channel), cache)
        }
    }

    private fun readHandle(src: FileChannelSource, copy: File?): LocalFileHandle {
        val magic = src.readAt(0, 4)
        return if (magic[0] == 'C'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == 'F'.code.toByte()) {
            val nc = NetCdf3File(src)
            val ncGrid = NcGrid.open(nc)
            LocalFileHandle(nc, ncGrid, null, copy)
        } else if (magic[0] == 'I'.code.toByte() || magic[0] == 'M'.code.toByte()) {
            val tiff = GeoTiffReader.read(src)
            if (tiff.width.toLong() * tiff.height > 32_000_000L) {
                src.close()
                copy?.delete()
                throw IllegalStateException("GeoTIFF zu groß für In-Memory-Laden (${tiff.width}x${tiff.height}). Nutze die NetCDF-Version.")
            }
            val grid = tiff.toGeoGrid()
            src.close()
            copy?.delete()
            LocalFileHandle(null, null, grid, null)
        } else {
            src.close()
            copy?.delete()
            throw IllegalStateException("Unbekanntes Format: weder NetCDF (CDF-Magic) noch TIFF")
        }
    }

    // ---------------- exports ----------------

    fun exportGeoTiff(context: Context, uri: Uri) {
        val grid = _state.value.grid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    GeoTiffWriter.write(grid, out)
                } ?: throw IllegalStateException("Ziel konnte nicht geöffnet werden")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(error = "GeoTIFF-Export fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    fun exportGridCsv(context: Context, uri: Uri) {
        val grid = _state.value.grid ?: return
        val note = _state.value.gridNote ?: ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    Exporters.writeGridCsv(grid, out, note)
                } ?: throw IllegalStateException("Ziel konnte nicht geöffnet werden")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(error = "CSV-Export fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    fun exportProfileCsv(context: Context, uri: Uri) {
        val pts = _state.value.profile ?: return
        val note = _state.value.gridNote ?: ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    Exporters.writeProfileCsv(pts, out, note)
                } ?: throw IllegalStateException("Ziel konnte nicht geöffnet werden")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(error = "Profil-Export fehlgeschlagen: ${e.message}")
                }
            }
        }
    }

    fun clearError() = update { it.copy(error = null) }

    override fun onCleared() {
        localHandle?.close()
        gebcoHandle?.close()
    }

    companion object {
        const val PREF_SATELLITE_ONLINE = "satellite_online"
    }
}
