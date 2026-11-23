package io.github.st0n89.bathyapp.core.seismic

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.profile.Profiler
import io.github.st0n89.bathyapp.core.sediment.SedGrid
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class FdtdWaveform { HANN, RICKER }

/** Seismic source placed on the map. Depth is metres below the water surface. */
data class FdtdShot(
    val lon: Double,
    val lat: Double,
    val depthM: Double,
    val f0: Double,
    val waveform: FdtdWaveform,
    val amp: Double = 1.0e3,
)

/** Receiver placed on the map. Depth is metres below the water surface. */
data class FdtdReceiver(val lon: Double, val lat: Double, val depthM: Double)

data class FdtdExportPlan(
    val dx: Double,
    val dz: Double,
    val nx: Int,
    val nz: Int,
    val xmax: Double,
    val zmax: Double,
    val lineLenM: Double,
    val bathyMaxM: Double,
    val sedMaxM: Double,
    val classCounts: Map<BottomClass, Int>,
    val maxCrossTrackM: Double,
    val coarsened: Boolean,
    val landNodes: Int,
    val tmaxHintS: Double,
    val warnings: List<String>,
)

data class FdtdExportResult(val envText: String, val sourceText: String, val plan: FdtdExportPlan)

/**
 * Exports a shot + receiver survey as env.jl / source.jl for the
 * Fdtd2DElastic Julia suite (github.com/St0n89/Fdtd2DElastic).
 *
 * Model: 2.5-D slice along the great circle from the shot through the
 * radially farthest receiver; other receivers are projected onto it.
 * Grid sizing targets [PPW] points per (water) wavelength at f0, with
 * automatic dx coarsening if the cell budget would be exceeded.
 *
 * env.jl contract (mirrors toy/env_toy.jl): dx, dz, xmax, zmax,
 * water_depth, c_water, rho_water, c_bottom, rho_bottom, Qp_water,
 * Qp_bottom, pml_cells, nx, nz, x, z, lam, mu, rho, Qp, Qs and
 * `grid = ElasticGrid(nx, nz, dx, dz, lam, mu, rho, Qp, Qs)`.
 *
 * source.jl contract (mirrors toy/sr_toy.jl): f0, source_kind, src_amp,
 * source_x, source_z, ix, iz (Int!), check_f_max, pulse_rate, sources,
 * monopoles, bc, receivers, SL_REF_RANGE, SL_REF_IS_RECEIVER.
 */
object FdtdExport {

    const val PML_CELLS = 12
    private const val PPW = 10.0
    private const val MAX_CELLS = 3_000_000L
    private const val MAX_NX = 8000
    private const val EARTH_R_M = Profiler.EARTH_RADIUS_KM * 1000.0

