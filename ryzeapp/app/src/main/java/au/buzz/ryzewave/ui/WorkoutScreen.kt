@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package au.buzz.ryzewave.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.Workout
import au.buzz.ryzewave.protocol.SportTypes

@Composable
fun WorkoutScreen(onOpenWorkout: (Long) -> Unit, vm: WorkoutViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sportType by vm.sportType.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val trace by vm.hrTrace.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(message, vm::clearMessage, snackbar)

    // GPS distance needs precise location; the foreground service cannot start without it on Android 14+.
    val context = LocalContext.current
    var locationDenied by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        locationDenied = !granted
        if (granted) vm.start()
    }
    val onStart: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationDenied = false
            vm.start()
        } else {
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveWorkoutCard(state, sportType, vm::setSportType, status.isConnected(), onStart, vm::pause, vm::resume, vm::stop)
            if (locationDenied) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Precise location is required to measure GPS distance. Allow \"Location — Precise\" for RyzeWave.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) { Text("Open app settings") }
                    }
                }
            }
            if (state.active || trace.isNotEmpty()) {
                val chartStart = if (state.startTime > 0L) state.startTime else (trace.firstOrNull()?.time ?: 0L)
                val duration = if (state.active) state.elapsedSeconds
                else ((trace.lastOrNull()?.time ?: chartStart) - chartStart).div(1000L).toInt()
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Live heart rate", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        WorkoutChart(hr = trace, pace = emptyList(), kmMarkers = emptyList(), startTime = chartStart, durationSeconds = duration)
                    }
                }
            }
            Text("Past workouts", style = MaterialTheme.typography.titleMedium)
            if (workouts.isEmpty()) {
                Text("No workouts yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            workouts.forEach { w -> WorkoutRow(w, onClick = { onOpenWorkout(w.id) }) }
        }
    }
}

/**
 * [selectedSport] is the sport the next workout starts with (persisted); while a workout is active the card shows
 * the session's own sport from [state] instead.
 */
@Composable
private fun LiveWorkoutCard(
    state: WorkoutUiState,
    selectedSport: Int,
    onSelectSport: (Int) -> Unit,
    connected: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val sport = if (state.phase == WorkoutPhase.IDLE) selectedSport else state.sportType
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.duration(state.elapsedSeconds), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "${SportTypes.name(sport)} · ${phaseLabel(state.phase)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.phase == WorkoutPhase.STARTING || state.phase == WorkoutPhase.STOPPING) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatText("Distance (from GPS)", Fmt.metres(state.distanceMeters), Modifier.weight(1f))
                StatText("Pace", Fmt.pace(state.paceSecPerKm), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatText("Heart rate", state.hr?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
                StatText("GPS", gpsLabel(state), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatText("Calories", "${state.calories} kcal", Modifier.weight(1f))
                StatText("Sport", SportTypes.name(sport), Modifier.weight(1f))
            }
            if (state.phase == WorkoutPhase.IDLE) SportPicker(selectedSport, onSelectSport)
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    WorkoutPhase.IDLE -> Button(onClick = onStart) { Text("Start workout") }
                    WorkoutPhase.RUNNING -> {
                        FilledTonalButton(onClick = onPause) { Text("Pause") }
                        OutlinedButton(onClick = onStop) { Text("Stop") }
                    }
                    WorkoutPhase.PAUSED -> {
                        Button(onClick = onResume) { Text("Resume") }
                        OutlinedButton(onClick = onStop) { Text("Stop") }
                    }
                    WorkoutPhase.STARTING, WorkoutPhase.STOPPING -> OutlinedButton(onClick = {}, enabled = false) { Text("Please wait…") }
                }
            }
            if (!connected && state.phase == WorkoutPhase.IDLE) {
                Text(
                    "Watch not connected: the workout will track GPS distance only, without heart rate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Chips for the sports people actually pick ([SportTypes.POPULAR]) plus "More…" for the watch's full menu of 70.
 * A sport chosen from the full list that is not in the short row gets its own chip so the selection stays visible.
 */
@Composable
private fun SportPicker(selected: Int, onSelect: (Int) -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Sport", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chips = if (selected in SportTypes.POPULAR) SportTypes.POPULAR else SportTypes.POPULAR + selected
            chips.forEach { id ->
                FilterChip(selected = id == selected, onClick = { onSelect(id) }, label = { Text(SportTypes.name(id)) })
            }
            AssistChip(onClick = { showAll = true }, label = { Text("More…") })
        }
    }
    if (showAll) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("All sports") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(SportTypes.byName, key = { it.first }) { (id, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(id); showAll = false }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAll = false }) { Text("Close") } },
        )
    }
}

private fun phaseLabel(phase: WorkoutPhase): String = when (phase) {
    WorkoutPhase.IDLE -> "Ready"
    WorkoutPhase.STARTING -> "Starting…"
    WorkoutPhase.RUNNING -> "Running"
    WorkoutPhase.PAUSED -> "Paused"
    WorkoutPhase.STOPPING -> "Saving…"
}

private fun gpsLabel(state: WorkoutUiState): String {
    val acc = state.gpsAccuracyM ?: return if (state.active) "searching…" else "–"
    return "${state.gpsQuality} (±${acc.toInt()} m)"
}

@Composable
private fun WorkoutRow(w: Workout, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${SportTypes.name(w.sportType)} · ${Fmt.dateTime(w.start)}", style = MaterialTheme.typography.titleSmall)
                val hr = w.avgHr?.let { " · avg $it bpm" } ?: ""
                Text(
                    "${Fmt.duration(w.durationSeconds)} · ${Fmt.metres(w.distanceMeters)} · ${Fmt.pace(averagePace(w))}$hr",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (w.end == null) Text("in progress / not finished", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

private fun averagePace(w: Workout): Double =
    if (w.distanceMeters > 0.0 && w.durationSeconds > 0) w.durationSeconds / w.distanceMeters * 1000.0 else 0.0

/** The workout-detail chart is its own screen: summary stats, HR + pace chart with km markers, GPX export. */
@Composable
fun WorkoutDetailScreen(id: Long, onBack: () -> Unit, vm: WorkoutDetailViewModel = viewModel(key = "workout-$id")) {
    LaunchedEffect(id) { vm.load(id) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(message, vm::clearMessage, snackbar)
    val w = detail.workout

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w?.let { "${SportTypes.name(it.sportType)} · ${Fmt.dateTime(it.start)}" } ?: "Workout") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (w == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            } else {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(SportTypes.name(w.sportType), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            StatText("Duration", Fmt.duration(w.durationSeconds), Modifier.weight(1f))
                            StatText("Distance", Fmt.metres(w.distanceMeters), Modifier.weight(1f))
                            StatText("Avg pace", Fmt.pace(averagePace(w)), Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            StatText("Avg HR", w.avgHr?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
                            StatText("Max HR", w.maxHr?.let { "$it bpm" } ?: "–", Modifier.weight(1f))
                            StatText("Calories", "${w.calories} kcal", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            StatText("GPS fixes", "${detail.acceptedCount} of ${detail.pointCount}", Modifier.weight(1f))
                            StatText("GPS track", Fmt.metres(detail.gpsDistanceMeters), Modifier.weight(1f))
                            StatText("HR samples", "${detail.hr.size}", Modifier.weight(1f))
                        }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Heart rate & pace", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        WorkoutChart(detail.hr, detail.pace, detail.kmMarkers, w.start, w.durationSeconds)
                    }
                }
                Button(onClick = vm::exportGpx, enabled = !busy && detail.acceptedCount > 0) { Text("Export GPX") }
            }
        }
    }
}
