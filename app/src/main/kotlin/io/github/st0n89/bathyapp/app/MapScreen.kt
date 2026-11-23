package io.github.st0n89.bathyapp.app

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.st0n89.bathyapp.core.geo.Colormap
import io.github.st0n89.bathyapp.core.seismic.BottomClass
import io.github.st0n89.bathyapp.core.seismic.FdtdSlice
import io.github.st0n89.bathyapp.core.seismic.FdtdWaveform
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ---- palette ---------------------------------------------------------------

private val MarkerA = Color(0xFFE53935)
private val MarkerB = Color(0xFFFFC107)
private val MarkerShot = Color(0xFFE53935)
private val MarkerRecv = Color(0xFF00E5FF)
private val WaterCol = Color(0xFF4FA3E3)
private val MudCol = Color(0xFF8D6E63)
private val SandCol = Color(0xFFC9A96A)
private val RockCol = Color(0xFF78909C)
private val BasementCol = Color(0xFF37474F)
private val PreviewBg = Color(0xFF1D262E)

/** What the marker editor sheet currently edits. */
private sealed interface EditTarget {
    data object Shot : EditTarget
    data class Rec(val index: Int) : EditTarget
}

// ===========================================================================
// Map screen
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: AppViewModel, state: AppUiState) {
    val grid = state.grid ?: return
    val density = LocalDensity.current
    val context = LocalContext.current

    val image = remember(grid) {
        val pixels = Colormap.toArgbPixels(grid)
        val bmp = Bitmap.createBitmap(grid.nx, grid.ny, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, grid.nx, 0, 0, grid.nx, grid.ny)
        bmp.asImageBitmap()
    }

    var scale by remember(grid) { mutableFloatStateOf(1f) }
    var pan by remember(grid) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // survey interaction state (local: placement/drag/selection is UI-only)
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var surveySheet by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf<EditTarget?>(null) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }

    // fresh references inside stable gesture lambdas (no restart on state change)
    val curState by rememberUpdatedState(state)
    val curVm by rememberUpdatedState(vm)

    val stats = remember(grid) { grid.stats() }

    val baseCell = if (canvasSize.width == 0 || canvasSize.height == 0) 1f
    else min(canvasSize.width.toFloat() / grid.nx, canvasSize.height.toFloat() / grid.ny)
    val cell = baseCell * scale
    val ox = canvasSize.width / 2f - grid.nx * cell / 2f + pan.x
    val oy = canvasSize.height / 2f - grid.ny * cell / 2f + pan.y

    fun toPixel(lon: Double, lat: Double): Offset = Offset(
        (ox + ((lon - grid.west) / grid.dx - 0.5) * cell).toFloat(),
        (oy + ((grid.north - lat) / grid.dy - 0.5) * cell).toFloat(),
    )

    /** Live geometry (event-time) for the gesture handlers: (ox, oy, cell). */
    fun geom(): Triple<Float, Float, Float> {
        val cs = canvasSize
        val b = if (cs.width == 0 || cs.height == 0) 1f
        else min(cs.width.toFloat() / grid.nx, cs.height.toFloat() / grid.ny)
        val c = b * scale
        val x0 = cs.width / 2f - grid.nx * c / 2f + pan.x
        val y0 = cs.height / 2f - grid.ny * c / 2f + pan.y
        return Triple(x0, y0, c)
    }

    fun pixelOf(lon: Double, lat: Double, x0: Float, y0: Float, c: Float): Offset = Offset(
        (x0 + ((lon - grid.west) / grid.dx - 0.5) * c).toFloat(),
        (y0 + ((grid.north - lat) / grid.dy - 0.5) * c).toFloat(),
    )

    /** lon/lat under an offset, clamped into the loaded grid. */
    fun toLonLatClamped(p: Offset): Pair<Double, Double> {
        val (x0, y0, c) = geom()
        val col = ((p.x - x0) / c).coerceIn(0f, (grid.nx - 1).toFloat())
        val row = ((p.y - y0) / c).coerceIn(0f, (grid.ny - 1).toFloat())
        return (grid.west + (col + 0.5) * grid.dx) to (grid.north - (row + 0.5) * grid.dy)
    }

    fun hitTarget(p: Offset): EditTarget? {
        val s = curState
        val (x0, y0, c) = geom()
        val slop = 30f * density.density
        s.shot?.let {
            if ((pixelOf(it.lon, it.lat, x0, y0, c) - p).getDistance() <= slop) return EditTarget.Shot
        }
        for ((i, r) in s.receivers.withIndex()) {
            if ((pixelOf(r.lon, r.lat, x0, y0, c) - p).getDistance() <= slop) return EditTarget.Rec(i)
        }
        return null
    }

    /** Geographic bbox currently visible in the canvas (world-clamped). */
    fun visibleBox(): DoubleArray {
        val lonAtWest = grid.west + ((0f - ox) / cell + 0.5) * grid.dx
        val lonAtEast = grid.west + ((canvasSize.width - ox) / cell + 0.5) * grid.dx
        val latAtTop = grid.north - ((0f - oy) / cell + 0.5) * grid.dy
        val latAtBottom = grid.north - ((canvasSize.height - oy) / cell + 0.5) * grid.dy
        var w = lonAtWest.coerceIn(-180.0, 180.0)
        var e = lonAtEast.coerceIn(-180.0, 180.0)
        var s = latAtBottom.coerceIn(-89.0, 89.0)
        var n = latAtTop.coerceIn(-89.0, 89.0)
        if (e - w < 0.02) { w -= 0.01; e += 0.01 }
        if (n - s < 0.02) { s -= 0.01; n += 0.01 }
        return doubleArrayOf(w, s, e, n)
    }

    val vis = if (canvasSize.width > 0 && canvasSize.height > 0) visibleBox() else null
    val epsLon = (grid.east - grid.west) * 0.01
    val epsLat = (grid.north - grid.south) * 0.01
    val beyondGrid = vis != null && (
        vis[0] < grid.west - epsLon || vis[2] > grid.east + epsLon ||
            vis[1] < grid.south - epsLat || vis[3] > grid.north + epsLat
        )

    // ---- SAF launchers ----------------------------------------------------

    val geoTiffLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { vm.exportGeoTiff(context, it) } }
    val gridCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportGridCsv(context, it) } }
    val sourceJuliaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { vm.exportSourceJulia(context, it) } }
    val envJuliaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { vm.exportEnvJulia(context, it) }
        sourceJuliaLauncher.launch("source.jl")
    }

    fun launchFdtdExport() {
        if (vm.prepFdtdExport()) envJuliaLauncher.launch("env.jl")
    }

    // ---- snackbar for errors/notes ----------------------------------------

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            curVm.clearError()
        }
    }

    // ---- back button closes sheets first ----------------------------------

    BackHandler(enabled = editTarget != null || surveySheet) {
        editTarget = null
        surveySheet = false
    }

    when (editTarget) {
        is EditTarget.Shot -> {
            if (state.shot != null) {
                ShotSheet(vm, state, onDismiss = { editTarget = null })
            } else {
                LaunchedEffect(Unit) { editTarget = null }
            }
        }
        is EditTarget.Rec -> {
            val idx = (editTarget as EditTarget.Rec).index
            if (idx in state.receivers.indices) {
                ReceiverSheet(vm, state, idx, onDismiss = { editTarget = null })
            } else {
                LaunchedEffect(Unit) { editTarget = null }
            }
        }
        null -> {}
    }
    if (surveySheet) {
        SurveySheet(
            vm = vm,
            state = state,
            onDismiss = { surveySheet = false },
            onExport = { surveySheet = false; launchFdtdExport() },
            onGeoTiff = { geoTiffLauncher.launch("bathyapp_grid.tif") },
            onGridCsv = { gridCsvLauncher.launch("bathyapp_grid.csv") },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ---- canvas: bathy raster, markers, gestures -------------------

        val moveSlopPx = with(density) { 14.dp.toPx() }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                // 1. marker grab/drag: runs FIRST so it can consume the gesture
                .pointerInput(grid) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val target = hitTarget(down.position) ?: return@awaitEachGesture
                        val slopPx = moveSlopPx
                        // marker grabbed: own the whole gesture (blocks map pan)
                        down.consume()
                        var moved = false
                        dragTarget = target
                        dragPos = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!moved) editTarget = target
                                break
                            }
                            if (!moved && (change.position - down.position).getDistance() > slopPx) {
                                moved = true
                            }
                            if (moved) {
                                val (lon, lat) = toLonLatClamped(change.position)
                                when (target) {
                                    is EditTarget.Shot -> curVm.moveShot(lon, lat)
                                    is EditTarget.Rec -> curVm.moveReceiver(target.index, lon, lat)
                                }
                            }
                            dragPos = change.position
                            change.consume()
                        }
                        dragTarget = null
                        dragPos = null
                    }
                }
                // 2. placement taps (modes other than Karte)
                .pointerInput(grid) {
                    detectTapGestures { tap ->
                        val s = curState
                        val cs = canvasSize
                        if (cs.width <= 0 || cs.height <= 0) return@detectTapGestures
                        val b = min(cs.width.toFloat() / grid.nx, cs.height.toFloat() / grid.ny)
                        val c = b * scale
                        val x0 = cs.width / 2f - grid.nx * c / 2f + pan.x
                        val y0 = cs.height / 2f - grid.ny * c / 2f + pan.y
                        val col = (tap.x - x0) / c
                        val row = (tap.y - y0) / c
                        if (col < 0f || col > grid.nx - 1f || row < 0f || row > grid.ny - 1f) return@detectTapGestures
                        val lon = grid.west + (col + 0.5f) * grid.dx
                        val lat = grid.north - (row + 0.5f) * grid.dy
                        when (s.fdtdMode) {
                            FdtdPlacementMode.SOURCE -> {
                                curVm.placeShot(lon, lat)
                                curVm.setFdtdMode(FdtdPlacementMode.MAP)
                            }
                            FdtdPlacementMode.RECEIVER -> curVm.addReceiver(lon, lat)
                            FdtdPlacementMode.AB -> curVm.setPoint(lon, lat)
                            FdtdPlacementMode.MAP -> { /* marker taps handled by the drag detector */ }
                        }
                    }
                }
                // 3. pan + pinch zoom
                .pointerInput(Unit) {
                    detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                        scale = (scale * gestureZoom).coerceIn(1f, 16f)
                        pan += gesturePan
                    }
                },
        ) {
            drawImage(
                image = image,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize((grid.nx * cell).roundToInt() + 1, (grid.ny * cell).roundToInt() + 1),
                filterQuality = FilterQuality.Low,
            )
            drawRect(
                color = Color(0xFF37474F),
                topLeft = Offset(ox, oy),
                size = Size(grid.nx * cell, grid.ny * cell),
                style = Stroke(width = 2f),
            )

            val a = state.pointA
            val b = state.pointB
            if (state.fdtdMode == FdtdPlacementMode.AB && a != null && b != null) {
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = toPixel(a[0], a[1]),
                    end = toPixel(b[0], b[1]),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                )
            }
            if (state.fdtdMode == FdtdPlacementMode.AB) {
                a?.let {
                    val p = toPixel(it[0], it[1])
                    drawCircle(Color.White, radius = 14f, center = p)
                    drawCircle(MarkerA, radius = 9f, center = p)
                }
                b?.let {
                    val p = toPixel(it[0], it[1])
                    drawCircle(Color.White, radius = 14f, center = p)
                    drawCircle(MarkerB, radius = 9f, center = p)
                }
            }

            // shot -> receiver rays
            val shotP = state.shot?.let { toPixel(it.lon, it.lat) }
            shotP?.let { sp ->
                for (r in state.receivers) {
                    drawLine(
                        color = MarkerRecv.copy(alpha = 0.75f),
                        start = sp,
                        end = toPixel(r.lon, r.lat),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }
            }

            // receivers with labels
            for ((i, r) in state.receivers.withIndex()) {
                val p = toPixel(r.lon, r.lat)
                drawCircle(Color.White, radius = 13f, center = p)
                drawCircle(MarkerRecv, radius = 8f, center = p)
                drawText("R${i + 1}", p.x + 16f, p.y - 10f, 12.dp.toPx(), Color.White)
            }
            // shot with label
            state.shot?.let { sh ->
                val p = toPixel(sh.lon, sh.lat)
                drawCircle(Color.White, radius = 16f, center = p)
                drawCircle(MarkerShot, radius = 11f, center = p)
                drawText("Q", p.x - 4f, p.y + 6f, 14.dp.toPx(), Color.White)
            }

            // drag crosshair
            if (dragTarget != null && dragPos != null) {
                val dp = dragPos!!
                drawLine(
                    Color.White.copy(alpha = 0.55f),
                    Offset(dp.x - 28f, dp.y),
                    Offset(dp.x + 28f, dp.y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                drawLine(
                    Color.White.copy(alpha = 0.55f),
                    Offset(dp.x, dp.y - 28f),
                    Offset(dp.x, dp.y + 28f),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
        }

        // ---- top overlay: mode pill + hint ------------------------------

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = state.fdtdMode == FdtdPlacementMode.MAP,
                    onClick = { vm.setFdtdMode(FdtdPlacementMode.MAP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                ) { Text("Karte") }
                SegmentedButton(
                    selected = state.fdtdMode == FdtdPlacementMode.SOURCE,
                    onClick = { vm.setFdtdMode(FdtdPlacementMode.SOURCE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                ) { Text("Quelle") }
                SegmentedButton(
                    selected = state.fdtdMode == FdtdPlacementMode.RECEIVER,
                    onClick = { vm.setFdtdMode(FdtdPlacementMode.RECEIVER) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                ) { Text("Receiver") }
                SegmentedButton(
                    selected = state.fdtdMode == FdtdPlacementMode.AB,
                    onClick = { vm.setFdtdMode(FdtdPlacementMode.AB) },
                    shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                ) { Text("Profil") }
            }
            val hint = when (state.fdtdMode) {
                FdtdPlacementMode.SOURCE -> "Auf Wasser tippen: Quelle setzen (später verschieb- und antippbar)"
                FdtdPlacementMode.RECEIVER -> "Tippen: Receiver am Meeresboden (mehrere möglich)"
                FdtdPlacementMode.AB -> "1. Tipp = A, 2. Tipp = B, dann „Profil“ in der Übersicht"
                FdtdPlacementMode.MAP -> if (state.shot == null && state.receivers.isEmpty()) {
                    "Quelle und Receiver über die Pille setzen, Marker antippen zum Bearbeiten"
                } else null
            }
            hint?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        maxLines = 2,
                    )
                }
            }
        }

        // ---- loading overlay --------------------------------------------

        if (state.loading) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(state.loadingNote ?: "Lade Daten …", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // ---- bottom overlay: beyond-grid pill + survey dock -------------

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            if (beyondGrid && vis != null && !state.loading) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ) {
                    TextButton(onClick = { vm.loadVisibleGrid(vis[0], vis[1], vis[2], vis[3]) }) {
                        Text("Ausschnitt laden · %.2f°…%.2f°E, %.2f°…%.2f°N".format(vis[0], vis[2], vis[1], vis[3]))
                    }
                }
            }

            SurveyDock(
                vm = vm,
                state = state,
                stats = stats,
                onExport = { launchFdtdExport() },
                onOpenSheet = { surveySheet = true },
                onEditShot = { editTarget = EditTarget.Shot },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ===========================================================================
// Survey dock (persistent bottom bar)
// ===========================================================================

@Composable
private fun SurveyDock(
    vm: AppViewModel,
    state: AppUiState,
    stats: io.github.st0n89.bathyapp.core.geo.GeoGrid.Stats,
    onExport: () -> Unit,
    onOpenSheet: () -> Unit,
    onEditShot: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            // next-step guidance line
            Text(nextStep(state), style = MaterialTheme.typography.labelMedium)

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                FilterChip(
                    selected = state.shot != null,
                    onClick = { if (state.shot != null) onEditShot() else vm.setFdtdMode(FdtdPlacementMode.SOURCE) },
                    label = { Text(if (state.shot == null) "1 · Quelle" else "✓ Quelle") },
                )
                FilterChip(
                    selected = state.receivers.isNotEmpty(),
                    onClick = { if (state.receivers.isEmpty()) vm.setFdtdMode(FdtdPlacementMode.RECEIVER) else onOpenSheet() },
                    label = { Text(if (state.receivers.isEmpty()) "2 · Receiver" else "✓ ${state.receivers.size} Rx") },
                )
                FilterChip(
                    selected = state.sediment != null,
                    onClick = { if (state.sediment == null) vm.loadBottomType() else onOpenSheet() },
                    label = {
                        if (state.bottomLoading) Text("Meeresboden …")
                        else if (state.sediment == null) Text("3 · Meeresboden") else Text("✓ Sediment")
                    },
                )
                Box(Modifier.weight(1f))
                TextButton(onClick = onOpenSheet) { Text("Übersicht") }
            }

            Button(
                onClick = onExport,
                enabled = state.sediment != null && state.shot != null && state.receivers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("env.jl + source.jl exportieren") }

            // colorbar: Land (max) links ... Wasser (min) rechts
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtM(stats.max), style = MaterialTheme.typography.labelSmall)
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .padding(horizontal = 6.dp)
                ) {
                    val steps = 128
                    val minDepth = min(0f, stats.min)
                    val maxDepth = max(0f, stats.max)
                    for (i in 0 until steps) {
                        val e = stats.max - (stats.max - stats.min) * i / (steps - 1)
                        drawRect(
                            color = Color(Colormap.color(e, minDepth, maxDepth)),
                            topLeft = Offset(size.width * i / steps, 0f),
                            size = Size(size.width / steps + 1f, size.height),
                        )
                    }
                }
                Text(fmtM(stats.min), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun nextStep(state: AppUiState): String = when {
    state.shot == null -> "1. „Quelle“ wählen und auf Wasser tippen"
    state.receivers.isEmpty() -> "2. „Receiver“ wählen und auf Wasser tippen"
    state.sediment == null -> "3. „Meeresboden“ laden (einmalig, GlobSed-Datensatz)"
    else -> "Bereit: Marker verschieben zum Anpassen, dann exportieren"
}

// ===========================================================================
// Survey overview sheet
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SurveySheet(
    vm: AppViewModel,
    state: AppUiState,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onGeoTiff: () -> Unit,
    onGridCsv: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("FDTD-Survey", style = MaterialTheme.typography.titleLarge)
            Text(
                state.gridNote ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // live slice preview
            val sl = state.fdtdSlice
            if (sl != null) {
                SlicePreview(
                    sl = sl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PreviewBg),
                )
                Text(planChips(sl), style = MaterialTheme.typography.labelSmall)
                sl.plan.warnings.forEach {
                    Text(
                        "• $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9A6A00),
                    )
                }
            } else {
                Text(
                    previewMissing(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.sediment == null && !state.bottomLoading) {
                Button(onClick = { vm.loadBottomType() }, modifier = Modifier.fillMaxWidth()) {
                    Text("GlobSed-Meeresboden laden (einmalig, ~19 MB)")
                }
            }
            if (state.bottomLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Text(state.loadingNote ?: "Meeresboden …", style = MaterialTheme.typography.labelSmall)
                }
            }
            state.bottomInfo?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onExport,
                enabled = state.sediment != null && state.shot != null && state.receivers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("env.jl + source.jl exportieren") }

            if (state.shot != null || state.receivers.isNotEmpty()) {
                TextButton(onClick = { vm.clearAcquisition() }) { Text("Survey zurücksetzen (Quelle und Receiver)") }
            }

            // ---- legacy tools ---------------------------------------------
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Werkzeuge", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "A: ${state.pointA?.let { fmtPos(it) } ?: "–"} · B: ${state.pointB?.let { fmtPos(it) } ?: "–"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { vm.computeProfile() },
                            enabled = state.pointA != null && state.pointB != null,
                        ) { Text("Profil") }
                        AssistChip(onClick = onGeoTiff, label = { Text("GeoTIFF") })
                        AssistChip(onClick = onGridCsv, label = { Text("Gitter-CSV") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { vm.clearPoints() }, label = { Text("Punkte löschen") })
                        AssistChip(onClick = { vm.goHome() }, label = { Text("Start") })
                    }
                }
            }
        }
    }
}