    fun build(grid: GeoGrid, sed: SedGrid, shot: FdtdShot, receivers: List<FdtdReceiver>, sourceNote: String): FdtdExportResult {
        require(receivers.isNotEmpty()) { "no receivers placed" }
        val warnings = mutableListOf<String>()

        // ---- land guards ------------------------------------------------
        fun assertWater(lon: Double, lat: Double, what: String): Double {
            val e = grid.sample(lon, lat)
            require(!e.isNaN() && e < 0) { "$what is on land or outside the loaded grid (elevation ${if (e.isNaN()) "NaN" else e} m)" }
            return -e.toDouble()
        }
        val shotDepthMax = assertWater(shot.lon, shot.lat, "source")
        require(shot.depthM in 1.0..(shotDepthMax - 1.0).coerceAtLeast(2.0)) {
            "source depth ${shot.depthM} m outside the water column (0..${shotDepthMax} m)"
        }
        for ((i, r) in receivers.withIndex()) {
            val d = assertWater(r.lon, r.lat, "receiver ${i + 1}")
            require(r.depthM in 1.0..(d - 1.0).coerceAtLeast(2.0)) {
                "receiver ${i + 1} depth ${r.depthM} m outside the water column (0..${d} m)"
            }
        }

        // ---- local projection about the shot -----------------------------
        fun localMetres(lon: Double, lat: Double): Pair<Double, Double> {
            val mLat = EARTH_R_M * (lat - shot.lat) * PI / 180.0
            val mLon = EARTH_R_M * (lon - shot.lon) * PI / 180.0 * cos((shot.lat + lat) / 2.0 * PI / 180.0)
            return mLon to mLat
        }
        val loc = receivers.map { localMetres(it.lon, it.lat) }
        val farIdx = loc.indices.maxByOrNull { hypot(loc[it].first, loc[it].second) } ?: 0
        val far = loc[farIdx]
        val farRange = sqrt(far.first * far.first + far.second * far.second)
        val az = atan2(far.second, far.first)

        val along = DoubleArray(receivers.size)
        val cross = DoubleArray(receivers.size)
        for (i in loc.indices) {
            along[i] = loc[i].first * cos(az) + loc[i].second * sin(az)
            cross[i] = -loc[i].first * sin(az) + loc[i].second * cos(az)
        }
        val xStart = min(0.0, along.min())
        val xEnd = max(0.0, along.max())
        val lineLen = xEnd - xStart
        require(lineLen >= 100.0) { "line too short (${"%.0f".format(lineLen)} m)" }
        val maxCross = cross.maxByOrNull { kotlin.math.abs(it) }?.let { kotlin.math.abs(it) } ?: 0.0
        if (maxCross > max(100.0, 0.05 * lineLen)) {
            warnings.add("2.5-D: receivers have cross-track offsets up to ${"%.0f".format(maxCross)} m (${"%.1f".format(100 * maxCross / lineLen)} % of range); they are projected onto the slice line")
        }

        // ---- grid sizing, slice sampling (iterate on dx) ------------------
        val dx0 = max(1.0, ceil(BottomClasses.WATER.vp / (PPW * shot.f0)))
        var dx = dx0
        var plan2: SliceArrays? = null
        var dims: Dims? = null
        var coarsened = false
        for (iter in 0 until 12) {
            val margin = max(max((PML_CELLS + 8) * dx, 2.0 * BottomClasses.WATER.vp / shot.f0), 500.0)
            val nx = (ceil((lineLen + 2.0 * margin) / dx).toInt() + 1).coerceAtLeast(16)
            val sl = sampleSlice(grid, sed, shot, receivers[farIdx], xStart, xEnd, margin, dx, nx, az, farRange)
            val zBuffer = max(max(0.25 * sl.bathyMax, 400.0), (PML_CELLS + 6) * dx)
            val zNeeded = max(
                max(sl.bathyMax + sl.sedMax + zBuffer, maxReceiverDepth(receivers) + 4 * dx),
                shot.depthM + 4 * dx,
            )
            val nz = (ceil(zNeeded / dx).toInt() + 1).coerceAtLeast(16)
            if (nx.toLong() * nz <= MAX_CELLS && nx <= MAX_NX) {
                plan2 = sl
                dims = Dims(nx, nz, dx, dx, (nx - 1) * dx, (nz - 1) * dx)
                break
            }
            coarsened = true
            dx = ceil(dx * 1.5)
        }
        val sl = plan2 ?: throw IllegalStateException("grid sizing failed")
        val d = dims!!

        if (coarsened) {
            warnings.add("grid auto-coarsened to dx = dz = $dx m — below the ${PPW.toInt()} ppw target at ${shot.f0} Hz; consider a shorter line or lower f0")
        }

        // shot grid position
        val sourceX = (sl.margin + (0.0 - xStart)).coerceAtMost(d.xmax - sl.margin)
        require(sourceX in sl.margin..(d.xmax - sl.margin)) { "source framing failed" }
        val ix = (sourceX / d.dx).toInt() + 1
        val iz = (shot.depthM / d.dz).toInt() + 1

        // receiver grid positions (projected along the slice line)
        val recX = DoubleArray(receivers.size)
        for (i in receivers.indices) {
            recX[i] = sl.margin + (along[i] - xStart)
            val rix = (recX[i] / d.dx).toInt() + 1
            require(rix in PML_CELLS..(d.nx - PML_CELLS)) {
                "receiver ${i + 1} framing failed (ix $rix outside ${PML_CELLS}..${d.nx - PML_CELLS})"
            }
        }

        val tmaxHint = 1.3 * (d.xmax - sourceX + d.zmax) / BottomClasses.WATER.vp + 20.0 / shot.f0

        val counts = BottomClass.entries.associateWith { c -> sl.classId.count { it == c } }
        if (sl.landNodes > 0) {
            warnings.add("slice crosses land at ${sl.landNodes} of ${d.nx} columns — land columns are modelled as basement up to z = 0 (free surface)")
        }

        val envText = writeEnv(shot, receivers, sl, d, sourceNote, warnings, counts)
        val sourceText = writeSource(shot, receivers, sl, d, sourceX, ix, iz, recX, warnings)

        val plan = FdtdExportPlan(
            dx = d.dx, dz = d.dz, nx = d.nx, nz = d.nz,
            xmax = d.xmax, zmax = d.zmax,
            lineLenM = lineLen, bathyMaxM = sl.bathyMax, sedMaxM = sl.sedMax,
            classCounts = counts, maxCrossTrackM = maxCross,
            coarsened = coarsened, landNodes = sl.landNodes,
            tmaxHintS = tmaxHint, warnings = warnings,
        )
        return FdtdExportResult(envText, sourceText, plan)
    }

