// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracks.app.ui.components.TracksSwitch
import com.tracks.app.AppContainer
import com.tracks.app.ui.components.DangerButton
import com.tracks.app.ui.components.NeutralButton
import com.tracks.app.ui.components.PrimaryButton
import com.tracks.app.ui.components.TonalButton
import com.tracks.app.ui.components.axisFormat
import com.tracks.app.ui.components.chartScale
import com.tracks.app.ui.components.drawYAxis
import com.tracks.app.ui.components.measureGutter
import com.tracks.app.ui.theme.Tokens
import com.tracks.core.api.PoiHit
import com.tracks.core.api.PointInfo
import com.tracks.core.api.WeatherDay
import com.tracks.core.api.WeatherHour
import com.tracks.core.format.distance
import com.tracks.core.format.elapsed
import com.tracks.core.format.elevation
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng

/**
 * What the map itself knows about a tapped point.
 *
 * Read from the rendered vector tiles with `queryRenderedFeatures`, because
 * they are already on the screen: the public-land polygon under the tap and the
 * trails within a few pixels of it are drawn there, and a round trip to ask a
 * server what the device is currently displaying would be slower and could
 * disagree with what the user can see.
 */
data class PointSurroundings(
    val landName: String? = null,
    val landType: String? = null,
    val landOwnership: String? = null,
    /** Named trails near the tap, de-duplicated, nearest layer first. */
    val trails: List<String> = emptyList(),
)

