package au.buzz.ryzewave.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import au.buzz.ryzewave.core.ConnectionState
import au.buzz.ryzewave.core.DailySummary
import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.WatchStatus
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val liveHr by vm.liveHr.collectAsStateWithLifecycle()
    val measuring by vm.measuring.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(message, vm::clearMessage, snackbar)
    LaunchedEffect(Unit) { vm.refreshDay() }
    // A slow clock so "Last sync … ago" and the "(live)" HR label age while the screen stays open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            now = System.currentTimeMillis()
        }
    }
    // Connect needs BLUETOOTH_CONNECT (Android 12+): ask for it here when the startup prompt was refused.
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (vm.hasBluetoothPermission()) vm.connect()
    }
    val onConnect: () -> Unit = {
        if (vm.hasBluetoothPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            vm.connect()
        } else {
            bluetoothLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
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
            ConnectionCard(status, busy, now, onConnect, vm::disconnect, vm::sync)
            StepsCard(today, profile.stepGoal)
            VitalsCard(
                today = today, liveHr = liveHr, measuring = measuring, busy = busy, now = now,
                connected = status.isConnected(), onMeasureHr = vm::measureHr, onSpo2 = vm::spo2Test,
            )
            SleepCard(sleep)
        }
    }
}

/** Shows each non-null [message] once in [host], then calls [onShown]. */
@Composable
fun MessageSnackbar(message: String?, onShown: () -> Unit, host: SnackbarHostState) {
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            onShown()
        }
    }
}

fun WatchStatus.isConnected(): Boolean =
    state == ConnectionState.CONNECTED || state == ConnectionState.SYNCING

fun connectionLabel(s: WatchStatus): String = when (s.state) {
    ConnectionState.DISCONNECTED -> "Disconnected"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.SYNCING -> "Syncing…"
    ConnectionState.ERROR -> "Error" + (s.message?.let { ": $it" } ?: "")
}

/** Small label-over-value block used in the cards. */
@Composable
fun StatText(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConnectionCard(
    status: WatchStatus,
    busy: Boolean,
    now: Long,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    val working = busy || status.state == ConnectionState.CONNECTING || status.state == ConnectionState.SYNCING
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ryze Wave", style = MaterialTheme.typography.titleMedium)
                    Text(connectionLabel(status), style = MaterialTheme.typography.bodyMedium)
                    status.mac?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (working) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                val battery = status.batteryPercent?.let { "$it %" + (if (status.charging) " ⚡" else "") } ?: "–"
                StatText("Battery", battery, Modifier.weight(1f))
                StatText("Last sync", Fmt.relative(status.lastSyncTime, now), Modifier.weight(1f))
            }
            StatText("Firmware", status.firmware ?: "–")
            if (status.state != ConnectionState.ERROR) {
                status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isConnected()) {
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect, enabled = !working) { Text("Connect") }
                }
                FilledTonalButton(onClick = onSync, enabled = status.isConnected() && !working) { Text("Sync now") }
            }
        }
    }
}

@Composable
private fun StepsCard(today: DailySummary?, goal: Int) {
    val steps = today?.steps ?: 0
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GoalRing(steps, goal)
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                StatText("Goal", "${Fmt.int(goal)} steps")
                StatText("Distance", Fmt.km(today?.distanceMeters ?: 0.0))
                StatText("Walk / run", "${Fmt.int(today?.walkSteps ?: 0)} / ${Fmt.int(today?.runSteps ?: 0)}")
            }
        }
    }
}

/** Steps-vs-goal ring, drawn on a Canvas with the count in the middle. */
@Composable
fun GoalRing(steps: Int, goal: Int, modifier: Modifier = Modifier) {
    val fraction = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (steps >= goal && goal > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Box(modifier.size(124.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0f) {
                drawArc(fill, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Fmt.int(steps), style = MaterialTheme.typography.titleLarge)
            Text("${(fraction * 100).roundToInt()} %", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VitalsCard(
    today: DailySummary?,
    liveHr: HrSample?,
    measuring: Boolean,
    busy: Boolean,
    now: Long,
    connected: Boolean,
    onMeasureHr: () -> Unit,
    onSpo2: () -> Unit,
) {
    val live = liveHr?.takeIf { now - it.time < 15_000L }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Vitals", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val hr = today?.lastHr
                StatText(
                    label = if (live != null) "Heart rate (live)" else "Heart rate",
                    value = when {
                        live != null -> "${live.bpm} bpm"
                        hr != null -> "${hr.bpm} bpm"
                        else -> "–"
                    },
                )
                if (live == null && hr != null) StatText("at", Fmt.time(hr.time))
                val ranges = today?.let { t -> if (t.minHr != null && t.maxHr != null) "${t.minHr}–${t.maxHr}" else null }
                if (ranges != null) StatText("Min–max", ranges)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val spo2 = today?.lastSpo2
                StatText("Blood oxygen", spo2?.let { "${it.percent} %" } ?: "–")
                if (spo2 != null) StatText("at", Fmt.time(spo2.time))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onMeasureHr, enabled = connected && !measuring) {
                    Text(if (measuring) "Measuring…" else "Measure HR")
                }
                OutlinedButton(onClick = onSpo2, enabled = connected && !busy) { Text("SpO2 test") }
            }
            if (!connected) {
                Text("Connect the watch to take live measurements.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SleepCard(stages: List<SleepStage>) {
    val summary = remember(stages) { SleepMath.summarize(stages) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Sleep last night", style = MaterialTheme.typography.titleMedium)
            if (stages.isEmpty() || summary.bedTime == null || summary.wakeTime == null) {
                Text("No sleep data yet — sync the watch after a night's sleep.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StatText("Asleep", Fmt.minutes(summary.totalMin))
                    StatText("Bed", Fmt.time(summary.bedTime))
                    StatText("Wake", Fmt.time(summary.wakeTime))
                }
                SleepStrip(stages, summary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatText("Deep", Fmt.minutes(summary.deepMin))
                    StatText("Light", Fmt.minutes(summary.lightMin))
                    StatText("REM", Fmt.minutes(summary.remMin))
                    StatText("Awake", Fmt.minutes(summary.awakeMin))
                }
            }
        }
    }
}

/** One coloured block per stage across the night. */
@Composable
private fun SleepStrip(stages: List<SleepStage>, summary: SleepSummary) {
    val bed = summary.bedTime ?: return
    val wake = summary.wakeTime ?: return
    val span = (wake - bed).toDouble().coerceAtLeast(1.0)
    val deep = MaterialTheme.colorScheme.primary
    val light = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val rem = MaterialTheme.colorScheme.tertiary
    val awake = MaterialTheme.colorScheme.error
    val other: Color = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        for (s in stages) {
            val x0 = ((s.start - bed) / span * size.width).toFloat().coerceIn(0f, size.width)
            val x1 = ((s.start + s.minutes * ChartData.MINUTE_MS - bed) / span * size.width).toFloat().coerceIn(0f, size.width)
            val c = when (s.stage) {
                SleepMath.DEEP -> deep
                SleepMath.LIGHT -> light
                SleepMath.REM -> rem
                SleepMath.AWAKE -> awake
                else -> other
            }
            drawRect(c, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
    }
}