    private class Dims(val nx: Int, val nz: Int, val dx: Double, val dz: Double, val xmax: Double, val zmax: Double)

    private class SliceArrays(
        val dx2: Double,
        val margin: Double,
        val bathy: DoubleArray,
        val sedBase: DoubleArray,
        val sed: List<BottomClasses.Props>,
        val classId: List<BottomClass>,
        val bathyMax: Double,
        val sedMax: Double,
        val landNodes: Int,
    )

    private fun maxReceiverDepth(receivers: List<FdtdReceiver>): Double =
        receivers.maxOf { it.depthM }

    // ---------------------------------------------------------------- slice

    /**
     * Sample the environment along the slice: column j of the export grid
     * sits [dx] metres apart; world coordinate s runs 0 (shot) .. farRange
     * (farthest receiver) along the great-circle bearing.
     */
    private fun sampleSlice(
        grid: GeoGrid, sed: SedGrid, shot: FdtdShot, far: FdtdReceiver,
        xStart: Double, xEnd: Double, margin: Double, dx: Double, nx: Int,
        az: Double, farRange: Double,
    ): SliceArrays {
        val (brg, ang) = bearing(shot.lon, shot.lat, far.lon, far.lat)
        val bathy = DoubleArray(nx)
        val sedBase = DoubleArray(nx)
        val sedProps = ArrayList<BottomClasses.Props>(nx)
        val classIds = ArrayList<BottomClass>(nx)
        var bathyMax = 0.0
        var sedMax = 0.0
        var landNodes = 0
        var lastBathy = -1.0
        var lastThick = -1.0
        for (j in 0 until nx) {
            val sWorld = (xStart + (j * dx - margin)).coerceIn(0.0, farRange)
            val (lonJ, latJ) = destination(shot.lon, shot.lat, brg, sWorld, ang)
            var e = grid.sample(lonJ, latJ).toDouble()
            if (e.isNaN()) {
                if (lastBathy < 0) {
                    e = 0.0 // corridor start outside the crop: treat as land column
                } else {
                    e = -lastBathy // carry the last valid depth
                }
            }
            var t = sed.sample(lonJ, latJ)
            if (lastThick >= 0 && t <= 0.0 && lastBathy >= 0) t = lastThick
            if (e >= 0) {
                bathy[j] = 0.0
                sedBase[j] = 0.0
                sedProps.add(BottomClasses.ROCK)
                classIds.add(BottomClass.ROCK)
                landNodes++
                lastBathy = -1.0
                lastThick = -1.0
            } else {
                val w = -e
                val cls = BottomClasses.classify(w, t)
                lastBathy = w
                lastThick = t
                bathy[j] = w
                sedBase[j] = if (cls == BottomClass.ROCK) w else w + t
                sedProps.add(
                    when (cls) {
                        BottomClass.ROCK -> BottomClasses.ROCK
                        BottomClass.MUD -> BottomClasses.MUD
                        BottomClass.SAND -> BottomClasses.SAND
                    }
                )
                classIds.add(cls)
                bathyMax = max(bathyMax, w)
                sedMax = max(sedMax, sedBase[j] - w)
            }
        }
        return SliceArrays(dx2 = dx, margin = margin, bathy = bathy, sedBase = sedBase, sed = sedProps, classId = classIds, bathyMax = bathyMax, sedMax = sedMax, landNodes = landNodes)
    }