/**
 * What is at the point you tapped.
 *
 * Elevation and the nearest named place, both of which come off the server —
 * it holds the DEM and the gazetteer. When it cannot answer, the sheet still
 * opens with the coordinates: the tap did land somewhere, and saying where
 * beats a sheet that refuses to appear.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PointSheet(
    container: AppContainer,
    point: PointInfo,
    surroundings: PointSurroundings = PointSurroundings(),
    /** Whether the forecast is still in flight — see [Weather]. */
    weatherLoading: Boolean = false,
    /** Saves this spot as a waypoint under the given name. */
    onSaveWaypoint: ((SavedSpot) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val coordinates = coordinateLabel(point.lat, point.lon)
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sprite = SpriteSheet.load(container) }

    // Non-modal, like a saved track's sheet: the map stays lit and can be
    // panned behind it — the spot it describes is on that map.
    ContentSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SHEET_PAD)
                .padding(bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
        ) {
            // Saving shares the title's line and comes first. It was at the
            // bottom, under the weather, which put the one *action* on this
            // sheet below three sections of reading — so marking a spot meant
            // scrolling past a week's forecast to find the button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    point.nearestPoi?.name ?: surroundings.landName ?: "This spot",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onSaveWaypoint != null && !saving) {
                    PrimaryButton("Save this spot", onClick = { saving = true })
                }
            }

            if (saving) {
                onSaveWaypoint?.let { save ->
                    SaveSpot(
                        sprite = sprite,
                        suggested = point.nearestPoi?.name
                            ?: surroundings.landName ?: "Waypoint",
                        onCancel = { saving = false },
                        onSave = { name, icon, color, toWatch ->
                            save(SavedSpot(name, icon, color, toWatch))
                            onDismiss()
                        },
                    )
                }
            }

            // Where, how high, and what it sits on — one line. These were three
            // stacked blocks with their own uppercase labels, which is a lot of
            // furniture around three short facts. The coordinates stay tappable
            // to copy, because they are the one thing here somebody sends to
            // another person and reading digits off a screen is how they get
            // dropped.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    coordinates,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(coordinates))
                    },
                )
                Text(
                    "· ${point.elevationMetres?.let { elevation(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                surroundings.landType?.let { type ->
                    Text(
                        "· ${type.replace('_', ' ').replaceFirstChar(Char::uppercase)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                surroundings.landOwnership?.let { OwnershipBadge(it) }
            }

            point.nearestPoi?.let { poi ->
                val kind = poi.kindDetail?.takeIf { it.isNotBlank() } ?: poi.kind
                Text(
                    buildString {
                        append(poi.name)
                        kind?.let { append(" · ${it.replace('_', ' ')}") }
                        poi.distanceMetres?.let { append(" · ${distance(it)} away") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (surroundings.trails.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    surroundings.trails.forEach { name ->
                        Surface(
                            shape = RoundedCornerShape(Tokens.Radius.md),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }

            Weather(container, point, weatherLoading)
        }
    }
}

/**
 * Now, the week, and any day of it hour by hour.
 *
 * The days were static text. On a map used to decide when to go somewhere that
 * is the wrong half of the answer — "Thursday: 18°/4°, 60%" does not say
 * whether the 60% is the dawn you were planning to walk in or the afternoon you
 * would be back by. Tapping a day asks Open-Meteo for that day's hours, which
 * is a request worth making only when somebody asks the question.
 */
@Composable
private fun Weather(container: AppContainer, point: PointInfo, loading: Boolean) {
    val forecast = point.weather
    val now = forecast?.current
    var openDay by remember(point.lat, point.lon) { mutableStateOf<String?>(null) }
    var hours by remember { mutableStateOf<List<WeatherHour>>(emptyList()) }
    var loadingHours by remember { mutableStateOf(false) }
    // Today's hours open at once: the chart under the days is what says the
    // days can be tapped, and an empty space under them said nothing.
    val firstDay = forecast?.daily?.firstOrNull()?.date
    LaunchedEffect(firstDay) { if (openDay == null && firstDay != null) openDay = firstDay }

    LaunchedEffect(openDay) {
        val date = openDay ?: return@LaunchedEffect
        loadingHours = true
        // The strip only exists when a forecast was allowed, but the switch can
        // be turned off while the sheet is open; check again before sending.
        hours = if (!container.weatherAllowed()) emptyList()
        else {
            val today = java.time.LocalDate.now()
            container.openMeteo.hourly(
                point.lat, point.lon, date,
                com.tracks.core.fit.decode.CivilDate(today.year, today.monthValue, today.dayOfMonth),
            ).hours
        }
        loadingHours = false
    }

    if (now == null) {
        Text(
            // The difference matters: one of these is worth waiting for and the
            // other is not, and the sheet now opens before either is known.
            when {
                loading -> "Fetching the forecast…"
                // Off by the user's own choice: say so and where to undo it,
                // or it reads as the server being broken.
                point.weatherDisabled -> "Weather is turned off. Turn it on in Settings → Privacy & connectivity."
                else -> "Forecast unavailable for here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(temperature(now.temperatureC), style = MaterialTheme.typography.titleLarge)
            Text(
                listOfNotNull(
                    now.apparentC?.let { "feels ${temperature(it)}" },
                    wmoLabel(now.weatherCode, now.isDay != 0),
                    now.windMps?.let { "${wind(it)} ${compass(now.windDirection)}".trim() },
                    now.humidityPct?.let { "${it.roundToInt()}% RH" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
            )
        }

        val days = forecast.daily.take(FORECAST_DAYS)
        if (days.isEmpty()) return@Column

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEachIndexed { index, day ->
                ForecastDay(
                    day = day,
                    today = index == 0,
                    open = openDay == day.date,
                    // A day is chosen, not toggled: one is always showing.
                    onClick = { openDay = day.date },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        openDay?.let { date ->
            Box(Modifier.fillMaxWidth().height(HOURS_HEIGHT + 22.dp), contentAlignment = Alignment.Center) {
                when {
                    loadingHours && hours.isEmpty() -> Text(
                        "Loading hours…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    hours.isEmpty() -> Text(
                        "No hourly forecast for $date.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> HourChart(hours, today = date == firstDay)
                }
            }
        }
    }
}

/**
 * A day's hours as two lines on one chart: temperature in orange, chance of
 * rain in blue, each on its own scale, across the width of the sheet.
 *
 * Nobody opens this to look up the temperature at 14:00; they open it to find
 * the part of the day that is warm and dry, which is a shape, and the shape is
 * where the two lines trade off. It fits the width rather than scrolling — a
 * strip wider than the phone hid the evening behind a swipe — and dragging
 * along it reads out the hour under the finger.
 *
 * Temperature is scaled to the day's own range: a day between 12° and 19°
 * drawn from freezing is a flat line. Rain is 0–100% as it stands. Hours are
 * labelled in the phone's own clock, 12- or 24-hour.
 */
@Composable
private fun HourChart(hours: List<WeatherHour>, today: Boolean) {
    val temperatures = hours.mapNotNull { it.tempC }
    if (temperatures.isEmpty()) return

    val warm = Color(0xFFF97316)
    val rain = Color(0xFF0EA5E9)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val is24 = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    val low = temperatures.min()
    val high = temperatures.max()
    val span = (high - low).takeIf { it > 0.5 } ?: 1.0
    val nowHour = if (today) java.time.LocalTime.now().hour else null
    var scrub by remember(hours) { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The legend doubles as the read-out: the hour under the finger, or
        // the day's range when nothing is touched.
        val shown = scrub?.let { hours.getOrNull(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shown != null) {
                Text(clockLabel(shown.time, is24, long = true), style = labelStyle, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(temperature(shown.tempC), style = labelStyle, color = warm)
                Text("${(shown.precipProbPct ?: 0.0).roundToInt()}% rain", style = labelStyle, color = rain)
            } else {
                Text("● Temp ${temperature(low)}–${temperature(high)}", style = labelStyle, color = warm)
                Text("● Rain ${(hours.mapNotNull { it.precipProbPct }.maxOrNull() ?: 0.0).roundToInt()}% max", style = labelStyle, color = rain)
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(HOURS_HEIGHT)
                .pointerInput(hours) {
                    fun at(x: Float) = ((x / size.width) * hours.size).toInt().coerceIn(0, hours.lastIndex)
                    detectDragGestures(
                        onDragStart = { scrub = at(it.x) },
                        onDragEnd = { scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ -> scrub = at(change.position.x) }
                },
        ) {
            val labelHeight = measurer.measure("12", labelStyle).size.height.toFloat()
            val top = 4.dp.toPx()
            val bottom = size.height - labelHeight - 4.dp.toPx()
            val step = size.width / hours.size
            fun x(i: Int) = step * (i + 0.5f)
            fun yTemp(c: Double) = bottom - ((c - low) / span).toFloat() * (bottom - top)
            fun yRain(p: Double) = bottom - (p.coerceIn(0.0, 100.0) / 100.0).toFloat() * (bottom - top)

            // Three faint guides; the lines carry the reading.
            for (f in listOf(0f, 0.5f, 1f)) {
                val y = top + (bottom - top) * f
                drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            nowHour?.let { h ->
                hours.indexOfFirst { it.time.substringAfter('T').take(2).toIntOrNull() == h }.takeIf { it >= 0 }?.let { i ->
                    drawLine(axis.copy(alpha = 0.6f), Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
            }

            fun line(values: List<Double?>, y: (Double) -> Float): Path = Path().apply {
                var started = false
                values.forEachIndexed { i, v ->
                    v ?: return@forEachIndexed
                    if (!started) { moveTo(x(i), y(v)); started = true } else lineTo(x(i), y(v))
                }
            }
            fun fill(path: Path, color: Color) {
                val area = Path().apply {
                    addPath(path)
                    lineTo(x(hours.lastIndex), bottom)
                    lineTo(x(0), bottom)
                    close()
                }
                drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.0f)), startY = top, endY = bottom))
            }
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val rainLine = line(hours.map { it.precipProbPct ?: 0.0 }, ::yRain)
            fill(rainLine, rain)
            drawPath(rainLine, rain, style = stroke)
            val tempLine = line(hours.map { it.tempC }, ::yTemp)
            fill(tempLine, warm)
            drawPath(tempLine, warm, style = stroke)

            // As many hour labels as fit without touching.
            val sample = measurer.measure(clockLabel("T12:00", is24), labelStyle).size.width
            val every = listOf(1, 2, 3, 4, 6, 12).first { it * step >= sample + 6.dp.toPx() }
            hours.forEachIndexed { i, hour ->
                if (i % every != 0) return@forEachIndexed
                val text = measurer.measure(clockLabel(hour.time, is24), labelStyle)
                val left = (x(i) - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                drawText(text, color = axis, topLeft = Offset(left, size.height - text.size.height))
            }

            scrub?.let { i ->
                drawLine(axis, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 1.5f)
                hours[i].tempC?.let { drawCircle(warm, 4.dp.toPx(), Offset(x(i), yTemp(it))) }
                drawCircle(rain, 4.dp.toPx(), Offset(x(i), yRain(hours[i].precipProbPct ?: 0.0)))
            }
        }
    }
}

private val HOURS_HEIGHT = 112.dp

/**
 * `2026-08-19T14:00` as the phone tells time: `2p` / `2 PM` in 12-hour, `14`
 * in 24. A value with no time shows its tail, so a provider that changed its
 * format is visible rather than silent.
 */
internal fun clockLabel(iso: String, is24: Boolean, long: Boolean = false): String {
    val hour = iso.substringAfter('T', "").take(2).toIntOrNull() ?: return iso.takeLast(5)
    if (is24) return if (long) "%02d:00".format(hour) else "%02d".format(hour)
    val h = if (hour % 12 == 0) 12 else hour % 12
    val am = hour < 12
    return if (long) "$h ${if (am) "AM" else "PM"}" else "$h${if (am) "a" else "p"}"
}

/** The hour of an ISO time, as 24-hour digits. */
internal fun hourLabel(iso: String): String =
    iso.substringAfter('T', "").take(2).ifBlank { iso.takeLast(5) }

@Composable
private fun OwnershipBadge(ownership: String) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.base),
        color = when (ownership.lowercase()) {
            "public" -> Color(0xFF16A34A).copy(alpha = 0.20f)
            "restricted" -> Color(0xFFDC2626).copy(alpha = 0.20f)
            else -> Color(0xFFF59E0B).copy(alpha = 0.20f)
        },
    ) {
        Text(
            ownership,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ForecastDay(
    day: WeatherDay,
    today: Boolean,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Tokens.Radius.lg))
            .background(
                if (open) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            if (today) "Today" else dayOfWeek(day.date),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            temperature(day.tempMaxC),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Text(
            temperature(day.tempMinC),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        // Only when there is something to say. A row of "0%" under every dry
        // day is noise, and the one day that reads 60% should stand out.
        day.precipProbPct?.takeIf { it > 0 }?.let {
            Text(
                "${it.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF0EA5E9),
                maxLines = 1,
            )
        }
    }
}

/**
 * A WMO weather code as words.
 *
 * The same table the web app decodes with, because the two clients must not
 * describe the same sky differently. Open-Meteo's codes are ranges rather than
 * a flat enum — 51 through 57 are all drizzle, varying by intensity and
 * freezing — and the intensity is not worth a word here.
 */
internal fun wmoLabel(code: Int?, isDay: Boolean): String = when (code) {
    null -> "—"
    0 -> if (isDay) "Clear" else "Clear night"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Rain showers"
    85, 86 -> "Snow showers"
    in 95..99 -> "Thunderstorm"
    else -> "—"
}

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

internal fun compass(degrees: Double?): String =
    degrees?.let { COMPASS[(((it / 45).roundToInt()) % 8 + 8) % 8] } ?: ""

private fun temperature(celsius: Double?): String =
    celsius?.let { "${(it * 9 / 5 + 32).roundToInt()}°" } ?: "—"

private fun wind(mps: Double): String = "${(mps * 2.23694).roundToInt()} mph"

private fun dayOfWeek(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).dayOfWeek
        .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
}.getOrDefault(iso.takeLast(5))

/** A week, which is what the server sends and what fits across a phone. */
private const val FORECAST_DAYS = 7

/**
 * Search for a place and fly to it.
 *
 * Results are biased toward the middle of the map rather than filtered to it,
 * so searching from a trailhead puts what is nearby first without hiding
 * anywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    vm: MapToolsViewModel,
    state: MapToolsUiState,
    near: LatLng?,
    onPick: (PoiHit) -> Unit,
    onDismiss: () -> Unit,
    /** A category query: mark every one in view on the map, and close. */
    onMarkAll: (String) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) { vm.search(query, near) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, dragHandle = { CompactDragHandle() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search places") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // A category ("campgrounds", "gas stations") is a request to see
            // them on the map, as a map search does, more than a list of ten.
            state.searchCategory?.takeIf { query.isNotBlank() && !state.searching }?.let { category ->
                TonalButton(
                    "Show all $category on the map",
                    onClick = { onMarkAll(query.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                state.searching -> Text(
                    "Searching…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                query.isNotBlank() && state.searchResults.isEmpty() -> Text(
                    "Nothing found. The gazetteer is on the server, so this needs a " +
                        "connection even inside a downloaded area.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(
                Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.searchResults, key = { "${it.name}${it.lat}${it.lng}" }) { hit ->
                    // A list row, not a button: tapping anywhere on the result
                    // picks it, the way every other list in the app works.
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(Tokens.Radius.lg))
                            .clickable { onPick(hit) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(hit.name, style = MaterialTheme.typography.bodyLarge)
                            if (hit.description.isNotBlank() && hit.description != hit.name) {
                                Text(
                                    hit.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the sheet collected before a place was saved. */
data class SavedSpot(
    val name: String,
    val icon: String,
    val color: String,
    val sendToWatch: Boolean,
)

/**
 * Keep this spot, with a name somebody chose.
 *
 * This was one button that guessed a name from the nearest POI and saved
 * immediately, which is wrong in the case that matters: a place is worth
 * marking precisely when it is *not* the labelled thing next to it — the
 * campsite below the named summit, the spring off the named trail. The guess is
 * still the starting point, because it is usually close and always better than
 * an empty field.
 *
 * Collapsed until asked for. The point sheet's job is telling you what is here,
 * and a form open by default under every tap would push the weather off the
 * screen for the nine taps out of ten that are only looking.
 */
@Composable
private fun SaveSpot(
    sprite: SpriteSheet?,
    suggested: String,
    onCancel: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit,
) {
    var name by remember(suggested) { mutableStateOf(suggested) }
    var icon by remember { mutableStateOf(WAYPOINT_ICONS.first()) }
    // Ink, not the track palette's first entry. A place's symbol is drawn in
    // this colour now, so the default has to be one a symbol reads as.
    var color by remember { mutableStateOf(DEFAULT_WAYPOINT_COLOR) }
    var toWatch by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        ColorRow(selected = color, onPick = { color = it })
        IconRow(sprite = sprite, selected = icon, onPick = { icon = it })

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (com.tracks.app.ui.components.LocalHasDevice.current) {
                Text(
                    "To watch",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TracksSwitch(
                    checked = toWatch,
                    onCheckedChange = { toWatch = it },
                    modifier = Modifier.scale(SWITCH_SCALE),
                )
            }
            Spacer(Modifier.weight(1f))
            NeutralButton("Cancel", onClick = onCancel)
            PrimaryButton("Save", onClick = { onSave(name.trim(), icon, color, toWatch) }, enabled = name.isNotBlank())
        }
    }
}

/**
 * The route builder's bar.
 *
 * Sits over the map rather than in a sheet for the same reason the download
 * selector does: you are working *on* the map, and a modal covering it would
 * hide the thing being built. Which is also why it is one row. Two rows of
 * controls plus a profile chart was a third of a phone screen spent describing
 * a line you could no longer see.
 *
 * There are two ways out and no third. Save keeps the line as a track; cancel
 * throws it away. A "done" button used to sit between them, closing the tool
 * while leaving the draft alive — a state with a line on the map and nothing
 * able to save it.
 */
@Composable
fun RouteBar(
    route: RouteDraft,
    onSave: (String, String) -> Unit,
    onSnapToTrails: (Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onEditName: (String) -> Unit = {},
    onEditColor: (String) -> Unit = {},
    onEditIcon: (String) -> Unit = {},
    sprite: SpriteSheet? = null,
    onProfileScrub: ((Double?) -> Unit)? = null,
    onSimplify: (Int) -> Unit = {},
    collections: List<com.tracks.core.api.TrackCollection> = emptyList(),
    onEditCollection: (Int?) -> Unit = {},
    onNewCollection: (String) -> Unit = {},
) {
    val editingPlace = route.editingPlace != null
    val editing = route.editing != null || editingPlace
    // One handle drawn fresh is a place; two or more, a track. The editor
    // shows what it will save as, and Save is the only step.
    val isPlace = editingPlace || (route.editing == null && route.waypoints.size == 1)
    val drawing = !editing && route.waypoints.isNotEmpty()

    Surface(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(Tokens.Radius.xl),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The shape of the climbing, not just its total. "↑340 m" says
            // nothing about whether that is one steady pull or four sharp ones,
            // which is the difference between a route somebody enjoys and one
            // they turn round on. Drawn for un-snapped lines too, from ground
            // the server samples under them.
            if (route.profile.size > 1) {
                ElevationProfile(points = route.profile, modifier = Modifier.fillMaxWidth(), onScrub = onProfileScrub)
            }

            // The editor: everything about the track or place in one place,
            // committed together by Save — for a new drawing as for an edit,
            // so naming it is not a second step after saving. The line or pin
            // is edited on the map.
            if (editing || drawing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = route.editingName.orEmpty(),
                        onValueChange = onEditName,
                        label = { Text(if (isPlace) "Place name" else "Track name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    CollectionDropdown(
                        current = route.editCollection,
                        collections = collections,
                        onPick = onEditCollection,
                        onCreate = onNewCollection,
                    )
                }
                ColorRow(selected = route.editColor ?: TRACK_COLORS.first(), onPick = onEditColor)
                // Refine a recorded or imported line, as on the desktop: fewer
                // points, same shape, previewed on the map as it moves.
                route.originalLine?.let { original ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (route.simplifyPercent == 0) "Simplify" else "Simplify ${route.simplifyPercent}% · ${route.baseLine?.size ?: original.size} pts",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        androidx.compose.material3.Slider(
                            value = route.simplifyPercent.toFloat(),
                            onValueChange = { onSimplify(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (isPlace) {
                    IconRow(
                        sprite = sprite,
                        selected = route.editIcon ?: "marker",
                        color = route.editColor,
                        onPick = onEditIcon,
                    )
                }
            }

            run {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        when {
                            route.error != null -> route.error
                            route.snapping -> "Following the trails…"
                            route.metres != null -> buildString {
                                append(distance(route.metres))
                                route.ascentMetres?.let { append(" · ↑${elevation(it)}") }
                                // BRouter's estimate, labelled as one so nobody
                                // plans a turnaround on it.
                                route.seconds?.let { append(" · ~${elapsed(it)}") }
                            }

                            editingPlace -> "Drag the pin, or tap to move it"
                            route.waypoints.isEmpty() -> "Tap the map to start"
                            isPlace -> "A place — tap again to make a track"
                            else -> "Tap again to extend"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (route.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (!isPlace) {
                        Text(
                            "Snap",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TracksSwitch(
                            checked = route.snapToTrails,
                            onCheckedChange = onSnapToTrails,
                            modifier = Modifier.scale(SWITCH_SCALE),
                        )
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Discard this track",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    PrimaryButton(
                        if (route.saving) "Saving…" else "Save",
                        onClick = { onSave("", "") },
                        // A place has its one point; a track being edited needs
                        // two; a new drawing is a place at one, a track at two.
                        enabled = when {
                            editingPlace -> route.waypoints.size == 1
                            route.editing != null -> route.waypoints.size >= 2
                            else -> route.waypoints.isNotEmpty()
                        } && !route.saving,
                    )
                }
            }
        }
    }
}

/**
 * What one handle of a half-drawn route can do.
 *
 * A menu of one, which is the honest size of it: a point that is in the wrong
 * place gets dragged, and a point that should not exist gets deleted. Anchored
 * to the handle rather than parked in the bar, because "this one" is the whole
 * content of the question and a bar cannot say which.
 */
@Composable
fun HandleMenu(onDelete: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Tokens.Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DangerButton("Delete point", onClick = onDelete, small = true)
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * Degrees with a hemisphere letter, magnitudes only.
 *
 * The letter carries the sign; printing both gives "-105.15°W", which states it
 * twice and names the wrong side of the planet. The server's own fallback had
 * exactly this bug.
 */
internal fun coordinateLabel(lat: Double, lon: Double): String {
    val ns = if (lat >= 0) "N" else "S"
    val ew = if (lon >= 0) "E" else "W"
    return "%.5f°%s, %.5f°%s".format(kotlin.math.abs(lat), ns, kotlin.math.abs(lon), ew)
}


/**
 * A route's height against distance.
 *
 * Drawn by hand rather than with the chart library the dashboard uses: this one
 * lives inside a bar overlaying the map and has to stay small and cheap to
 * redraw, since it is rebuilt every time a waypoint is added. It shares the
 * app's axis code so its labels round the same way every other chart's do.
 *
 * The y axis deliberately does not anchor at zero. A route between 2,900 and
 * 3,240 metres drawn from sea level is a flat line at the top of the box, which
 * is precisely the wrong answer for the one question this chart exists for.
 */
@Composable
fun ElevationProfile(
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
    /**
     * Distance along the line under the finger while pressing on the chart, and
     * null when it lifts — so the map can mark the same spot on the track.
     */
    onScrub: ((Double?) -> Unit)? = null,
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val scale = remember(points) {
        // At least 100 m either side of the median: a nearly flat track drawn
        // to fill the box looked like mountains, which it is not.
        val sorted = points.map { it.elevationMetres }.sorted()
        val median = sorted[sorted.size / 2]
        chartScale(minOf(sorted.first(), median - FLAT_MARGIN_M), maxOf(sorted.last(), median + FLAT_MARGIN_M))
    }
    val format = remember(scale) { axisFormat(scale) }
    val gutter = remember(scale, labelStyle) { measureGutter(scale, measurer, labelStyle, format) }
    val total = points.last().metres.takeIf { it > 0 } ?: 1.0
    var cursor by remember { mutableStateOf<Double?>(null) }

    Canvas(
        modifier
            .height(PROFILE_HEIGHT)
            .then(
                if (onScrub == null) Modifier else Modifier.pointerInput(points, gutter) {
                    fun at(px: Float): Double {
                        val plotWidth = (size.width - gutter).coerceAtLeast(1f)
                        return (((px - gutter) / plotWidth).coerceIn(0f, 1f) * total)
                    }
                    detectDragGestures(
                        onDragStart = { cursor = at(it.x); onScrub(cursor) },
                        onDragEnd = { cursor = null; onScrub(null) },
                        onDragCancel = { cursor = null; onScrub(null) },
                        onDrag = { change, _ -> cursor = at(change.position.x); onScrub(cursor) },
                    )
                },
            ),
    ) {
        drawYAxis(
            scale = scale,
            measurer = measurer,
            style = labelStyle,
            labelColor = axisColor,
            gridColor = gridColor,
            plotHeight = size.height,
            gutter = gutter,
            format = format,
        )
        val plotWidth = (size.width - gutter).coerceAtLeast(1f)
        fun x(metres: Double) = gutter + plotWidth * (metres / total).toFloat()
        fun y(elevation: Double) = size.height * (1f - scale.fraction(elevation))

        // Ground under the line, neutral; the line carries the steepness.
        val area = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(x(point.metres), y(point.elevationMetres))
                else lineTo(x(point.metres), y(point.elevationMetres))
            }
            lineTo(x(points.last().metres), size.height)
            lineTo(x(points.first().metres), size.height)
            close()
        }
        drawPath(area, brush = Brush.verticalGradient(listOf(axisColor.copy(alpha = 0.22f), axisColor.copy(alpha = 0.03f))))

        // Each piece coloured by how steep it is, green to red, so the hard
        // parts of a route are the ones that stand out.
        val stroke = 2.5.dp.toPx()
        for (k in 1 until points.size) {
            val a = points[k - 1]; val b = points[k]
            val run = (b.metres - a.metres).coerceAtLeast(1.0)
            val grade = kotlin.math.abs(b.elevationMetres - a.elevationMetres) / run
            drawLine(
                gradeColor(grade),
                Offset(x(a.metres), y(a.elevationMetres)),
                Offset(x(b.metres), y(b.elevationMetres)),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        cursor?.let { d ->
            val cx = x(d)
            drawLine(cursorColor.copy(alpha = 0.6f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
            val e = elevationAt(points, d)
            drawCircle(cursorColor, radius = 4.dp.toPx(), center = Offset(cx, y(e)))
        }
    }
}

/** Green when flat, yellow around 8%, red from 15%. */
internal fun gradeColor(grade: Double): androidx.compose.ui.graphics.Color {
    val t = (grade / 0.15).coerceIn(0.0, 1.0).toFloat()
    val green = androidx.compose.ui.graphics.Color(0xFF16A34A)
    val yellow = androidx.compose.ui.graphics.Color(0xFFEAB308)
    val red = androidx.compose.ui.graphics.Color(0xFFDC2626)
    return if (t < 0.53f) androidx.compose.ui.graphics.lerp(green, yellow, t / 0.53f)
    else androidx.compose.ui.graphics.lerp(yellow, red, (t - 0.53f) / 0.47f)
}

private fun elevationAt(points: List<RoutePoint>, metres: Double): Double {
    val k = points.indexOfFirst { it.metres >= metres }
    if (k <= 0) return points.first().elevationMetres
    val a = points[k - 1]; val b = points[k]
    val t = ((metres - a.metres) / (b.metres - a.metres).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
    return a.elevationMetres + (b.elevationMetres - a.elevationMetres) * t
}

private const val FLAT_MARGIN_M = 100.0

private val PROFILE_HEIGHT = 78.dp
