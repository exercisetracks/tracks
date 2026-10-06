// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val coordinates = coordinateLabel(point.lat, point.lon)
    var sprite by remember { mutableStateOf<SpriteSheet?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sprite = SpriteSheet.load(container) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
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
 * would be back by. Tapping a day asks the server for that day's hours, which
 * is a request worth making only when somebody asks the question.
 */
@Composable
private fun Weather(container: AppContainer, point: PointInfo, loading: Boolean) {
    val forecast = point.weather
    val now = forecast?.current
    var openDay by remember(point.lat, point.lon) { mutableStateOf<String?>(null) }
    var hours by remember { mutableStateOf<List<WeatherHour>>(emptyList()) }
    var loadingHours by remember { mutableStateOf(false) }

    LaunchedEffect(openDay) {
        val date = openDay ?: return@LaunchedEffect
        loadingHours = true
        hours = runCatching { container.client().pointHourly(point.lat, point.lon, date) }
            .getOrNull()?.hours.orEmpty()
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
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            days.forEachIndexed { index, day ->
                ForecastDay(
                    day = day,
                    today = index == 0,
                    open = openDay == day.date,
                    // Tapping the open day closes it, so the strip is never
                    // stuck expanded with no way back to the week.
                    onClick = { openDay = if (openDay == day.date) null else day.date },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        openDay?.let { date ->
            when {
                loadingHours -> Text(
                    "Loading hours…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                hours.isEmpty() -> Text(
                    "No hourly forecast for $date.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> HourStrip(hours)
            }
        }
    }
}

/**
 * A day's hours, as a chart rather than a row of numbers.
 *
 * Twenty-four readings printed as text is a table, and a table is the wrong
 * shape for the question being asked. Nobody opens this to look up the
 * temperature at 14:00; they open it to find the part of the day that is warm
 * and dry, which is a *shape* — and a shape is read in one glance from a curve
 * and lost entirely in a row of digits.
 *
 * So: temperature as a filled curve, rain as bars rising from the floor, and
 * the two on the same canvas because the answer is where they trade off. It
 * scrolls sideways because a day does not fit across a phone and must not be
 * allowed to grow the sheet downward — this opens inside a panel already
 * competing with the map.
 */
@Composable
private fun HourStrip(hours: List<WeatherHour>) {
    val temperatures = hours.mapNotNull { it.tempC }
    if (temperatures.isEmpty()) return

    val warm = Color(0xFFF97316)
    val rain = Color(0xFF0EA5E9)
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val hourWidth = HOUR_WIDTH
    val width = hourWidth * hours.size

    // The scale is the day's own range, not zero-based: a day between 12° and
    // 19° drawn from freezing is a flat line, which is exactly the wrong answer
    // for the one question this chart exists for.
    val low = temperatures.min()
    val high = temperatures.max()
    val span = (high - low).takeIf { it > 0.5 } ?: 1.0

    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Box(
            Modifier
                .width(width)
                .height(HOURS_HEIGHT)
                .clip(RoundedCornerShape(Tokens.Radius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / hours.size
                val plotTop = size.height * 0.30f
                val plotBottom = size.height * 0.74f
                fun x(index: Int) = step * (index + 0.5f)
                fun y(celsius: Double) =
                    plotBottom - ((celsius - low) / span).toFloat() * (plotBottom - plotTop)

                // Rain first, underneath: it is context for the curve rather
                // than a series competing with it.
                hours.forEachIndexed { index, hour ->
                    val chance = (hour.precipProbPct ?: 0.0).coerceIn(0.0, 100.0)
                    if (chance <= 0) return@forEachIndexed
                    val height = (size.height - plotBottom) * (chance / 100.0).toFloat()
                    drawRoundRect(
                        color = rain.copy(alpha = 0.30f + 0.5f * (chance / 100.0).toFloat()),
                        topLeft = Offset(x(index) - step * 0.30f, size.height - height),
                        size = Size(step * 0.60f, height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }

                val curve = Path()
                hours.forEachIndexed { index, hour ->
                    val celsius = hour.tempC ?: return@forEachIndexed
                    val point = Offset(x(index), y(celsius))
                    if (curve.isEmpty) curve.moveTo(point.x, point.y)
                    else curve.lineTo(point.x, point.y)
                }
                // Filled beneath, so the warm part of the day reads as a mass
                // rather than as a line that happens to be higher.
                val area = Path().apply {
                    addPath(curve)
                    lineTo(x(hours.lastIndex), size.height)
                    lineTo(x(0), size.height)
                    close()
                }
                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        listOf(warm.copy(alpha = 0.28f), warm.copy(alpha = 0.02f)),
                        startY = plotTop,
                        endY = size.height,
                    ),
                )
                drawPath(
                    curve,
                    color = warm,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                // Labels every third hour. All twenty-four collide at this
                // width, and a legible quarter of them is worth more than an
                // illegible whole.
                hours.forEachIndexed { index, hour ->
                    if (index % HOUR_LABEL_EVERY != 0) return@forEachIndexed
                    val text = measurer.measure(hourLabel(hour.time), labelStyle)
                    drawText(
                        text,
                        color = axis,
                        topLeft = Offset(
                            x(index) - text.size.width / 2f,
                            size.height - text.size.height - 1.dp.toPx(),
                        ),
                    )
                    hour.tempC?.let { celsius ->
                        val reading = measurer.measure(temperature(celsius), labelStyle)
                        drawText(
                            reading,
                            color = warm,
                            topLeft = Offset(
                                x(index) - reading.size.width / 2f,
                                (y(celsius) - reading.size.height - 3.dp.toPx())
                                    .coerceAtLeast(0f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Narrow enough that a day is two screens of scrolling, not five. */
private val HOUR_WIDTH = 26.dp
private val HOURS_HEIGHT = 92.dp

/** Every third hour carries a label; the rest would collide at this width. */
private const val HOUR_LABEL_EVERY = 3

/**
 * `2026-08-19T14:00` → `14`.
 *
 * A value with no time in it has no hour to show, so the tail of it is shown
 * instead — wrong-looking beats a blank cell in a strip of twenty-four, and it
 * makes a provider that changed its format visible rather than silent.
 */
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
            .clip(RoundedCornerShape(Tokens.Radius.md))
            .background(
                if (open) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) { vm.search(query, near) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // Random rather than always the same first swatch — a batch of tracks
    // saved in one sitting is easy to tell apart on the map without anyone
    // having had to think about colour at all, which is the point of a
    // preset row in the first place.
    var color by remember { mutableStateOf(TRACK_COLORS.random()) }

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
                ElevationProfile(points = route.profile, modifier = Modifier.fillMaxWidth())
            }

            if (naming) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Track name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    NeutralButton("Back", onClick = { naming = false })
                    PrimaryButton(if (route.saving) "Saving…" else "Save", onClick = { onSave(name, color) }, enabled = !route.saving)
                }
                // Chosen here rather than after saving, because the colour is
                // how you tell one track from another on the map and the moment
                // you have just drawn it is the moment you know which it is.
                ColorRow(selected = color, onPick = { color = it })
            } else {
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

                            route.waypoints.isEmpty() -> "Tap the map to start"
                            else -> "Tap again to extend"
                        },
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (route.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
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
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Discard this track",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    PrimaryButton("Save", onClick = { naming = true }, enabled = route.waypoints.size >= 2 && !route.saving)
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
fun ElevationProfile(points: List<RoutePoint>, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val scale = remember(points) {
        chartScale(points.minOf { it.elevationMetres }, points.maxOf { it.elevationMetres })
    }
    val format = remember(scale) { axisFormat(scale) }
    val gutter = remember(scale, labelStyle) { measureGutter(scale, measurer, labelStyle, format) }
    val total = points.last().metres.takeIf { it > 0 } ?: 1.0

    Canvas(modifier.height(PROFILE_HEIGHT)) {
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

        val path = Path().apply {
            points.forEachIndexed { index, point ->
                val px = x(point.metres)
                val py = y(point.elevationMetres)
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        // Filled under the line: a profile reads as ground rather than as a
        // trend when it has a solid mass beneath it.
        val area = Path().apply {
            addPath(path)
            lineTo(x(points.last().metres), size.height)
            lineTo(x(points.first().metres), size.height)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(
                listOf(line.copy(alpha = 0.30f), line.copy(alpha = 0.03f)),
            ),
        )
        drawPath(
            path,
            color = line,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private val PROFILE_HEIGHT = 78.dp
