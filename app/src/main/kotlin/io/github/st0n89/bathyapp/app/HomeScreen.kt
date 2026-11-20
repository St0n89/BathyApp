package io.github.st0n89.bathyapp.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs
import io.github.st0n89.bathyapp.core.geo.Coastlines
import io.github.st0n89.bathyapp.core.geo.GlobeMath
import io.github.st0n89.bathyapp.core.geo.LandRaster
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val Accent = Color(0xFF3FA9D9)
private val GridBox = Color(0xFF7CFC9A)

/** Selection scale limits for the size slider (6° x 4° at k = 1). */
private const val K_MIN = 3.33e-4
private const val K_MAX = 20.0

/** Globe zoom bounds (0.8 fits the whole disc, 400 is enough for ~1 km areas). */
private const val ZOOM_MIN = 0.8f
private const val ZOOM_MAX = 400f

/** Land/sea mask raster resolution (equirectangular). */
private const val MASK_W = 1440
private const val MASK_H = 720

/** Pixel budget for the shaded-sphere bitmap (rendered each frame). */
private const val SHADE_PIXELS = 160_000

@Composable
fun HomeScreen(vm: AppViewModel, state: AppUiState) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.openLocalFile(context, uri) }

    // ---- selection bbox from state ----
    val w = state.west.toDoubleOrNull() ?: 3.0
    val s = state.south.toDoubleOrNull() ?: 53.5
    val e = state.east.toDoubleOrNull() ?: 9.0
    val n = state.north.toDoubleOrNull() ?: 57.5
    val selCLon = GlobeMath.normLon((w + e) / 2.0)
    val selCLat = GlobeMath.normLat((s + n) / 2.0)
    val spanLon = (e - w).coerceAtLeast(0.002)
    val spanLat = (n - s).coerceAtLeast(0.002)

    // ---- globe view state ----
    var centerLon by remember { mutableStateOf(selCLon) }
    var centerLat by remember { mutableStateOf(selCLat) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var showCoords by remember { mutableStateOf(false) }
    var lastTapUp by remember { mutableStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    // Live snapshots for the gesture handler: pointerInput keeps the closure
    // it was created with, but MutableState reads always see current values.
    var snapW by remember { mutableStateOf(w) }
    var snapS by remember { mutableStateOf(s) }
    var snapE by remember { mutableStateOf(e) }
    var snapN by remember { mutableStateOf(n) }
    var snapCLat by remember { mutableStateOf(selCLat) }
    var snapCLon by remember { mutableStateOf(selCLon) }
    var loadingActive by remember { mutableStateOf(state.loading) }
    LaunchedEffect(state.west, state.east, state.south, state.north, state.loading) {
        snapW = w; snapS = s; snapE = e; snapN = n
        snapCLat = selCLat; snapCLon = selCLon
        loadingActive = state.loading
    }

    val coasts = remember {
        runCatching { context.assets.open("coast_50m.txt").use { Coastlines.read(it) } }.getOrNull()
    }
    val landMask = remember {
        val land = runCatching { context.assets.open("land_50m.txt").use { Coastlines.read(it, "NELAND") } }.getOrNull()
        if (land == null) null else LandRaster.rasterize(land, MASK_W, MASK_H)
    }

    // Shaded sphere: half-resolution android bitmap redrawn when the view changes.
    val shadeBuf = remember(canvasSize) {
        if (canvasSize.width < 2 || canvasSize.height < 2) null
        else {
            var sw = canvasSize.width / 2
            var sh = canvasSize.height / 2
            val pxCount = sw * sh
            if (pxCount > SHADE_PIXELS) {
                val f = kotlin.math.sqrt(SHADE_PIXELS.toDouble() / pxCount)
                sw = (sw * f).toInt().coerceAtLeast(8)
                sh = (sh * f).toInt().coerceAtLeast(8)
            }
            ShadeBuffer(
                android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888),
                IntArray(sw * sh),
            )
        }
    }
    remember(landMask, shadeBuf, centerLat, centerLon, zoom) {
        val b = shadeBuf
        val m = landMask
        if (b != null && m != null) renderShadedSphere(b, m, MASK_W, MASK_H, centerLat, centerLon, zoom)
    }

    fun applyDrag(dxPx: Float, dyPx: Float) {
        val rPx = min(canvasSize.width, canvasSize.height) * 0.44f * zoom
        if (rPx <= 1f || canvasSize.width == 0) return
        val dLat = Math.toDegrees(dyPx.toDouble() / rPx)
        centerLat = (centerLat + dLat).coerceIn(-89.0, 89.0)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)
        centerLon = GlobeMath.normLon(centerLon - Math.toDegrees(dxPx.toDouble() / (rPx * cosLat)))
    }

    /** Zooms the globe toward a screen position (drag the point halfway to the centre, then scale). */
    fun zoomToward(px: Float, py: Float, factor: Float) {
        if (canvasSize.width == 0) return
        applyDrag((canvasSize.width / 2f - px) * 0.45f, (canvasSize.height / 2f - py) * 0.45f)
        zoom = (zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /** Smoothly rotates and zooms the globe onto the selection centre. */
    fun spinToSelection() {
        val lat = selCLat
        val lon = selCLon
        val targetZoom = (65.0 / maxOf(spanLon, 0.004)).coerceIn(ZOOM_MIN.toDouble(), ZOOM_MAX.toDouble())
        flingJob?.cancel()
        flingJob = scope.launch {
            val startLat = centerLat
            val startLon = centerLon
            val startZoom = zoom.toDouble()
            val dLat = lat - startLat
            val dLon = shortestLonDelta(startLon, lon)
            var t = 0f
            while (t < 1f) {
                withFrameNanos { t = min(1f, t + 0.07f) }
                val ease = (t * t * (3f - 2f * t)).toDouble()
                centerLat = startLat + dLat * ease
                centerLon = startLon + dLon * ease
                zoom = (startZoom + (targetZoom - startZoom) * ease).toFloat()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ================= globe =================
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(state.loading) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            flingJob?.cancel()
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // ---- geometry for this gesture (rPx fixed at down time) ----
                            val rPx = min(canvasSize.width, canvasSize.height) * 0.44f * zoom
                            val ccx = canvasSize.width / 2f
                            val ccy = canvasSize.height / 2f

                            fun projToPx(lat: Double, lon: Double): Offset? {
                                if (rPx <= 1f) return null
                                return GlobeMath.projectUnit(lat, lon, centerLat, centerLon)?.let {
                                    Offset(ccx + (it.first * rPx).toFloat(), ccy - (it.second * rPx).toFloat())
                                }
                            }

                            /** Unprojects a screen point, clamped to the visible disc. */
                            fun unproj(x: Float, y: Float): Pair<Double, Double>? {
                                if (rPx <= 1f) return null
                                var gx = (x - ccx).toDouble() / rPx
                                var gy = -(y - ccy).toDouble() / rPx
                                val rho = sqrt(gx * gx + gy * gy)
                                if (rho > 0.999) {
                                    val f = 0.999 / rho
                                    gx *= f; gy *= f
                                }
                                return GlobeMath.unprojectUnit(gx, gy, centerLat, centerLon)
                            }

                            // ---- classify the down event: globe / move box / corner resize ----
                            var mode = 0 // 0 globe, 1 move box, 2 corner resize
                            var grabLat = 0.0
                            var grabLon = 0.0
                            if (!loadingActive) {
                                val cLat0 = snapCLat
                                val cLon0 = snapCLon
                                val span0 = (snapE - snapW).coerceAtLeast(0.02)
                                val grabPx = 24.dp.toPx()
                                val cornerPx = listOf(
                                    projToPx(snapN, snapW), projToPx(snapN, snapE),
                                    projToPx(snapS, snapW), projToPx(snapS, snapE),
                                )
                                var best = grabPx
                                var hitCorner = -1
                                cornerPx.forEachIndexed { i, o ->
                                    if (o != null) {
                                        val d = (o - down.position).getDistance()
                                        if (d < best) {
                                            best = d; hitCorner = i
                                        }
                                    }
                                }
                                if (hitCorner >= 0) {
                                    mode = 2
                                } else {
                                    val ll = unproj(down.position.x, down.position.y)
                                    if (ll != null &&
                                        ll.first >= snapS - 1e-9 && ll.first <= snapN + 1e-9 &&
                                        abs(shortestLonDelta(cLon0, ll.second)) <= span0 / 2 + 1e-9
                                    ) {
                                        mode = 1
                                        grabLat = cLat0 - ll.first
                                        grabLon = shortestLonDelta(ll.second, cLon0)
                                    }
                                }
                                if (mode != 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }

                            val primaryId = down.id
                            var last = down.position
                            var lastT = down.uptimeMillis
                            var vx = 0.0
                            var vy = 0.0
                            var moved = 0f
                            var pinch = false
                            var prevSpread = -1f
                            var spread0 = -1f
                            var twoDownUptime = 0L
                            var tapPos = down.position
                            var upUptime = 0L

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    tapPos = event.changes.firstOrNull()?.position ?: last
                                    upUptime = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                                    break
                                }
                                if (loadingActive) continue
                                if (pressed.size >= 2 && mode == 0) {
                                    if (!pinch) {
                                        pinch = true
                                        twoDownUptime = pressed[0].uptimeMillis
                                    }
                                    val spread = (pressed[0].position - pressed[1].position).getDistance()
                                    if (spread0 < 0f) spread0 = spread
                                    if (prevSpread > 0f && spread > 0f) {
                                        zoom = (zoom * (spread / prevSpread)).coerceIn(ZOOM_MIN, ZOOM_MAX)
                                    }
                                    prevSpread = spread
                                } else if (pressed.size == 1 || mode != 0) {
                                    val pMove = event.changes.firstOrNull { it.id == primaryId && it.pressed }
                                        ?: break
                                    val p = pMove.position
                                    if (pinch) {
                                        prevSpread = -1f
                                    } else {
                                        val now = pMove.uptimeMillis
                                        val dx = p.x - last.x
                                        val dy = p.y - last.y
                                        moved += abs(dx) + abs(dy)
                                        when (mode) {
                                            0 -> applyDrag(dx, dy)
                                            1 -> unproj(p.x, p.y)?.let {
                                                vm.selectCenter(it.first + grabLat, it.second + grabLon)
                                            }
                                            2 -> unproj(p.x, p.y)?.let {
                                                val dLat = it.first - snapCLat
                                                val dLon = shortestLonDelta(snapCLon, it.second)
                                                vm.setSpans(2 * abs(dLon), 2 * abs(dLat))
                                            }
                                        }
                                        val dt = (now - lastT).coerceAtLeast(1)
                                        vx = 0.75 * vx + 0.25 * (dx / dt)
                                        vy = 0.75 * vy + 0.25 * (dy / dt)
                                        last = p
                                        lastT = now
                                    }
                                }
                            }

                            if (loadingActive || pinch) {
                                // quick two-finger tap with barely changed spread = zoom out
                                if (!loadingActive && pinch && spread0 > 0f && prevSpread > 0f &&
                                    abs(prevSpread - spread0) < 0.06f * spread0 &&
                                    upUptime - twoDownUptime in 1..350 && moved < slop * 2
                                ) {
                                    zoom = (zoom / 1.6f).coerceIn(ZOOM_MIN, ZOOM_MAX)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                return@awaitEachGesture
                            }

                            if (moved < slop && upUptime > 0L) {
                                if (upUptime - lastTapUp < 280L && (tapPos - lastTapPos).getDistance() < 80f) {
                                    // double tap: zoom toward the tapped point
                                    zoomToward(tapPos.x, tapPos.y, 1.55f)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (mode == 0) {
                                    // single tap on the globe: move the selection centre there
                                    val ll = unproj(tapPos.x, tapPos.y)
                                    if (ll != null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.selectCenter(ll.first, ll.second)
                                    }
                                }
                                lastTapUp = upUptime
                                lastTapPos = tapPos
                            } else if (mode == 0) {
                                // inertia fling
                                flingJob?.cancel()
                                flingJob = scope.launch {
                                    var ivx = vx
                                    var ivy = vy
                                    var lastNanos = withFrameNanos { it }
                                    while (abs(ivx) + abs(ivy) > 0.002) {
                                        val t = withFrameNanos { it }
                                        val dt = (t - lastNanos) / 1_000_000.0
                                        lastNanos = t
                                        if (dt in 0.1..100.0) {
                                            applyDrag((ivx * dt).toFloat(), (ivy * dt).toFloat())
                                            val f = 0.995.pow(dt / 16.7)
                                            ivx *= f
                                            ivy *= f
                                        }
                                    }
                                }
                            }
                        }
                    },
            ) {
                val cw = size.width
                val ch = size.height
                if (cw <= 0f || ch <= 0f) return@Canvas
                val cx = cw / 2f
                val cy = ch / 2f
                val r = min(cw, ch) * 0.44f * zoom
                val cLat = centerLat
                val cLon = centerLon

                fun px(lat: Double, lon: Double): Offset? {
                    val p = GlobeMath.projectUnit(lat, lon, cLat, cLon) ?: return null
                    val o = Offset(cx + (p.first * r).toFloat(), cy - (p.second * r).toFloat())
                    return if (o.x.isFinite() && o.y.isFinite()) o else null
                }

                // atmosphere glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x663FA9D9), Color(0x003FA9D9)),
                        center = Offset(cx, cy),
                        radius = r * 1.28f,
                    ),
                    radius = r * 1.28f,
                    center = Offset(cx, cy),
                )
                // ocean
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF12466E), Color(0xFF07131F)),
                        center = Offset(cx, cy - r * 0.25f),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )
                // limb
                drawCircle(
                    color = Color(0xFF3FA9D9).copy(alpha = 0.4f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )

                // ---- shaded land/sea sphere (stretched onto the disc) ----
                if (shadeBuf != null && landMask != null) {
                    drawImage(
                        image = shadeBuf.bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(shadeBuf.bmp.width, shadeBuf.bmp.height),
                        dstOffset = IntOffset((cx - r).toInt(), (cy - r).toInt()),
                        dstSize = IntSize((r * 2f).toInt().coerceAtLeast(1), (r * 2f).toInt().coerceAtLeast(1)),
                        filterQuality = FilterQuality.Medium,
                    )
                }

                // ---- graticule ----
                val gridPath = Path()
                val majorPath = Path()
                fun parallel(lat: Double, path: Path) {
                    var drawing = false
                    var lon = -180.0
                    while (lon <= 180.0) {
                        val o = px(lat, lon)
                        if (o == null) drawing = false
                        else if (drawing) path.lineTo(o.x, o.y)
                        else { path.moveTo(o.x, o.y); drawing = true }
                        lon += 3.0
                    }
                }
                fun meridian(lon: Double, path: Path) {
                    var drawing = false
                    var lat = -90.0
                    while (lat <= 90.0) {
                        val o = px(lat, lon)
                        if (o == null) drawing = false
                        else if (drawing) path.lineTo(o.x, o.y)
                        else { path.moveTo(o.x, o.y); drawing = true }
                        lat += 3.0
                    }
                }
                for (lat in -75..75 step 15) {
                    val d = lat.toDouble()
                    parallel(d, if (lat == 0) majorPath else gridPath)
                }
                for (lon in -180 until 180 step 15) {
                    val d = lon.toDouble()
                    meridian(d, if (lon == 0) majorPath else gridPath)
                }
                drawPath(gridPath, Color.White.copy(alpha = 0.13f), style = Stroke(0.8.dp.toPx()))
                drawPath(majorPath, Color.White.copy(alpha = 0.28f), style = Stroke(1.dp.toPx()))

                // ---- coastlines ----
                val coastPath = Path()
                for (lineArr in coasts?.polylines.orEmpty()) {
                    var drawing = false
                    var i = 0
                    while (i < lineArr.size) {
                        val lon = lineArr[i].toDouble()
                        val lat = lineArr[i + 1].toDouble()
                        val o = px(lat, lon)
                        if (o == null) drawing = false
                        else if (drawing) coastPath.lineTo(o.x, o.y)
                        else { coastPath.moveTo(o.x, o.y); drawing = true }
                        i += 2
                    }
                }
                drawPath(coastPath, Color(0xFFBFE3F5), style = Stroke(1.dp.toPx()))
                // limb darkening
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color(0x00000000),
                        0.62f to Color(0x0010202A),
                        1.0f to Color(0x8810202A),
                        center = Offset(cx, cy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx, cy),
                )

                // ---- loaded grid extent ----
                state.grid?.let { g ->
                    val boxPath = Path()
                    fun edge(fixed: Double, isLat: Boolean, from: Double, to: Double) {
                        var drawing = false
                        val steps = 24
                        for (i in 0..steps) {
                            val v = from + (to - from) * i / steps
                            val o = if (isLat) px(fixed, v) else px(v, fixed)
                            if (o == null) drawing = false
                            else if (drawing) boxPath.lineTo(o.x, o.y)
                            else { boxPath.moveTo(o.x, o.y); drawing = true }
                        }
                    }
                    edge(g.north, true, g.west, g.east)
                    edge(g.south, true, g.west, g.east)
                    edge(g.west, false, g.south, g.north)
                    edge(g.east, false, g.south, g.north)
                    drawPath(boxPath, GridBox, style = Stroke(1.5.dp.toPx()))
                }

                // ---- selection bbox ----
                val selPath = Path()
                fun selEdge(fixed: Double, isLat: Boolean, from: Double, to: Double) {
                    var drawing = false
                    val steps = 24
                    for (i in 0..steps) {
                        val v = from + (to - from) * i / steps
                        val o = if (isLat) px(fixed, v) else px(v, fixed)
                        if (o == null) drawing = false
                        else if (drawing) selPath.lineTo(o.x, o.y)
                        else { selPath.moveTo(o.x, o.y); drawing = true }
                    }
                }
                selEdge(n, true, w, e)
                selEdge(s, true, w, e)
                selEdge(w, false, s, n)
                selEdge(e, false, s, n)
                // faint fill while the whole box is on the visible side
                val fillCorners = listOf(px(n, w), px(n, e), px(s, e), px(s, w))
                if (fillCorners.all { it != null }) {
                    val fillPath = Path()
                    fillPath.moveTo(fillCorners[0]!!.x, fillCorners[0]!!.y)
                    for (i in 1..3) fillPath.lineTo(fillCorners[i]!!.x, fillCorners[i]!!.y)
                    fillPath.close()
                    drawPath(fillPath, Accent.copy(alpha = 0.10f))
                }
                drawPath(selPath, Accent, style = Stroke(2.dp.toPx()))
                // corner handles (draggable)
                for (corner in fillCorners) {
                    corner?.let {
                        drawCircle(Accent.copy(alpha = 0.35f), radius = 11.dp.toPx(), center = it)
                        drawCircle(Color.White, radius = 3.5.dp.toPx(), center = it)
                    }
                }
                // centre crosshair
                px(selCLat, selCLon)?.let { c ->
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
                    drawCircle(Accent, radius = 3.dp.toPx(), center = c)
                }
            }

            // recentre button
            TextButton(
                onClick = { spinToSelection() },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Zur Auswahl", style = MaterialTheme.typography.labelSmall)
            }

            // centre readout
            val kmLon = spanLon * 111.32 * cos(Math.toRadians(selCLat))
            val kmLat = spanLat * 110.574
            val kmFmt = if (maxOf(kmLon, kmLat) < 10.0) "%.2f × %.2f km" else "%.0f × %.0f km"
            Text(
                "%.4f°, %.4f°\n".format(selCLon, selCLat) + kmFmt.format(kmLon, kmLat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.End,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(10.dp),
            )

            // gestures hint
            Text(
                "Ziehen: Erde drehen · Box ziehen: verschieben · Ecken ziehen: Größe\n" +
                    "Tippen: Gebiet hierher · Doppeltipp: reinzoomen · Zwei-Finger-Tipp: rauszoomen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text(state.loadingNote ?: "Lade Daten …", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ================= control panel =================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.source == DataSource.EMODNET,
                    onClick = { vm.setSource(DataSource.EMODNET) },
                    label = { Text("EMODnet") },
                )
                FilterChip(
                    selected = state.source == DataSource.GEBCO,
                    onClick = { vm.setSource(DataSource.GEBCO) },
                    label = { Text("GEBCO") },
                )
                FilterChip(
                    selected = state.source == DataSource.LOCAL_FILE,
                    onClick = { vm.setSource(DataSource.LOCAL_FILE) },
                    label = { Text("Datei") },
                )
            }

            when (state.source) {
                DataSource.EMODNET -> {
                    OutlinedButton(
                        onClick = { vm.setCoverageIndex(state.coverageIndex + 1) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "Datensatz: ${EmodnetWcs.COVERAGES[state.coverageIndex].label}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    if (selCLat < 10.8 || selCLat > 84.6 || selCLon < -70.5 || selCLon > 43.0) {
                        Text(
                            "Mittelpunkt liegt außerhalb des EMODnet-Gebiets (Europa/Atlantik). Für Weltgebiete GEBCO wählen.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                DataSource.GEBCO -> Text(
                    (state.gebcoInfo ?: "Weltweite Abdeckung, 15″: der Server schneidet das Gebiet zu."),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
                DataSource.LOCAL_FILE -> {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text(if (state.localReady) "Andere Datei wählen" else "NetCDF/GeoTIFF wählen", style = MaterialTheme.typography.labelMedium)
                    }
                    if (state.localInfo != null) {
                        Text(state.localInfo!!, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
            }

            // size slider (log scale)
            val k = (spanLon / 6.0).coerceIn(K_MIN, K_MAX)
            val sliderPos = ((log10(k) - log10(K_MIN)) / (log10(K_MAX) - log10(K_MIN))).toFloat()
            val spanTxt = when {
                spanLon < 0.01 -> "%.4f°×%.4f°".format(spanLon, spanLat)
                spanLon < 1.0 -> "%.3f°×%.3f°".format(spanLon, spanLat)
                else -> "%.2f°×%.2f°".format(spanLon, spanLat)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gebiet", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderPos,
                    onValueChange = { p ->
                        val kk = 10.0.pow(log10(K_MIN) + p * (log10(K_MAX) - log10(K_MIN)))
                        vm.scaleSelection(kk)
                    },
                    onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    spanTxt,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 96.dp, height = 20.dp),
                )
            }

            // resolution slider
            val widthPx = state.widthPx.toIntOrNull() ?: 600
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pixel", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = widthPx.toFloat().coerceIn(100f, 2048f),
                    onValueChange = { vm.setWidthPx(it.toInt().coerceIn(100, 2048).toString()) },
                    onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    valueRange = 100f..2048f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$widthPx px",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 64.dp, height = 20.dp),
                )
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.loadGrid() },
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                ) { Text("Gitter laden") }
                OutlinedButton(onClick = { showCoords = true }) { Text("Koordinaten") }
                if (state.grid != null) {
                    OutlinedButton(onClick = { vm.goToMap() }) { Text("Karte") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showCoords) {
        AlertDialog(
            onDismissRequest = { showCoords = false },
            title = { Text("Gebiet & Auflösung") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("West (°)", state.west, 8, Modifier.weight(1f), vm::setWest)
                        NumberField("Ost (°)", state.east, 8, Modifier.weight(1f), vm::setEast)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Süd (°)", state.south, 8, Modifier.weight(1f), vm::setSouth)
                        NumberField("Nord (°)", state.north, 8, Modifier.weight(1f), vm::setNorth)
                    }
                    NumberField("Zielauflösung (px breit)", state.widthPx, 4, Modifier.fillMaxWidth(), vm::setWidthPx)
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoords = false }) { Text("OK") }
            },
        )
    }
}

