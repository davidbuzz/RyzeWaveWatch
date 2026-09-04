@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package au.buzz.ryzewave.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.SamplingSettings
import au.buzz.ryzewave.core.StrideSettings
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchStatus
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.health.ExportResult
import au.buzz.ryzewave.workout.DefaultStrideModel
import java.util.Locale

@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val mac by vm.mac.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val sampling by vm.sampling.collectAsStateWithLifecycle()
    val stride by vm.stride.collectAsStateWithLifecycle()
    val hcEnabled by vm.healthConnectEnabled.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanResults by vm.scanResults.collectAsStateWithLifecycle()
    val calibrationWorkout by vm.calibrationWorkout.collectAsStateWithLifecycle()
    val lastExport by vm.lastExport.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val health = LocalHealthPermissionHost.current
    val sdkStatus by health.sdkStatus.collectAsStateWithLifecycle()
    val granted by health.granted.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(message, vm::clearMessage, snackbar)
    LaunchedEffect(Unit) { health.refresh() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WatchSection(
                mac = mac, status = status, scanning = scanning, results = scanResults, busy = busy,
                onSave = vm::saveMac, onScan = vm::startScan, onStopScan = vm::stopScan,
                onChoose = vm::chooseDevice, onFind = vm::findWatch,
            )
            ProfileSection(profile, onSave = vm::saveProfile)
            SamplingSection(sampling, onChange = vm::saveSampling)
            StrideSection(
                profile = profile, stride = stride, calibrationWorkout = calibrationWorkout,
                onSave = vm::saveStride, onReset = vm::resetStride, onCalibrate = vm::calibrateFromLastWorkout,
            )
            HealthSection(
                enabled = hcEnabled, sdkStatus = sdkStatus, granted = granted, busy = busy || exporting,
                lastExport = lastExport,
                onToggle = { on ->
                    vm.setHealthConnectEnabled(on)
                    if (on && !granted) health.request()     // arming the export without permissions is pointless
                },
                onGrant = health::request, onExport = vm::exportNow,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

// ---- watch ------------------------------------------------------------------------------------------------

@Composable
private fun WatchSection(
    mac: String,
    status: WatchStatus,
    scanning: Boolean,
    results: List<ScannedDevice>,
    busy: Boolean,
    onSave: (String) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onChoose: (ScannedDevice) -> Unit,
    onFind: () -> Unit,
) {
    var text by remember(mac) { mutableStateOf(mac) }
    SectionCard("Watch") {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.uppercase(Locale.ROOT) },
            label = { Text("MAC address") },
            supportingText = { Text("Default ${UiDefaults.WATCH_MAC}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }, enabled = text.trim() != mac) { Text("Save") }
            OutlinedButton(onClick = if (scanning) onStopScan else onScan) { Text(if (scanning) "Stop scan" else "Scan") }
            OutlinedButton(onClick = onFind, enabled = status.isConnected() && !busy) { Text("Find watch") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        results.forEach { d ->
            ListItem(
                headlineContent = { Text(d.name) },
                supportingContent = { Text("${d.mac} · ${d.rssi} dBm · tap to use") },
                modifier = Modifier.clickable { onChoose(d) },
            )
        }
        if (scanning && results.isEmpty()) {
            Text("Looking for watches named \"${UiDefaults.WATCH_NAME_PREFIX}…\"", style = MaterialTheme.typography.bodySmall)
        }
        Text("Status: ${connectionLabel(status)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- profile -----------------------------------------------------------------------------------------------

@Composable
private fun ProfileSection(profile: UserProfile, onSave: (UserProfile) -> Unit) {
    // Local edit state is seeded from the stored profile only while the user has not typed anything (and again
    // after Save); keying it on the profile flow made in-progress edits revert whenever the flow re-emitted.
    var height by remember { mutableStateOf(profile.heightCm.toString()) }
    var weight by remember { mutableStateOf(profile.weightKg.toString()) }
    var age by remember { mutableStateOf(profile.age.toString()) }
    var goal by remember { mutableStateOf(profile.stepGoal.toString()) }
    var male by remember { mutableStateOf(profile.male) }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(profile, edited) {
        if (!edited) {
            height = profile.heightCm.toString()
            weight = profile.weightKg.toString()
            age = profile.age.toString()
            goal = profile.stepGoal.toString()
            male = profile.male
        }
    }
    val parsed = UserProfile(
        heightCm = height.toIntOrNull() ?: 0,
        weightKg = weight.toIntOrNull() ?: 0,
        age = age.toIntOrNull() ?: 0,
        male = male,
        stepGoal = goal.toIntOrNull() ?: 0,
    )
    val valid = parsed.heightCm in 100..250 && parsed.weightKg in 20..300 && parsed.age in 5..120 && parsed.stepGoal in 500..100_000
    SectionCard("Profile") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Height", height, { height = it; edited = true }, Modifier.weight(1f), "cm")
            NumberField("Weight", weight, { weight = it; edited = true }, Modifier.weight(1f), "kg")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Age", age, { age = it; edited = true }, Modifier.weight(1f))
            NumberField("Step goal", goal, { goal = it; edited = true }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = male, onClick = { male = true; edited = true }, label = { Text("Male") })
            FilterChip(selected = !male, onClick = { male = false; edited = true }, label = { Text("Female") })
        }
        Button(onClick = { onSave(parsed); edited = false }, enabled = valid && parsed != profile) { Text("Save profile") }
    }
}

// ---- sampling ---------------------------------------------------------------------------------------------

@Composable
private fun SamplingSection(sampling: SamplingSettings, onChange: (SamplingSettings) -> Unit) {
    SectionCard("Sampling") {
        SwitchRow("Continuous heart rate (10-minute bins)", sampling.continuousHr) { onChange(sampling.copy(continuousHr = it)) }
        SwitchRow("Automatic SpO2", sampling.spo2AutoEnabled) { onChange(sampling.copy(spo2AutoEnabled = it)) }
        Text("SpO2 interval", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            UiDefaults.SPO2_INTERVALS.forEach { min ->
                FilterChip(
                    selected = sampling.spo2IntervalMin == min,
                    onClick = { onChange(sampling.copy(spo2IntervalMin = min)) },
                    enabled = sampling.spo2AutoEnabled,
                    label = { Text("$min min") },
                )
            }
        }
        SwitchRow("Raise wrist to wake", sampling.raiseWristWake) { onChange(sampling.copy(raiseWristWake = it)) }
        Text("Changes are sent to the watch immediately when connected, otherwise on the next connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- stride -----------------------------------------------------------------------------------------------

@Composable
private fun StrideSection(
    profile: UserProfile,
    stride: StrideSettings,
    calibrationWorkout: Workout?,
    onSave: (StrideSettings) -> Unit,
    onReset: () -> Unit,
    onCalibrate: () -> Unit,
) {
    var walk by remember(stride) { mutableStateOf(stride.walkStrideM?.let { fmt3(it) } ?: "") }
    var run by remember(stride) { mutableStateOf(stride.runStrideM?.let { fmt3(it) } ?: "") }
    val walkVal = walk.toDoubleOrNull()
    val runVal = run.toDoubleOrNull()
    val walkOk = walk.isBlank() || (walkVal != null && walkVal in DefaultStrideModel.MIN_STRIDE_M..DefaultStrideModel.MAX_STRIDE_M)
    val runOk = run.isBlank() || (runVal != null && runVal in DefaultStrideModel.MIN_STRIDE_M..DefaultStrideModel.MAX_STRIDE_M)
    val parsed = StrideSettings(walkStrideM = walkVal?.takeIf { walk.isNotBlank() }, runStrideM = runVal?.takeIf { run.isNotBlank() })
    SectionCard("Stride (distance model)") {
        Text(
            "Defaults from your height: walk ${fmt3(DefaultStrideModel.defaultWalkStrideM(profile))} m, run ${fmt3(DefaultStrideModel.defaultRunStrideM(profile))} m per step. " +
                "In use: walk ${fmt3(strideModel.walkStrideM(profile, stride))} m, run ${fmt3(strideModel.runStrideM(profile, stride))} m.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Walk stride", walk, { walk = it }, Modifier.weight(1f), "m")
            NumberField("Run stride", run, { run = it }, Modifier.weight(1f), "m")
        }
        if (!walkOk || !runOk) Text("Stride must be between ${DefaultStrideModel.MIN_STRIDE_M} and ${DefaultStrideModel.MAX_STRIDE_M} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(parsed) }, enabled = walkOk && runOk && parsed != stride) { Text("Save") }
            OutlinedButton(onClick = onReset, enabled = stride != StrideSettings()) { Text("Use defaults") }
        }
        OutlinedButton(onClick = onCalibrate, enabled = calibrationWorkout != null) { Text("Calibrate from last GPS workout") }
        Text(
            calibrationWorkout?.let { "Uses ${Fmt.dateTime(it.start)}: ${Fmt.metres(it.distanceMeters)} in ${Fmt.duration(it.durationSeconds)}, divided by the steps the watch counted in that window." }
                ?: "Record a GPS workout of at least 200 m first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt3(v: Double): String = String.format(Locale.US, "%.3f", v)

private val strideModel = DefaultStrideModel()

// ---- Health Connect ---------------------------------------------------------------------------------------

@Composable
private fun HealthSection(
    enabled: Boolean,
    sdkStatus: Int,
    granted: Boolean,
    busy: Boolean,
    lastExport: ExportResult?,
    onToggle: (Boolean) -> Unit,
    onGrant: () -> Unit,
    onExport: () -> Unit,
) {
    val available = sdkStatus == HealthConnectClient.SDK_AVAILABLE
    SectionCard("Health Connect") {
        Text(healthSdkLabel(sdkStatus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Export to Health Connect", enabled, enabled = available, onChange = onToggle)
        Text(
            when {
                granted -> "Permissions granted"
                enabled -> "Permissions not granted yet — nothing is exported until you grant them"
                else -> "Permissions not granted yet"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !granted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(onClick = onGrant, enabled = available && !granted, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
        Button(onClick = onExport, enabled = available && granted && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Exporting…" else "Export now") }
        if (lastExport != null) {
            Text(
                exportSummary(lastExport),
                style = MaterialTheme.typography.bodySmall,
                color = if (lastExport.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "Last export 18:05: 42 records" / "Last export 18:05 failed: …". */
private fun exportSummary(r: ExportResult): String {
    val at = Fmt.time(r.time)
    return when (r.status) {
        ExportResult.Status.OK -> "Last export $at: ${r.inserted} records" + (if (r.failed > 0) ", ${r.failed} rejected" else "")
        ExportResult.Status.NOTHING_TO_EXPORT -> "Last export $at: nothing new"
        else -> "Last export $at failed: ${r.message ?: r.status.name}"
    }
}
