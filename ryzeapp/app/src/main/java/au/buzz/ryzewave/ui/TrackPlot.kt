@file:OptIn(ExperimentalTextApi::class)

package au.buzz.ryzewave.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.buzz.ryzewave.core.TrackPoint

/** Height of the track plot; a little taller than the time charts because a track is two-dimensional. */
val TrackPlotHeight: Dp = 240.dp

/** Start and end markers: the same green / red in both themes (they sit on the line, not on the background). */
val TrackStartColor: Color = Color(0xFF43A047)
val TrackEndColor: Color = Color(0xFFE53935)

/**
 * The GPS track of a workout, north up, scaled to fit (`TrackGeometry`): the accepted fixes as a polyline,
 * the rejected ones as small dots, a green start and a red end marker, a numbered ring at every whole
 * kilometre, and — in a strip below the track so they never overlap it — a scale bar (left) and a north
 * arrow (right). No map tiles: the shape of the route is what matters when judging the distance filter.
 */
@Composable
fun TrackPlot(points: List<TrackPoint>, modifier: Modifier = Modifier, colors: ChartColors = chartColors()) {
    if (points.isEmpty()) {
        EmptyChart("No GPS track recorded", modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = colors.text)
    val accepted = remember(points) { TrackGeometry.accepted(points) }
    val rejected = remember(points) { points.filter { !it.accepted && it.lat.isFinite() && it.lon.isFinite() } }
    val marks = remember(points) { TrackGeometry.kmMarks(points) }

    Canvas(modifier.fillMaxWidth().height(TrackPlotHeight)) {
        val pad = 12.dp.toPx()
        val tick = 4.dp.toPx()
        val w = 2.dp.toPx()
        val labelH = measurer.measure("10 m", style).size.height.toFloat()
        // bottom strip for the scale bar and the north arrow; the track is fitted above it
        val strip = labelH + tick + 8.dp.toPx()
        val plotH = (size.height - strip).coerceAtLeast(1f)
        val proj = TrackGeometry.projection(points, size.width, plotH, pad) ?: return@Canvas
        val dotR = 1.5.dp.toPx()
        val markerR = 5.dp.toPx()
        val kmR = 7.dp.toPx()

        clipRect(0f, 0f, size.width, plotH) {
            for (p in rejected) {
                val xy = proj.project(p)
                drawCircle(colors.grid, dotR, Offset(xy.x, xy.y))
            }
            if (accepted.size >= 2) {
                val path = Path()
                accepted.forEachIndexed { i, p ->
                    val xy = proj.project(p)
                    if (i == 0) path.moveTo(xy.x, xy.y) else path.lineTo(xy.x, xy.y)
                }
                drawPath(path, colors.line, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            for (m in marks) {
                val xy = proj.project(m.lat, m.lon)
                val c = Offset(xy.x, xy.y)
                drawCircle(colors.dotFill, kmR, c)
                drawCircle(colors.secondary, kmR, c, style = Stroke(width = w))
                val label = "${m.km}"
                val s = measurer.measure(label, style).size
                drawText(measurer, label, Offset(xy.x - s.width / 2f, xy.y - s.height / 2f), style)
            }
            accepted.firstOrNull()?.let { p ->
                val xy = proj.project(p)
                drawCircle(colors.dotFill, markerR + 1.5.dp.toPx(), Offset(xy.x, xy.y))
                drawCircle(TrackStartColor, markerR, Offset(xy.x, xy.y))
            }
            if (accepted.size >= 2) {
                val xy = proj.project(accepted.last())
                drawCircle(colors.dotFill, markerR + 1.5.dp.toPx(), Offset(xy.x, xy.y))
                drawCircle(TrackEndColor, markerR, Offset(xy.x, xy.y))
            }
        }

        // scale bar, bottom-left of the strip
        val bar = TrackGeometry.scaleBar(proj.metresPerPx, size.width * 0.45f)
        val x0 = pad / 2f
        val y = size.height - 3.dp.toPx()
        drawLine(colors.text, Offset(x0, y), Offset(x0 + bar.px, y), strokeWidth = w)
        drawLine(colors.text, Offset(x0, y - tick), Offset(x0, y), strokeWidth = w)
        drawLine(colors.text, Offset(x0 + bar.px, y - tick), Offset(x0 + bar.px, y), strokeWidth = w)
        val ls = measurer.measure(bar.label, style).size
        drawText(measurer, bar.label, Offset(x0 + bar.px / 2f - ls.width / 2f, y - tick - ls.height), style)

        // north arrow, bottom-right of the strip: "N" beside an upward arrow
        val ns = measurer.measure("N", style).size
        val ax = size.width - pad / 2f - tick
        val top = y - labelH - tick
        drawLine(colors.text, Offset(ax, y), Offset(ax, top), strokeWidth = w)
        drawLine(colors.text, Offset(ax - tick, top + tick), Offset(ax, top), strokeWidth = w)
        drawLine(colors.text, Offset(ax + tick, top + tick), Offset(ax, top), strokeWidth = w)
        drawText(measurer, "N", Offset(ax - tick - 4.dp.toPx() - ns.width, y - ns.height), style)
    }
}
