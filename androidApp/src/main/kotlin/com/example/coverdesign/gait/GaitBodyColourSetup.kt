package com.example.coverdesign.gait

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.coverdesign.ui.theme.AppTheme
import com.example.coverdesign.ui.theme.GaitOverlayBlack
import com.example.coverdesign.ui.theme.GaitOverlayWhite
import com.example.coverdesign.ui.theme.GaitPhotoBackground
import com.example.coverdesign.ui.theme.GaitSelectionCyan
import com.example.coverdesign.ui.theme.GaitTransparent
import kotlin.math.min

/** Memory-only selection of real paper centres. Pose positions never enter this workflow. */
@Composable
internal fun GaitBodyColourSetup(
    snapshot: GaitColourSnapshot,
    calibration: MarkerColorCalibration,
    onApply: (MarkerColorCalibration, Map<MarkerColor, Color>) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    retakeLabel: String = "Retake photo"
) {
    val groups = remember(snapshot) {
        listOf(MarkerColor.SHOULDERS, MarkerColor.PELVIS) +
            if (snapshot.view == GaitView.FRONT) listOf(MarkerColor.KNEES) else listOf(MarkerColor.C7, MarkerColor.COCCYX)
    }
    val bitmap = remember(snapshot) {
        Bitmap.createBitmap(snapshot.argb, snapshot.width, snapshot.height, Bitmap.Config.ARGB_8888)
    }
    DisposableEffect(bitmap) { onDispose { if (!bitmap.isRecycled) bitmap.recycle() } }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var selected by remember(snapshot) { mutableStateOf(groups.first()) }
    var points by remember(snapshot) { mutableStateOf<Map<MarkerColor, List<MarkerPoint>>>(emptyMap()) }
    var samples by remember(snapshot) { mutableStateOf<Map<MarkerColor, MarkerColorSampleResult>>(emptyMap()) }
    var editing by remember(snapshot) { mutableStateOf<Int?>(null) }
    var message by remember(snapshot) { mutableStateOf<String?>(null) }
    var lightingTolerance by remember(snapshot, calibration) { mutableStateOf(calibration.lightingTolerance) }
    var zoom by remember(snapshot) { mutableFloatStateOf(1f) }
    var pan by remember(snapshot) { mutableStateOf(Offset.Zero) }
    var viewport by remember(snapshot) { mutableStateOf(IntSize.Zero) }
    val guidanceScroll = rememberScrollState()
    LaunchedEffect(selected) { guidanceScroll.scrollTo(0) }
    val selectedPoints = points[selected].orEmpty()
    val requiredPoints = selected.bodyPointCount
    val allSampled = groups.all { samples[it]?.valid == true }
    val candidate = if (allSampled) {
        // Do not test collisions against a half-replaced palette: stage every required group first.
        calibration.withLightingTolerance(lightingTolerance)
            .withViewTargets(snapshot.view, groups.associateWith { samples.getValue(it).target!! })
    } else null
    val paletteProblem = candidate?.problemFor(snapshot.view)
    val canApply = allSampled && candidate?.readyFor(snapshot.view) == true

    fun scale(forZoom: Float = zoom): Float = if (viewport.width > 0 && viewport.height > 0)
        min(viewport.width.toFloat() / snapshot.width, viewport.height.toFloat() / snapshot.height) * forZoom else 0f
    fun clampPan(value: Offset, forZoom: Float = zoom): Offset {
        val s = scale(forZoom)
        val maxX = ((snapshot.width * s - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((snapshot.height * s - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    fun selectPoint(tap: Offset) {
        val s = scale()
        if (s <= 0) return
        val left = (viewport.width - snapshot.width * s) / 2 + pan.x
        val top = (viewport.height - snapshot.height * s) / 2 + pan.y
        val px = (tap.x - left) / s
        val py = (tap.y - top) / s
        if (px < 0 || py < 0 || px >= snapshot.width || py >= snapshot.height) {
            message = "Tap inside the photograph, on coloured paper."
            return
        }
        val current = points[selected].orEmpty()
        val replacement = editing
        if (current.size == selected.bodyPointCount && replacement == null) {
            message = "Choose a point below to replace it, or reset this group."
            return
        }
        val point = MarkerPoint(px.toDouble() / snapshot.width, py.toDouble() / snapshot.height)
        val next = current.toMutableList().apply {
            if (replacement != null && replacement in indices) this[replacement] = point else add(point)
        }.toList()
        points = points + (selected to next)
        samples = samples - selected
        editing = null
        message = null
        if (next.size == selected.bodyPointCount) {
            val sample = MarkerBodyColourSample.capture(snapshot, selected, next)
            samples = samples + (selected to sample)
            if (!sample.valid) message = sample.message
            else {
                val nextGroup = groups.firstOrNull { samples[it]?.valid != true }
                if (nextGroup != null) selected = nextGroup
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Text("Choose the real marker centres", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(GaitPhotoBackground)) {
            Canvas(Modifier.fillMaxSize().testTag("body-colour-viewport")
                .semantics { contentDescription = "Still photograph. Tap marker paper centres; drag to move the zoomed photograph." }
                .onSizeChanged { viewport = it; pan = Offset.Zero }
                .pointerInput(snapshot, zoom, viewport, selected) { detectTapGestures(onTap = ::selectPoint) }
                .pointerInput(snapshot, zoom, viewport) {
                    detectDragGestures { change, amount ->
                        change.consume()
                        pan = clampPan(pan + amount)
                    }
                }) {
                val s = scale()
                val left = (size.width - snapshot.width * s) / 2 + pan.x
                val top = (size.height - snapshot.height * s) / 2 + pan.y
                withTransform({ translate(left, top); scale(s, s, Offset.Zero) }) {
                    drawImage(image, srcSize = IntSize(snapshot.width, snapshot.height),
                        dstSize = IntSize(snapshot.width, snapshot.height), filterQuality = FilterQuality.Medium)
                }
                points.forEach { (group, selectedPaper) ->
                    selectedPaper.forEach { point ->
                        val cx = (point.x * snapshot.width).toInt()
                        val cy = (point.y * snapshot.height).toInt()
                        val centre = Offset(left + (cx + .5f) * s, top + (cy + .5f) * s)
                        val colour = if (group == selected) GaitSelectionCyan else GaitOverlayWhite
                        val arm = 9.dp.toPx()
                        drawLine(GaitOverlayBlack, centre - Offset(arm, 0f), centre + Offset(arm, 0f), 3.dp.toPx())
                        drawLine(GaitOverlayBlack, centre - Offset(0f, arm), centre + Offset(0f, arm), 3.dp.toPx())
                        drawLine(colour, centre - Offset(arm, 0f), centre + Offset(arm, 0f), 1.dp.toPx())
                        drawLine(colour, centre - Offset(0f, arm), centre + Offset(0f, arm), 1.dp.toPx())
                        drawRect(colour, centre - Offset(1.5f * s, 1.5f * s), Size(3f * s, 3f * s), style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4, 8).forEach { level ->
                FilterChip(selected = zoom == level.toFloat(),
                    onClick = { zoom = level.toFloat(); pan = clampPan(pan, zoom) },
                    label = { Text("${level}×") }, modifier = Modifier.weight(1f).testTag("body-zoom-$level"))
            }
        }
        Column(Modifier.fillMaxWidth().weight(.65f).verticalScroll(guidanceScroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${selected.bodyLabel}: ${selectedPoints.size}/$requiredPoints centres selected",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("body-selection-progress"))
            Text(if (editing != null) "Tap a new centre for point ${editing!! + 1}."
                else if (requiredPoints == 2) "Tap the centre of each paper on opposite sides of the body. Zoom in, then drag if needed."
                else "Tap the centre of the ${selected.bodyLabel} paper. Zoom in, then drag if needed.",
                style = MaterialTheme.typography.bodySmall)
            groups.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { group ->
                        val captured = samples[group]?.valid == true
                        OutlinedButton(onClick = { selected = group; editing = null; message = samples[group]?.takeIf { !it.valid }?.message },
                            modifier = Modifier.weight(1f).testTag("body-group-${group.name}"),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected == group) MaterialTheme.colorScheme.primaryContainer else GaitTransparent)) {
                            if (captured) Box(Modifier.size(12.dp).background(Color(samples.getValue(group).argb), MaterialTheme.shapes.extraSmall)
                                .testTag("body-swatch-${group.name}"))
                            Text("${if (captured) "  " else ""}${group.bodyLabel} ${if (captured) "✓" else "${points[group].orEmpty().size}/${group.bodyPointCount}"}")
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(requiredPoints) { index ->
                    OutlinedButton(onClick = { editing = index; message = null }, enabled = index < selectedPoints.size,
                        modifier = Modifier.weight(1f).testTag("body-point-$index")) { Text("Point ${index + 1}") }
                }
                TextButton(onClick = {
                    points = points + (selected to selectedPoints.dropLast(1)); samples = samples - selected; editing = null; message = null
                }, enabled = selectedPoints.isNotEmpty(), modifier = Modifier.testTag("body-undo")) { Text("Undo") }
            }
            TextButton(onClick = { points = points - selected; samples = samples - selected; editing = null; message = null },
                enabled = selectedPoints.isNotEmpty(), modifier = Modifier.testTag("body-reset-group")) { Text("Reset this group") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("body-sample-message")) }
            paletteProblem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("body-palette-problem")) }
            Text("Keep the sample square inside the coloured paper, away from edges, skin and clothing. The photograph stays in memory.",
                style = MaterialTheme.typography.bodySmall)
            GaitLightingControls(lightingTolerance, { lightingTolerance = it })
            if (snapshot.view == GaitView.BACK) Text(
                "Back markers allow small height offsets for self-attachment. Keep the pelvic papers on opposite sides, C7 at the neck base and the tailbone paper lower in the centre. Placement errors still affect the measured angles.",
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { candidate?.takeIf { canApply }?.let { palette ->
                onApply(palette, groups.associateWith { Color(samples.getValue(it).argb) })
            } }, enabled = canApply, modifier = Modifier.fillMaxWidth().testTag("body-apply")) {
                Text("Apply marker colours")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f).testTag("body-retake")) { Text(retakeLabel) }
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f).testTag("body-cancel")) { Text("Cancel") }
            }
        }
    }
}

private val MarkerColor.bodyPointCount: Int get() = when (this) {
    MarkerColor.SHOULDERS, MarkerColor.PELVIS, MarkerColor.KNEES -> 2
    MarkerColor.C7, MarkerColor.COCCYX -> 1
}

private val MarkerColor.bodyLabel: String get() = when (this) {
    MarkerColor.SHOULDERS -> "Shoulders"
    MarkerColor.PELVIS -> "Pelvis"
    MarkerColor.KNEES -> "Knees"
    MarkerColor.C7 -> "C7"
    MarkerColor.COCCYX -> "Tailbone"
}

@Preview(name = "On-body colour editor", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun GaitBodyColourSetupPreview() {
    // A neutral in-memory picture keeps this layout preview independent of patient media.
    val snapshot = remember {
        GaitColourSnapshot(IntArray(300 * 600) { GaitPhotoBackground.toArgb() },
            300, 600, timestampMs = 0L, view = GaitView.FRONT)
    }
    AppTheme {
        GaitBodyColourSetup(snapshot, MarkerColorCalibration(), onApply = { _, _ -> },
            onRetake = {}, onCancel = {})
    }
}
