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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.min

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
        val axisColor = Color(0xFF9E9E9E)

        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val n = pts.size
            if (n < 2) return@Canvas
            val ml = 12.dp.toPx()
            val mr = 12.dp.toPx()
            val mt = 10.dp.toPx()
            val mb = 10.dp.toPx()
            val xMax = pts.last().distKm.coerceAtLeast(1e-6)
            val vMin = st.minElevation
            val vMax = st.maxElevation
            val pad = ((vMax - vMin).coerceAtLeast(1.0)) * 0.08
            val lo = vMin - pad
            val hi = vMax + pad

            val w = size.width - ml - mr
            val h = size.height - mt - mb

            fun X(d: Double): Float = (ml + (d / xMax) * w).toFloat()
            fun Y(e: Double): Float = (mt + (hi - e) / (hi - lo) * h).toFloat()
            val y0 = Y(0.0)

            // Gitterlinien
            val gridColor = Color(0x1A000000)
            val steps = 5
            for (i in 0..steps) {
                val e = lo + (hi - lo) * i / steps
                drawLine(gridColor, Offset(ml, Y(e)), Offset(ml + w, Y(e)), strokeWidth = 1f)
            }

            // Wasserfläche (Profil auf 0 geklemmt) unter der Nulllinie
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

            // Nulllinie
            drawLine(
                axisColor,
                Offset(ml, y0),
                Offset(ml + w, y0),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )

            // Profilkurve
            val curve = Path().apply {
                moveTo(X(pts[0].distKm), Y(pts[0].elevation))
                for (i in 1 until n) lineTo(X(pts[i].distKm), Y(pts[i].elevation))
            }
            drawPath(curve, lineColor, style = Stroke(width = 3.5f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0", style = MaterialTheme.typography.labelSmall)
            Text("%.0f km".format(pts.last().distKm / 2), style = MaterialTheme.typography.labelSmall)
            Text("%.0f km".format(pts.last().distKm), style = MaterialTheme.typography.labelSmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { csvLauncher.launch("bathyapp_profil.csv") }) { Text("Profil-CSV") }
            OutlinedButton(onClick = { vm.goToMap() }) { Text("← Karte") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF757575))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
