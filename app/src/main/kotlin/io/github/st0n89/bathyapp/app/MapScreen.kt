package io.github.st0n89.bathyapp.app

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.st0n89.bathyapp.core.geo.Colormap
import io.github.st0n89.bathyapp.core.seismic.FdtdWaveform
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MarkerA = Color(0xFFE53935)
private val MarkerB = Color(0xFFFFC107)
private val MarkerShot = Color(0xFFE53935)
private val MarkerRecv = Color(0xFF00E5FF)

@Composable
fun MapScreen(vm: AppViewModel, state: AppUiState) {
    val grid = state.grid ?: return
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

    // FDtd placement dialogs
    var pendingSource by remember { mutableStateOf<DoubleArray?>(null) }
    var pendingReceiver by remember { mutableStateOf<DoubleArray?>(null) }
    pendingSource?.let { point ->
        SourceDialog(
            initialDepth = (vm.waterDepthAt(point[0], point[1]) / 2.0).coerceAtLeast(20.0),
            onCancel = { pendingSource = null },
            onConfirm = { depth, f0, waveform, amp ->
                vm.placeShot(point[0], point[1], depth, f0, waveform, amp)
                pendingSource = null
            },
        )
    }
    pendingReceiver?.let { point ->
        ReceiverDialog(
            initialDepth = vm.seafloorDepthAt(point[0], point[1]),
            onCancel = { pendingReceiver = null },
            onConfirm = { depth ->
                vm.addReceiver(point[0], point[1], depth)
                pendingReceiver = null
            },
        )
    }

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
    // visible area extends beyond the loaded grid (with 1% slack)?
    val epsLon = (grid.east - grid.west) * 0.01
    val epsLat = (grid.north - grid.south) * 0.01
    val beyondGrid = vis != null && (
        vis[0] < grid.west - epsLon || vis[2] > grid.east + epsLon ||
            vis[1] < grid.south - epsLat || vis[3] > grid.north + epsLat
        )

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

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { canvasSize = it }
                .pointerInput(grid, state.fdtdMode, state.shot, state.receivers.size) {
                    detectTapGestures { tap ->
                        val cs = canvasSize
                        if (cs.width > 0 && cs.height > 0) {
                            val base = min(
                                cs.width.toFloat() / grid.nx,
                                cs.height.toFloat() / grid.ny,
                            )
                            val c = base * scale
                            val x0 = cs.width / 2f - grid.nx * c / 2f + pan.x
                            val y0 = cs.height / 2f - grid.ny * c / 2f + pan.y
                            val col = (tap.x - x0) / c
                            val row = (tap.y - y0) / c
                            if (col >= 0f && col <= grid.nx - 1f && row >= 0f && row <= grid.ny - 1f) {
                                val lon = grid.west + (col + 0.5f) * grid.dx
                                val lat = grid.north - (row + 0.5f) * grid.dy
                                when (state.fdtdMode) {
                                    FdtdPlacementMode.SOURCE -> pendingSource = doubleArrayOf(lon, lat)
                                    FdtdPlacementMode.RECEIVER -> pendingReceiver = doubleArrayOf(lon, lat)
                                    FdtdPlacementMode.AB -> vm.setPoint(lon, lat)
                                }
                            }
                        }
                    }
                }
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
            if (a != null && b != null) {
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = toPixel(a[0], a[1]),
                    end = toPixel(b[0], b[1]),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                )
            }
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
            val shot = state.shot
            if (shot != null) {
                val ps = toPixel(shot.lon, shot.lat)
                for (r in state.receivers) {
                    drawLine(
                        color = MarkerRecv.copy(alpha = 0.8f),
                        start = ps,
                        end = toPixel(r.lon, r.lat),
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    )
                }
                drawCircle(Color.White, radius = 16f, center = ps)
                drawCircle(MarkerShot, radius = 11f, center = ps)
            }
            for ((i, r) in state.receivers.withIndex()) {
                val p = toPixel(r.lon, r.lat)
                drawCircle(Color.White, radius = 13f, center = p)
                drawCircle(
                    if (i == state.selectedReceiver) MarkerShot else MarkerRecv,
                    radius = 8f, center = p,
                )
            }
        }

        // Ausschnitt laden, wenn über den Rand des geladenen Gitters hinausgepannt wurde
        if (beyondGrid && vis != null && !state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Außerhalb geladen: %.2f°…%.2f°E, %.2f°…%.2f°N".format(vis[0], vis[2], vis[1], vis[3]),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.loadVisibleGrid(vis[0], vis[1], vis[2], vis[3]) }) {
                    Text("Ausschnitt laden")
                }
            }
        }
        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    state.loadingNote ?: "Lade Daten …",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // -------- FDtd: Modus, Meeresboden, Receiver-Dropdown, Export --------
        fdtdAcquisitionRow(vm, state, onExport = {
            if (vm.prepFdtdExport()) envJuliaLauncher.launch("env.jl")
        })

        state.fdtdPlan?.let { p ->
            Text(
                "FDtd-Plan: ${p.nx}×${p.nz} @ ${"%.0f".format(p.dx)} m, Linie ${"%.1f".format(p.lineLenM / 1000.0)} km, " +
                    "Tiefe ${"%.0f".format(p.bathyMaxM)} m, mud/sand/rock = ${p.classCounts[io.github.st0n89.bathyapp.core.seismic.BottomClass.MUD] ?: 0}/" +
                    "${p.classCounts[io.github.st0n89.bathyapp.core.seismic.BottomClass.SAND] ?: 0}/${p.classCounts[io.github.st0n89.bathyapp.core.seismic.BottomClass.ROCK] ?: 0}" +
                    (if (p.coarsened) ", vergröbert" else "") +
                    ", tmax ≈ ${"%.1f".format(p.tmaxHintS)} s",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // colorbar: Land (max) links ... Wasser (min) rechts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "A: ${state.pointA?.let { fmtPos(it) } ?: "–"}",
                style = MaterialTheme.typography.labelSmall,
                color = MarkerA,
            )
            Text(
                "B: ${state.pointB?.let { fmtPos(it) } ?: "–"}",
                style = MaterialTheme.typography.labelSmall,
                color = MarkerB,
            )
            if (state.shot != null) {
                Text(
                    "Quelle: ${fmtPos(doubleArrayOf(state.shot.lon, state.shot.lat))}, ${state.shot.depthM.toInt()} m, ${state.shot.f0.toInt()} Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MarkerShot,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            when (state.fdtdMode) {
                FdtdPlacementMode.AB -> "Tippen setzt A, dann B. Pinch zoomt. Über den Rand ziehen und \"Ausschnitt laden\" tippen für neue Gebiete."
                FdtdPlacementMode.SOURCE -> "Tippen platziert die seismische Quelle (Tiefe/Wellenform im Dialog)."
                FdtdPlacementMode.RECEIVER -> "Tippen platziert einen Receiver (Tiefe im Dialog, Standard: Meeresboden)."
            },
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.computeProfile() },
                enabled = state.pointA != null && state.pointB != null,
            ) { Text("Profil") }
            OutlinedButton(onClick = { geoTiffLauncher.launch("bathyapp_grid.tif") }) { Text("GeoTIFF") }
            OutlinedButton(onClick = { gridCsvLauncher.launch("bathyapp_grid.csv") }) { Text("Gitter-CSV") }
            OutlinedButton(onClick = { vm.clearPoints() }) { Text("Punkte löschen") }
            OutlinedButton(onClick = { vm.goHome() }) { Text("Start") }
        }
        state.error?.let {
            Text(
                it,
                color = if (it.startsWith("Hinweis")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun fdtdAcquisitionRow(vm: AppViewModel, state: AppUiState, onExport: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = state.fdtdMode == FdtdPlacementMode.AB,
                onClick = { vm.setFdtdMode(FdtdPlacementMode.AB) },
                label = { Text("A/B") },
            )
            FilterChip(
                selected = state.fdtdMode == FdtdPlacementMode.SOURCE,
                onClick = { vm.setFdtdMode(FdtdPlacementMode.SOURCE) },
                label = { Text("Quelle") },
            )
            FilterChip(
                selected = state.fdtdMode == FdtdPlacementMode.RECEIVER,
                onClick = { vm.setFdtdMode(FdtdPlacementMode.RECEIVER) },
                label = { Text("Receiver") },
            )
            if (state.bottomLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(14.dp).width(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    state.bottomInfo ?: "Meeresboden …",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            } else {
                TextButton(onClick = { vm.loadBottomType() }) {
                    Text(if (state.sediment == null) "Meeresboden" else "✓ Sediment", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ReceiverDropdown(vm, state)
            TextButton(
                onClick = { vm.removeSelectedReceiver() },
                enabled = state.selectedReceiver in state.receivers.indices,
            ) { Text("Receiver löschen", style = MaterialTheme.typography.labelMedium) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onExport,
                enabled = state.sediment != null && state.shot != null && state.receivers.isNotEmpty(),
            ) { Text("FDTD-Export (env.jl + source.jl)") }
            if (state.shot != null || state.receivers.isNotEmpty()) {
                TextButton(onClick = { vm.clearAcquisition() }) { Text("Reset") }
            }
        }
        if (!state.bottomLoading) {
            state.bottomInfo?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverDropdown(vm: AppViewModel, state: AppUiState) {
    var expanded by remember { mutableStateOf(false) }
    val label = state.receivers.getOrNull(state.selectedReceiver)?.let {
        "R${state.selectedReceiver + 1}: %.3f°, %.3f° · ${it.depthM.toInt()} m".format(it.lon, it.lat)
    } ?: if (state.receivers.isEmpty()) "Receiver: keine" else "Receiver: ${state.receivers.size}"
    ExposedDropdownMenuBox(
        expanded = expanded && state.receivers.isNotEmpty(),
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Receiver wählen") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.labelMedium,
        )
        ExposedDropdownMenu(
            expanded = expanded && state.receivers.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            for ((i, r) in state.receivers.withIndex()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "R${i + 1}: %.3f°, %.3f° · ${r.depthM.toInt()} m".format(r.lon, r.lat),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    onClick = {
                        vm.selectReceiver(i)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceDialog(
    initialDepth: Double,
    onCancel: () -> Unit,
    onConfirm: (depthM: Double, f0: Double, waveform: FdtdWaveform, amp: Double) -> Unit,
) {
    var depth by remember { mutableStateOf("%.0f".format(initialDepth)) }
    var f0 by remember { mutableStateOf("25") }
    var amp by remember { mutableStateOf("1000") }
    var waveform by remember { mutableStateOf(FdtdWaveform.HANN) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Quelle platzieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = depth, onValueChange = { depth = it }, label = { Text("Tiefe (m unter Wasseroberfläche)") }, singleLine = true)
                OutlinedTextField(value = f0, onValueChange = { f0 = it }, label = { Text("f0 (Hz)") }, singleLine = true)
                OutlinedTextField(value = amp, onValueChange = { amp = it }, label = { Text("Amplitude (Pa/s)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = waveform == FdtdWaveform.HANN, onClick = { waveform = FdtdWaveform.HANN }, label = { Text("Hann") })
                    FilterChip(selected = waveform == FdtdWaveform.RICKER, onClick = { waveform = FdtdWaveform.RICKER }, label = { Text("Ricker") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = depth.trim().toDoubleOrNull()
                val f = f0.trim().toDoubleOrNull()
                val a = amp.trim().toDoubleOrNull()
                if (d != null && f != null && f > 0 && a != null && a > 0) onConfirm(d, f, waveform, a)
            }) { Text("Setzen") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

@Composable
private fun ReceiverDialog(
    initialDepth: Double,
    onCancel: () -> Unit,
    onConfirm: (depthM: Double) -> Unit,
) {
    var depth by remember { mutableStateOf("%.0f".format(initialDepth)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Receiver platzieren") },
        text = {
            OutlinedTextField(
                value = depth,
                onValueChange = { depth = it },
                label = { Text("Tiefe (m, Standard: Meeresboden)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val d = depth.trim().toDoubleOrNull()
                if (d != null && d > 0) onConfirm(d)
            }) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

private fun fmtM(v: Float): String =
    if (v >= 0f) "+%.0f m".format(v) else "%.0f m".format(v)

private fun fmtPos(p: DoubleArray): String =
    "%.4f°, %.4f°".format(p[0], p[1])