    /** Great-circle initial bearing (rad) and angular distance (rad) between two points. */
    private fun bearing(lon0: Double, lat0: Double, lon1: Double, lat1: Double): Pair<Double, Double> {
        val p0 = toRad(lat0); val p1 = toRad(lat1)
        val dl = toRad(lon1 - lon0)
        val y = sin(dl) * cos(p1)
        val x = cos(p0) * sin(p1) - sin(p0) * cos(p1) * cos(dl)
        val brg = atan2(y, x)
        val dot = sin(p0) * sin(p1) + cos(p0) * cos(p1) * cos(dl)
        val ang = acos(dot.coerceIn(-1.0, 1.0))
        return brg to ang
    }

    /** Point at great-circle distance [m] from (lon0, lat0) along bearing brg. */
    private fun destination(lon0: Double, lat0: Double, brg: Double, distM: Double, totalAng: Double): Pair<Double, Double> {
        val d = (distM / EARTH_R_M).coerceAtMost(totalAng)
        val p1 = toRad(lat0)
        val l1 = toRad(lon0)
        val sinP1 = sin(p1); val cosP1 = cos(p1)
        val sinD = sin(d); val cosD = cos(d)
        val p2 = asin(sinP1 * cosD + cosP1 * sinD * cos(brg))
        val l2 = l1 + atan2(sin(brg) * sinD * cosP1, cosD - sinP1 * sin(p2))
        return normLon(toDeg(l2)) to toDeg(p2)
    }

    // ---------------------------------------------------------------- env.jl