/** Offscreen bitmap + pixel scratch for the shaded globe. */
private class ShadeBuffer(val bmp: android.graphics.Bitmap, val px: IntArray)

/**
 * Software-renders the visible hemisphere with per-pixel inverse orthographic projection,
 * a land/sea mask lookup and diffuse day-side shading (light from upper left).
 */
private fun renderShadedSphere(
    buf: ShadeBuffer,
    mask: ByteArray,
    maskW: Int,
    maskH: Int,
    lat0: Double,
    lon0: Double,
    zoom: Float,
) {
    val w = buf.bmp.width
    val h = buf.bmp.height
    val px = buf.px
    val p0 = lat0 * PI / 180.0
    val l0 = lon0 * PI / 180.0
    val sinP0 = sin(p0)
    val cosP0 = cos(p0)
    // Diffuse light direction in view space (unit): upper left, slightly toward viewer.
    val lx = -0.38
    val ly = 0.42
    val lz = 0.82
    // Base colors: deep ocean blue, dark sage green land.
    val oceanR = 13
    val oceanG = 34
    val oceanB = 61
    val landR = 86
    val landG = 100
    val landB = 78
    var i = 0
    for (jy in 0 until h) {
        // Normalized device coordinates on [-1, 1] with y pointing north.
        val gy = 1.0 - 2.0 * (jy + 0.5) / h
        for (ix in 0 until w) {
            val gx = 2.0 * (ix + 0.5) / w - 1.0
            val rr = gx * gx + gy * gy
            if (rr > 1.0) {
                px[i++] = 0 // transparent space
                continue
            }
            // Inverse orthographic in the (east, north, centre) view frame:
            // P = gx*E + gy*N + nz*C, hence the surface point has
            // sin(lat) = nz*sin(lat0) + gy*cos(lat0) and
            // dlon = atan2(gx, cos(lat0)*nz - sin(lat0)*gy). Exact at the centre too.
            val nz = sqrt((1.0 - rr).coerceAtLeast(0.0))
            val sinPhi = nz * sinP0 + gy * cosP0
            val phi = asin(sinPhi.coerceIn(-1.0, 1.0))
            val lam = l0 + atan2(gx, cosP0 * nz - sinP0 * gy)
            val latD = phi * 180.0 / PI
            val lonD = (lam * 180.0 / PI).let { x -> ((x + 540.0) % 360.0) - 180.0 }
            val mi = (((lonD + 180.0) / 360.0) * maskW).toInt().coerceIn(0, maskW - 1)
            val mj = (((90.0 - latD) / 180.0) * maskH).toInt().coerceIn(0, maskH - 1)
            val isLand = mask[mj * maskW + mi] != 0.toByte()
            // Surface normal in view space (towards the observer hemisphere); nz from above.
            val diff = (gx * lx + gy * ly + nz * lz).coerceAtLeast(0.0)
            val k = 0.42 + 0.58 * diff
            val r = ((if (isLand) landR else oceanR) * k).toInt().coerceIn(0, 255)
            val g = ((if (isLand) landG else oceanG) * k).toInt().coerceIn(0, 255)
            val b = ((if (isLand) landB else oceanB) * k).toInt().coerceIn(0, 255)
            px[i++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
    buf.bmp.setPixels(px, 0, w, 0, 0, w, h)
}

/** Shortest signed longitude delta from [from] to [to]. */
private fun shortestLonDelta(from: Double, to: Double): Double {
    var d = to - from
    while (d > 180.0) d -= 360.0
    while (d < -180.0) d += 360.0
    return d
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    maxChars: Int,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.take(maxChars)
            onChange(text)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
