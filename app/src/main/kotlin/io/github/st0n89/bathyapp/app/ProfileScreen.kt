package io.github.st0n89.bathyapp.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Composable
fun ProfileScreen(vm: AppViewModel, state: AppUiState) {
    val pts = state.profile ?: return
    val st = state.profileStats ?: return
    val context = LocalContext.current

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { vm.exportProfileCsv(context, it) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Tiefenprofil", style = MaterialTheme.typography.titleLarge)

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Stat("Länge", "%.1f km".format(st.lengthKm))
                Stat("Tiefster", "%.0f m".format(st.minElevation))
                Stat("Höchster", "%.0f m".format(st.maxElevation))
                Stat("Mittel", "%.0f m".format(st.meanElevation))
                Stat("Punkte", "${st.nValid}")
            }
        }

        val waterColor = Color(0xFF3FA9D9)
        val lineColor = MaterialTheme.colorScheme.primary
        val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f).toArgb()

        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val n = pts.size
            if (n < 2) return@Canvas

            val gridColor = Color(0x1A000000)
            val axisColor = Color(0xFF9E9E9E)
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = labelColor
                textSize = 10.5.dp.toPx()
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            fun paintAlignedRight(): android.graphics.Paint = labelPaint

            val mt = 14.dp.toPx()
            val mr = 12.dp.toPx()
            val mb = 22.dp.toPx()
            val xMax = pts.last().distKm.coerceAtLeast(1e-6)
            val vMin = st.minElevation
            val vMax = st.maxElevation
            val pad = ((vMax - vMin).coerceAtLeast(1.0)) * 0.08
            val lo = vMin - pad
            val hi = vMax + pad

            // left margin: widest y tick label + gap
            val stepY = niceStep(hi - lo, 5)
            val yTicks = mutableListOf<Double>()
            var ev = ceil(lo / stepY) * stepY
            while (ev <= hi + 1e-9) {
                yTicks.add(ev)
                ev += stepY
            }
            val yLabels = yTicks.map { "%.0f".format(it) }
            var labelW = 0f
            for (t in yLabels) labelW = max(labelW, labelPaint.measureText(t))
            val ml = labelW + 8.dp.toPx()

            val w = size.width - ml - mr
            val h = size.height - mt - mb
            if (w <= 0f || h <= 0f) return@Canvas

            fun X(d: Double): Float = (ml + (d / xMax) * w).toFloat()
            fun Y(e: Double): Float = (mt + (hi - e) / (hi - lo) * h).toFloat()
            val y0 = Y(0.0)

            // ---- grid + y tick labels ----
            val halfText = labelPaint.textSize / 3f
            for (i in yTicks.indices) {
                val yc = Y(yTicks[i])
                drawLine(gridColor, Offset(ml, yc), Offset(ml + w, yc), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(yLabels[i], ml - 6.dp.toPx(), yc + halfText, paintAlignedRight())
            }

            // ---- x ticks (distance, nice km steps) ----
            val stepX = niceStep(xMax, 5)
            val xPaint = android.graphics.Paint(labelPaint).apply {
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val yBase = mt + h + xPaint.textSize * 0.9f
            var dx = 0.0
            var lastLabelEnd = -1f
            while (dx <= xMax + 1e-9) {
                val xc = X(dx)
                drawLine(gridColor, Offset(xc, mt), Offset(xc, mt + h), strokeWidth = 1f)
                val lab = if (stepX < 1.0) "%.1f".format(dx) else "%.0f".format(dx)
                val textW = xPaint.measureText(lab)
                if (xc - textW / 2f - 4f > lastLabelEnd) {
                    drawContext.canvas.nativeCanvas.drawText(lab, xc, yBase, xPaint)
                    lastLabelEnd = xc + textW / 2f
                }
                dx += stepX
            }

            // ---- axes ----
            drawLine(axisColor, Offset(ml, mt), Offset(ml, mt + h), strokeWidth = 1.5f)
            drawLine(axisColor, Offset(ml, mt + h), Offset(ml + w, mt + h), strokeWidth = 1.5f)

            // axis titles
            val titlePaint = android.graphics.Paint(labelPaint).apply {
                textAlign = android.graphics.Paint.Align.LEFT
                textSize = 10.dp.toPx()
            }
            drawContext.canvas.nativeCanvas.drawText("Tiefe [m]", ml, mt - 4.dp.toPx(), titlePaint)
            val rightTitlePaint = android.graphics.Paint(labelPaint).apply {
                textAlign = android.graphics.Paint.Align.RIGHT
                textSize = 10.dp.toPx()
            }
            drawContext.canvas.nativeCanvas.drawText("Distanz [km]", ml + w, yBase, rightTitlePaint)

            // ---- water fill (profile clamped to 0) ----
            val water = Path().apply {
                moveTo(X(pts[0].distKm), y0)
                for (i in 1 until n) {
                    val e = min(pts[i].elevation, 0.0)
                    lineTo(X(pts[i].distKm), Y(e))
                }
                lineTo(X(pts[n - 1].distKm), y0)
                close()
            }
            drawPath(water, waterColor.copy(alpha = 0.35f))

            // sea level line
            drawLine(
                axisColor,
                Offset(ml, y0),
                Offset(ml + w, y0),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )

            // ---- profile curve ----
            val curve = Path().apply {
                moveTo(X(pts[0].distKm), Y(pts[0].elevation))
                for (i in 1 until n) lineTo(X(pts[i].distKm), Y(pts[i].elevation))
            }
            drawPath(curve, lineColor, style = Stroke(width = 3.5f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { csvLauncher.launch("bathyapp_profil.csv") }) { Text("Profil-CSV") }
            OutlinedButton(onClick = { vm.goToMap() }) { Text("← Karte") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Nice 1/2/5 x 10^k step covering [range] with roughly [target] ticks. */
private fun niceStep(range: Double, target: Int): Double {
    val raw = range / target
    if (raw <= 0.0) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val s = when {
        norm < 1.5 -> 1.0
        norm < 3.5 -> 2.0
        norm < 7.5 -> 5.0
        else -> 10.0
    }
    return s * mag
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