private fun previewMissing(state: AppUiState): String = when {
    state.sediment == null -> "Für die 2-D-Vorschau fehlen die Meeresbodendaten (GlobSed, einmaliger Download)."
    state.shot == null -> "Für die 2-D-Vorschau fehlt die Quelle."
    state.receivers.isEmpty() -> "Für die 2-D-Vorschau fehlen Receiver."
    else -> "Vorschau wird berechnet …"
}

private fun planChips(sl: FdtdSlice): String {
    val p = sl.plan
    val c = p.classCounts
    return "Gitter %dx%d @ %.0f m · Linie %.1f km · max. Tiefe %.0f m · tmax ≈ %.0f s · mud/sand/rock %d/%d/%d".format(
        p.nx, p.nz, p.dx, p.lineLenM / 1000.0, p.bathyMaxM, p.tmaxHintS,
        c[BottomClass.MUD] ?: 0, c[BottomClass.SAND] ?: 0, c[BottomClass.ROCK] ?: 0,
    )
}

// ===========================================================================
// Slice preview canvas
// ===========================================================================

@Composable
private fun SlicePreview(sl: FdtdSlice, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = sl.classes.size
        if (n < 2 || h <= 0f || w <= 0f) return@Canvas
        val zScale = h / sl.zmaxM.toFloat().coerceAtLeast(0.1f)
        val step = max(1, n / 480)

        // layer bands
        var j = 0
        while (j < n) {
            val x = w * j / (n - 1)
            val bw = w * step / (n - 1) + 1f
            val bathy = sl.bathy[j].toFloat() * zScale
            val sedBase = sl.sedBase[j].toFloat() * zScale
            drawRect(WaterCol.copy(alpha = 0.8f), topLeft = Offset(x, 0f), size = Size(bw, bathy))
            when (sl.classes[j]) {
                BottomClass.MUD -> drawRect(MudCol, topLeft = Offset(x, bathy), size = Size(bw, max(0f, sedBase - bathy)))
                BottomClass.SAND -> drawRect(SandCol, topLeft = Offset(x, bathy), size = Size(bw, max(0f, sedBase - bathy)))
                BottomClass.ROCK -> {}
            }
            val baseTop = max(bathy, sedBase)
            drawRect(BasementCol, topLeft = Offset(x, baseTop), size = Size(bw, h - baseTop))
            j += step
        }

        // bathymetric accent line
        val path = Path()
        var i = 0
        while (i < n) {
            val x = w * i / (n - 1)
            val y = sl.bathy[i].toFloat() * zScale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            i += step
        }
        drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 2f))

        // shot + receivers
        val totalM = (n - 1) * sl.dx
        val pxShot = (w * (sl.shotXm / totalM).toFloat()).coerceIn(0f, w)
        val pyShot = (sl.shotDepthM.toFloat() * zScale).coerceIn(0f, h)
        drawCircle(MarkerShot, radius = 6f, center = Offset(pxShot, pyShot))
        drawCircle(Color.White, radius = 6f, center = Offset(pxShot, pyShot), style = Stroke(width = 2f))
        drawText("Q", pxShot - 5f, pyShot - 10f, 11.dp.toPx(), Color.White)
        for ((k, rx) in sl.recXm.withIndex()) {
            val x = (w * (rx / totalM).toFloat()).coerceIn(0f, w)
            val y = (sl.recDepthM[k].toFloat() * zScale).coerceIn(0f, h)
            drawCircle(MarkerRecv, radius = 5f, center = Offset(x, y))
            drawCircle(Color.White, radius = 5f, center = Offset(x, y), style = Stroke(width = 1.5f))
            drawText("R${k + 1}", x - 5f, y - 9f, 10.dp.toPx(), Color.White)
        }

        // axes
        val labelPx = 10.dp.toPx()
        drawText("0", 3f, h - 4f, labelPx, Color.White)
        drawText("%.0f km".format(sl.plan.lineLenM / 1000.0), w - 42f, h - 4f, labelPx, Color.White)
        drawText("%.0f m".format(sl.zmaxM), 3f, labelPx + 2f, labelPx, Color.White)
    }
}

