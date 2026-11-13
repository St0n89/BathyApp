package io.github.st0n89.bathyapp.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs

@Composable
fun HomeScreen(vm: AppViewModel, state: AppUiState) {
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.openLocalFile(context, uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("BathyApp", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tiefenprofile und Gitter aus EMODnet (online) oder GEBCO-Dateien (offline).",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.source == DataSource.EMODNET,
                onClick = { vm.setSource(DataSource.EMODNET) },
                label = { Text("EMODnet (online)") },
            )
            FilterChip(
                selected = state.source == DataSource.LOCAL_FILE,
                onClick = { vm.setSource(DataSource.LOCAL_FILE) },
                label = { Text("Datei (offline)") },
            )
        }

        if (state.source == DataSource.EMODNET) {
            OutlinedButton(onClick = { vm.setCoverageIndex(state.coverageIndex + 1) }) {
                val cov = EmodnetWcs.COVERAGES[state.coverageIndex]
                Text("Datensatz: ${cov.label} (${cov.id})")
            }
        } else {
            OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                Text(if (state.localReady) "Andere Datei wählen" else "NetCDF/GeoTIFF wählen (.nc, .tif)")
            }
            if (state.localInfo != null) {
                Text(state.localInfo, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("West (°)", state.west, 8, Modifier.weight(1f), vm::setWest)
            NumberField("Ost (°)", state.east, 8, Modifier.weight(1f), vm::setEast)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Süd (°)", state.south, 8, Modifier.weight(1f), vm::setSouth)
            NumberField("Nord (°)", state.north, 8, Modifier.weight(1f), vm::setNorth)
        }
        NumberField("Zielauflösung (px breit)", state.widthPx, 4, Modifier.fillMaxWidth(), vm::setWidthPx)

        Button(
            onClick = { vm.loadGrid() },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Gitter laden") }

        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Lade Daten …", modifier = Modifier.padding(top = 12.dp))
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (state.grid != null) {
            OutlinedButton(onClick = { vm.goToMap() }, modifier = Modifier.fillMaxWidth()) {
                Text("Zurück zur Karte")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
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
