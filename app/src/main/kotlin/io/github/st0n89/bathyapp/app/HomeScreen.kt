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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs
import io.github.st0n89.bathyapp.core.geo.Coastlines
import io.github.st0n89.bathyapp.core.geo.GlobeMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private val Accent = Color(0xFF3FA9D9)
private val GridBox = Color(0xFF7CFC9A)

/** Selection scale limits for the size slider (6° x 4° at k = 1). */
private const val K_MIN = 0.05
private const val K_MAX = 20.0

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
    val spanLon = (e - w).coerceAtLeast(0.02)
    val spanLat = (n - s).coerceAtLeast(0.02)

    // ---- globe view state ----
    var centerLon by remember { mutableStateOf(selCLon) }
    var centerLat by remember { mutableStateOf(selCLat) }
    var zoom by remember { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var showCoords by remember { mutableStateOf(false) }

    val coasts = remember {
        runCatching { context.assets.open("coast_50m.txt").use { Coastlines.read(it) } }.getOrNull()
    }

    fun applyDrag(dxPx: Float, dyPx: Float) {
        val rPx = min(canvasSize.width, canvasSize.height) * 0.44f * zoom
        if (rPx <= 1f || canvasSize.width == 0) return
        val dLat = Math.toDegrees(dyPx.toDouble() / rPx)
        centerLat = (centerLat + dLat).coerceIn(-89.0, 89.0)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)
        centerLon = GlobeMath.normLon(centerLon - Math.toDegrees(dxPx.toDouble() / (rPx * cosLat)))
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
                            var last = down.position
                            var lastT = down.uptimeMillis
                            var vx = 0.0
                            var vy = 0.0
                            var moved = 0f
                            var pinch = false
                            var prevSpread = -1f
                            var tapPos = last
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    tapPos = event.changes.firstOrNull()?.position ?: last
                                    break
                                }
                                if (state.loading) continue
                                if (pressed.size >= 2) {
                                    pinch = true
                                    val spread = (pressed[0].position - pressed[1].position).getDistance()
                                    if (prevSpread > 0f && spread > 0f) {
                                        zoom = (zoom * (spread / prevSpread)).coerceIn(0.8f, 2.6f)
                                    }
                                    prevSpread = spread
                                } else {
                                    prevSpread = -1f
                                    val now = event.changes[0].uptimeMillis
                                    val p = event.changes[0].position
                                    val dx = p.x - last.x
                                    val dy = p.y - last.y
                                    moved += abs(dx) + abs(dy)
                                    if (!pinch) applyDrag(dx, dy)
                                    val dt = (now - lastT).coerceAtLeast(1)
                                    vx = 0.75 * vx + 0.25 * (dx / dt)
                                    vy = 0.75 * vy + 0.25 * (dy / dt)
                                    last = p
                                    lastT = now
                                }
                            }
                            if (!pinch && moved < slop && !state.loading) {
                                // tap: set the selection centre
                                val rPx = min(canvasSize.width, canvasSize.height) * 0.44f * zoom
                                if (rPx > 1f) {
                                    val cx = canvasSize.width / 2f
                                    val cy = canvasSize.height / 2f
                                    val gx = (tapPos.x - cx) / rPx
                                    val gy = -(tapPos.y - cy) / rPx
                                    globeMathTap@ {
                                        val ll = GlobeMath.unprojectUnit(gx.toDouble(), gy.toDouble(), centerLat, centerLon) ?: return@globeMathTap
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        vm.selectCenter(ll.first, ll.second)
                                        // gently rotate to the new centre
                                        scope.launch {
                                            var t = 0f
                                            val startLat = centerLat
                                            val startLon = centerLon
                                            while (t < 1f) {
                                                withFrameNanos { t = min(1f, t + 0.08f) }
                                                centerLat = startLat + (ll.first - startLat) * t
                                                centerLon = startLon + shortestLonDelta(startLon, ll.second) * t
                                            }
                                        }
                                    }
                                }
                            } else if (!pinch) {
                                // inertia
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
                drawPath(selPath, Accent, style = Stroke(2.dp.toPx()))
                // corner dots
                for (corner in listOf(px(n, w), px(n, e), px(s, w), px(s, e))) {
                    corner?.let { drawCircle(Accent, radius = 3.dp.toPx(), center = it) }
                }
                // centre crosshair
                px(selCLat, selCLon)?.let { c ->
                    drawCircle(Color.White, radius = 5.dp.toPx(), center = c)
                    drawCircle(Accent, radius = 3.dp.toPx(), center = c)
                }
            }

            // small centre readout
            Text(
                "%.2f°, %.2f°".format(selCLon, selCLat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )

            // gestures hint
            Text(
                "Ziehen: Erde drehen · Pinch: Zoom · Tippen: Gebiet setzen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
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
                DataSource.EMODNET -> OutlinedButton(
                    onClick = { vm.setCoverageIndex(state.coverageIndex + 1) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Datensatz: ${EmodnetWcs.COVERAGES[state.coverageIndex].label}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Gebiet", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderPos,
                    onValueChange = { p ->
                        val kk = 10.0.pow(log10(K_MIN) + p * (log10(K_MAX) - log10(K_MIN)))
                        vm.scaleSelection(kk)
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.1f°×%.1f°".format(spanLon, spanLat),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 56.dp, height = 20.dp),
                )
            }

            // resolution slider
            val widthPx = state.widthPx.toIntOrNull() ?: 600
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pixel", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = widthPx.toFloat().coerceIn(100f, 2048f),
                    onValueChange = { vm.setWidthPx(it.toInt().coerceIn(100, 2048).toString()) },
                    valueRange = 100f..2048f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$widthPx px",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 56.dp, height = 20.dp),
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