// ===========================================================================
// Source / receiver editor sheets (sliders instead of dialogs)
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShotSheet(vm: AppViewModel, state: AppUiState, onDismiss: () -> Unit) {
    val shot = state.shot ?: return
    val floor = vm.seafloorDepthAt(shot.lon, shot.lat)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Seismische Quelle", style = MaterialTheme.typography.titleLarge)
            Text(
                "%.4f°, %.4f° · Wassersäule %.0f m".format(shot.lon, shot.lat, floor),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Tiefe: %.0f m".format(shot.depthM), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = shot.depthM.toFloat().coerceIn(1f, max(2f, (floor - 1f).toFloat())),
                onValueChange = { vm.updateShot(depthM = it.toDouble()) },
                valueRange = 1f..max(2f, (floor - 1f).toFloat()),
            )
            Text(
                "Wassersäule 1 bis %.0f m, aktuell %.1f m über Grund".format(floor - 1.0, floor - shot.depthM),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Frequenz: %.0f Hz".format(shot.f0), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = shot.f0.toFloat().coerceIn(2f, 250f),
                onValueChange = { vm.updateShot(f0 = it.toDouble()) },
                valueRange = 2f..250f,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10.0, 25.0, 50.0, 100.0).forEach { f ->
                    AssistChip(
                        onClick = { vm.updateShot(f0 = f) },
                        label = { Text("${f.toInt()}") },
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = shot.waveform == FdtdWaveform.HANN,
                    onClick = { vm.updateShot(waveform = FdtdWaveform.HANN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Hann-Puls") }
                SegmentedButton(
                    selected = shot.waveform == FdtdWaveform.RICKER,
                    onClick = { vm.updateShot(waveform = FdtdWaveform.RICKER) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Ricker") }
            }

            Text("Amplitude: %s Pa/s".format(fmtAmp(shot.amp)), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = (ln(shot.amp) / ln(10.0)).toFloat().coerceIn(1f, 6f),
                onValueChange = { vm.updateShot(amp = 10.0.pow(it.toDouble())) },
                valueRange = 1f..6f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { vm.deleteShot(); onDismiss() }) { Text("Entfernen") }
                Box(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Fertig") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverSheet(vm: AppViewModel, state: AppUiState, index: Int, onDismiss: () -> Unit) {
    val r = state.receivers.getOrNull(index) ?: return
    val floor = vm.seafloorDepthAt(r.lon, r.lat)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Receiver R${index + 1}", style = MaterialTheme.typography.titleLarge)
            Text(
                "%.4f°, %.4f° · Wassersäule %.0f m".format(r.lon, r.lat, floor),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Tiefe: %.0f m".format(r.depthM), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = r.depthM.toFloat().coerceIn(1f, max(2f, (floor - 1f).toFloat())),
                onValueChange = { vm.updateReceiverDepth(index, it.toDouble()) },
                valueRange = 1f..max(2f, (floor - 1f).toFloat()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = { vm.updateReceiverDepth(index, (floor - 1.0).coerceAtLeast(1.0)) },
                    label = { Text("Meeresboden") },
                )
                AssistChip(
                    onClick = { vm.updateReceiverDepth(index, (floor / 2.0).coerceIn(1.0, (floor - 1.0).coerceAtLeast(1.0))) },
                    label = { Text("Halbe Tiefe") },
                )
            }
            Text(
                "%.1f m über Grund".format(floor - r.depthM),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { vm.removeReceiver(index); onDismiss() }) { Text("Entfernen") }
                Box(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("Fertig") }
            }
        }
    }
}

// ===========================================================================
// helpers
// ===========================================================================

/** Small white label with a soft shadow, drawn on a Canvas. */
private fun DrawScope.drawText(text: String, x: Float, y: Float, sizePx: Float, color: Color) {
    val paint = Paint().apply {
        textSize = sizePx
        isAntiAlias = true
        setShadowLayer(3f, 1f, 1f, android.graphics.Color.argb(200, 0, 0, 0))
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun fmtAmp(v: Double): String = when {
    v >= 1.0e6 -> "%.0f M".format(v / 1.0e6)
    v >= 1.0e3 -> "%.0f k".format(v / 1.0e3)
    else -> "%.0f".format(v)
}

private fun fmtM(v: Float): String =
    if (v >= 0f) "+%.0f m".format(v) else "%.0f m".format(v)

private fun fmtPos(p: DoubleArray): String =
    "%.4f°, %.4f°".format(p[0], p[1])