    private fun writeEnv(
        shot: FdtdShot, receivers: List<FdtdReceiver>, sl: SliceArrays, d: Dims,
        sourceNote: String, warnings: List<String>, counts: Map<BottomClass, Int>,
    ): String {
        val recvList = receivers.withIndex().joinToString(", ") { (i, r) ->
            "R${i + 1} (${f(r.lon)}, ${f(r.lat)}) @ ${f(r.depthM)} m"
        }
        val hdr = headerComment(
            "env.jl — Fdtd2DElastic environment exported by BathyApp",
            listOf(
                "Bathymetry : " + sourceNote,
                "Sediment   : GlobSed v3 total sediment thickness (NOAA NCEI, 5 arc min, metres)",
                "Shot       : (${f(shot.lon)}, ${f(shot.lat)}), depth ${f(shot.depthM)} m",
                "Receivers  : $recvList",
                "Model      : water (vp 1500, mu 0) over classified sediment",
                "             (mud vp 1550 vs 60 rho 1550 | sand vp 1800 vs 140 rho 1950)",
                "             over basement (vp 4500 vs 2400 rho 2600); thin cover",
                "             (<= ${f(BottomClasses.THIN_M)} m) counts as rock.",
                "Classes    : mud/sand/rock = ${counts[BottomClass.MUD] ?: 0}/${counts[BottomClass.SAND] ?: 0}/${counts[BottomClass.ROCK] ?: 0} of ${d.nx} columns,",
                "Grid       : ${d.nx} x ${d.nz} @ dx = dz = ${f(d.dx)} m (${PPW.toInt()} ppw at ${f(shot.f0)} Hz in water),",
                "             free surface at z = 0 (sea level), PML 3 sides.",
                "Warning(s) : " + (if (warnings.isEmpty()) "none" else warnings.joinToString(" | ")),
                "Usage      : include(\"Fdtd2DElastic.jl\"); include(\"env.jl\"); include(\"source.jl\");",
                "             out = run_fdtd(grid, sources, bc, tmax; monopoles = monopoles,",
                "                 receivers = receivers, pml_width = pml_cells, check_f_max = check_f_max)",
            ),
        )
        val sb = StringBuilder(hdr.length + sl.bathy.size * 70 + 2048)
        sb.append(hdr)
        sb.append("dx = ").append(f(d.dx)).append('\n')
        sb.append("dz = ").append(f(d.dz)).append('\n')
        sb.append("xmax = ").append(f(d.xmax)).append('\n')
        sb.append("zmax = ").append(f(d.zmax)).append('\n')
        sb.append("water_depth = ").append(f(sl.bathyMax)).append('\n')
        sb.append("c_water,  rho_water  = ").append(f(BottomClasses.WATER.vp)).append(", ").append(f(BottomClasses.WATER.rho)).append('\n')
        sb.append("c_bottom, rho_bottom = ").append(f(BottomClasses.BASEMENT.vp)).append(", ").append(f(BottomClasses.BASEMENT.rho)).append('\n')
        sb.append("Qp_water, Qp_bottom  = ").append(f(BottomClasses.WATER.qp)).append(", ").append(f(BottomClasses.BASEMENT.qp)).append('\n')
        sb.append("vs_bottom = ").append(f(BottomClasses.BASEMENT.vs)).append('\n')
        sb.append('\n')
        sb.append("nx = ").append(d.nx).append('\n')
        sb.append("nz = ").append(d.nz).append('\n')
        sb.append("pml_cells = ").append(PML_CELLS).append('\n')
        sb.append("x = collect(0.0:dx:(nx - 1) * dx)\n")
        sb.append("z = collect(0.0:dz:(nz - 1) * dz)\n")
        sb.append('\n')
        sb.append("# per-column environment: water depth, sediment base and geoacoustic\n")
        sb.append("# class properties (Hamilton 1980 style surface values)\n")
        sb.append(fmtArr("bathy", sl.bathy))
        sb.append(fmtArr("sed_base", sl.sedBase))
        sb.append(fmtArr("sed_vp", DoubleArray(sl.sed.size) { sl.sed[it].vp }))
        sb.append(fmtArr("sed_vs", DoubleArray(sl.sed.size) { sl.sed[it].vs }))
        sb.append(fmtArr("sed_rho", DoubleArray(sl.sed.size) { sl.sed[it].rho }))
        sb.append(fmtArr("sed_Qp", DoubleArray(sl.sed.size) { sl.sed[it].qp }))
        sb.append(fmtArr("sed_Qs", DoubleArray(sl.sed.size) { sl.sed[it].qs }))
        sb.append('\n')
        sb.append("lam = Array{Float64}(undef, nx, nz)\n")
        sb.append("mu  = Array{Float64}(undef, nx, nz)\n")
        sb.append("rho = Array{Float64}(undef, nx, nz)\n")
        sb.append("Qp  = Array{Float64}(undef, nx, nz)\n")
        sb.append("Qs  = Array{Float64}(undef, nx, nz)\n")
        sb.append("for i in 1:nx\n")
        sb.append("    b  = Float64(bathy[i])\n")
        sb.append("    sb = Float64(sed_base[i])\n")
        sb.append("    vp = Float64(sed_vp[i]); vs0 = Float64(sed_vs[i]); r = Float64(sed_rho[i])\n")
        sb.append("    qp = Float64(sed_Qp[i]); qs = Float64(sed_Qs[i])\n")
        sb.append("    for k in 1:nz\n")
        sb.append("        zz = z[k]\n")
        sb.append("        if zz < b\n")
        sb.append("            # water column\n")
        sb.append("            lam[i, k] = rho_water * c_water^2;  mu[i, k] = 0.0\n")
        sb.append("            rho[i, k] = rho_water;            Qp[i, k] = Qp_water;  Qs[i, k] = 0.0\n")
        sb.append("        elseif zz <= sb\n")
        sb.append("            # classified sediment\n")
        sb.append("            lam[i, k] = r * (vp^2 - 2.0 * vs0^2);  mu[i, k] = r * vs0^2\n")
        sb.append("            rho[i, k] = r;  Qp[i, k] = qp;  Qs[i, k] = qs\n")
        sb.append("        else\n")
        sb.append("            # basement half-space (land columns too)\n")
        sb.append("            lam[i, k] = rho_bottom * (c_bottom^2 - 2.0 * vs_bottom^2);  mu[i, k] = rho_bottom * vs_bottom^2\n")
        sb.append("            rho[i, k] = rho_bottom;  Qp[i, k] = Qp_bottom;  Qs[i, k] = 60.0\n")
        sb.append("        end\n")
        sb.append("    end\n")
        sb.append("end\n")
        sb.append('\n')
        sb.append("grid = ElasticGrid(nx, nz, dx, dz, lam, mu, rho, Qp, Qs)\n")
        return sb.toString()
    }

    // ------------------------------------------------------------- source.jl

    private fun writeSource(
        shot: FdtdShot, receivers: List<FdtdReceiver>, sl: SliceArrays, d: Dims,
        sourceX: Double, ix: Int, iz: Int, recX: DoubleArray, warnings: List<String>,
    ): String {
        val kind = if (shot.waveform == FdtdWaveform.HANN) "Hann pulse" else "Ricker (derivative)"
        val header = headerComment(
            "source.jl — Fdtd2DElastic acquisition exported by BathyApp",
            listOf(
                "Shot f0 = ${f(shot.f0)} Hz, $kind, amplitude ${f(shot.amp)} Pa/s, depth ${f(shot.depthM)} m.",
                "Compatible contract: f0, source_kind, src_amp, source_x, source_z, check_f_max,",
                "ix, iz (Int), sources, monopoles, bc, receivers. Sizes refer to env.jl.",
                "Warning(s): " + (if (warnings.isEmpty()) "none" else warnings.joinToString(" | ")),
            ),
        )
        val sb = StringBuilder(header.length + 2048)
        sb.append(header)
        sb.append("f0          = ").append(f(shot.f0)).append('\n')
        sb.append("source_kind = \"BathyApp ").append(kind).append(" ${f(shot.f0)} Hz\"\n")
        sb.append("src_amp     = ").append(f(shot.amp)).append('\n')
        sb.append("source_x = ").append(f(sourceX)).append('\n')
        sb.append("source_z = ").append(f(shot.depthM)).append('\n')
        sb.append("check_f_max = ").append(f(2.0 * shot.f0)).append('\n')
        sb.append('\n')
        sb.append("ix = ").append(ix).append("   # 1-based, Int(round(source_x / dx)) + 1\n")
        sb.append("iz = ").append(iz).append("   # 1-based, Int(round(source_z / dz)) + 1\n")
        sb.append('\n')
        if (shot.waveform == FdtdWaveform.HANN) {
            sb.append("T_pulse = 4.0 / f0\n")
            sb.append("function pulse_rate(t)\n")
            sb.append("    (t > 0.0 && t <= T_pulse) || return 0.0\n")
            sb.append("    w  = 0.5 * (1.0 - cos(2.0*pi*t / T_pulse))\n")
            sb.append("    dw = (pi / T_pulse) * sin(2.0*pi*t / T_pulse)\n")
            sb.append("    return src_amp * (2.0*pi*f0*cos(2.0*pi*f0*t)*w + sin(2.0*pi*f0*t)*dw)\n")
            sb.append("end\n")
        } else {
            sb.append("t_rick = 1.0 / f0\n")
            sb.append("function pulse_rate(t)\n")
            sb.append("    # Ricker wavelet derivative (injected rate): d/dt [(1 - 2*(pi*f0*(t-t_r))^2) * exp(-(pi*f0*(t-t_r))^2)]\n")
            sb.append("    tau = pi * f0 * (t - t_rick)\n")
            sb.append("    return src_amp * (-2.0 * pi * f0 * tau) * exp(-tau * tau) * (3.0 - 2.0 * tau * tau)\n")
            sb.append("end\n")
        }
        sb.append('\n')
        sb.append("sources   = Source[]\n")
        sb.append("monopoles = MonopoleSource[MonopoleSource(ix, iz, pulse_rate)]\n")
        sb.append("bc = BC(:pml, :pml, :free, :pml)\n")
        sb.append('\n')
        sb.append("# receiver positions as metres first, then converted to Int indices (1-based)\n")
        sb.append(fmtArr("receiver_x", recX))
        sb.append(fmtArr("receiver_z", DoubleArray(receivers.size) { receivers[it].depthM }))
        sb.append("receivers = Receiver[Receiver(Int(round(rx / dx)) + 1, Int(round(rz / dz)) + 1)\n")
        sb.append("                  for (rx, rz) in zip(receiver_x, receiver_z)]\n")
        sb.append('\n')
        sb.append("SL_REF_RANGE = 20.0\n")
        sb.append("SL_REF_IS_RECEIVER = 0\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ helpers

    private fun normLon(lon: Double): Double {
        var l = (lon + 180.0) % 360.0
        if (l < 0) l += 360.0
        return l - 180.0
    }

    private fun toRad(d: Double): Double = d * PI / 180.0
    private fun toDeg(r: Double): Double = r * 180.0 / PI

    private fun f(v: Double): String = String.format(Locale.ROOT, "%.12g", v)

    private fun fmtArr(name: String, a: DoubleArray): String {
        val sb = StringBuilder(name.length + a.size * 10)
        sb.append(name).append(" = Float32[")
        for (i in a.indices) {
            if (i > 0) sb.append(", ")
            sb.append(f(a[i]))
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun headerComment(title: String, lines: List<String>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        for (l in lines) sb.append("# ").append(l).append('\n')
        sb.append('\n')
        return sb.toString()
    }
}
