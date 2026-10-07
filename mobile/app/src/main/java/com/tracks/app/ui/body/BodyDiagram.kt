// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.body

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import com.tracks.core.spec.BODY_VIEWER_REGIONS
import com.tracks.core.spec.BodyArt
import com.tracks.core.spec.FEMALE_BACK
import com.tracks.core.spec.FEMALE_FRONT
import com.tracks.core.spec.MALE_BACK
import com.tracks.core.spec.MALE_FRONT
import kotlin.math.max
import kotlin.math.min

/** Which way the body is facing. A region appears on exactly one of the two. */
enum class BodyView { Front, Back }

enum class BodyGender { Male, Female }

/**
 * The model every diagram draws unless told otherwise — the user's biological
 * sex from Settings, provided at the root (TracksNavHost). A local rather than
 * a parameter, because the diagrams sit three or four calls deep in screens
 * that otherwise know nothing about the profile.
 */
val LocalBodyGender = androidx.compose.runtime.compositionLocalOf { BodyGender.Male }

/**
 * The anatomical body diagram, shaded by how hard each muscle worked.
 *
 * ## The artwork is not ours and not hand-drawn
 *
 * The paths come from react-muscle-highlighter (MIT), generated into
 * `core.spec.BodyArtData` by `frontend/scripts/build-body-art.mjs`. The web app
 * renders the very same path strings through the React component, so the two
 * diagrams are one drawing with two renderers rather than two drawings that
 * happen to agree today. Drawing our own would have meant worse anatomy that
 * then disagreed with the browser; hosting React in a WebView would have meant
 * a UI framework in the APK to render one picture.
 *
 * ## Three vocabularies, one collapse
 *
 * A muscle key (`quads`), a body region (`quads_l`), and an artwork part
 * (`quadriceps`) are three different things, and the artwork is the coarsest —
 * one `quadriceps` shape covers what Tracks measures as quads *and* hip
 * flexors. So a part takes the strongest activation of everything that maps
 * onto it: shading it by the weakest would make a hard leg day look easy, and
 * averaging would invent a number nothing measured. The mapping is generated
 * from `spec/muscle_groups.yaml` and shared with the browser.
 */
@Composable
fun BodyDiagram(
    /** Muscle key to 0..1, as `computeMuscleActivation` produces. */
    activation: Map<String, Float>,
    view: BodyView,
    modifier: Modifier = Modifier,
    gender: BodyGender = LocalBodyGender.current,
    /** Highlighted regardless of activation — the picker's selection. */
    selected: Set<String> = emptySet(),
    /** Non-null makes the diagram tappable, reporting the muscle key tapped. */
    onMuscleTap: ((String) -> Unit)? = null,
) {
    val art = artFor(view, gender)
    val figure = remember(art) { figureFor(art) }
    val shapes = figure.shapes

    val fills = partFills(activation, view, selected)

    // The tap handler below outlives recompositions (pointerInput is keyed on
    // the artwork only), so it must read the *current* callback. Capturing the
    // first one froze the selection it closed over: tapping a chosen muscle a
    // second time "toggled" it against the original empty set and re-added it,
    // so a muscle could never be deselected by tapping it again.
    val onTap by rememberUpdatedState(onMuscleTap)
    Canvas(
        modifier = if (onMuscleTap == null) modifier else modifier.pointerInput(art) {
            detectTapGestures { offset ->
                val muscle = muscleAt(offset, size.width, size.height, figure, view)
                if (muscle != null) onTap?.invoke(muscle)
            }
        }
    ) {
        val box = figure.box
        val scale = fitScale(box, size.width, size.height)
        // Centred, then shifted by the drawing's origin. The male *back* is
        // drawn at x >= 724 and the female bodies have offsets of their own —
        // without the translate they render entirely off-canvas.
        translate(
            left = (size.width - box.width * scale) / 2f,
            top = (size.height - box.height * scale) / 2f,
        ) {
            scale(scale, pivot = Offset.Zero) {
                translate(left = -box.left, top = -box.top) {
                    drawBody(shapes, fills, scale)
                }
            }
        }
    }
}

private fun DrawScope.drawBody(
    shapes: Map<String, List<Path>>,
    fills: Map<String, Float?>,
    scale: Float,
) {
    for ((slug, paths) in shapes) {
        val colour = muscleColour(fills[slug])
        for (path in paths) {
            drawPath(path, colour)
            // Hairline, scaled back out so it stays hairline: the transform
            // above multiplies stroke widths too, and a 1px outline drawn
            // inside a 3x scale is a 3px outline that swallows the small
            // shapes — the fingers and the ankles go solid grey.
            drawPath(path, OUTLINE, style = Stroke(width = 1f / scale))
        }
    }
}

/**
 * Activation per artwork part, or null for parts nothing maps onto.
 *
 * Null rather than zero, because they are different: the hands and the hair
 * have no muscle behind them and should stay neutral, while a muscle that was
 * genuinely not worked today should read as the cold end of the scale.
 */
internal fun partFills(
    activation: Map<String, Float>,
    view: BodyView,
    selected: Set<String> = emptySet(),
): Map<String, Float?> {
    val wanted = if (view == BodyView.Front) "front" else "back"
    val fills = mutableMapOf<String, Float?>()
    for ((region, mapping) in BODY_VIEWER_REGIONS) {
        if (mapping.view != wanted) continue
        val value = when {
            // A picked muscle pins to the top of the scale so the selection is
            // unmistakable next to a merely well-worked one.
            mapping.muscle in selected || region in selected -> 1f
            else -> activation[mapping.muscle] ?: 0f
        }
        val current = fills[mapping.slug]
        if (current == null || value > current) fills[mapping.slug] = value
    }
    return fills
}

/**
 * The activation colour ramp, matching the browser's `muscleColor`.
 *
 * Deliberately the same numbers as
 * `frontend/src/components/activity/charts/MuscleMap.jsx` rather than
 * something drawn from the app theme. The diagram is read the same way in both
 * places — blue is easy, orange is hard — and a phone that used the theme's
 * primary would make the same session look like a different workout.
 */
private fun muscleColour(value: Float?): Color {
    if (value == null || value <= 0f) return NEUTRAL
    val v = min(1f, value)
    val hue = if (v < 0.5f) 220f - 182f * (v / 0.5f) else 38f - 34f * ((v - 0.5f) / 0.5f)
    val saturation = (72f + 22f * v) / 100f
    val lightness = (60f - 16f * v) / 100f
    val alpha = 0.42f + 0.53f * v
    return Color.hsl(hue, saturation, lightness, alpha)
}

private fun fitScale(box: Rect, width: Float, height: Float): Float =
    min(width / box.width, height / box.height)

/**
 * A body parsed once, and the box its paths actually cover.
 *
 * Parsed once per body for the life of the process: PathParser walks the
 * string and allocates a Path per shape, there are ~70 of them, and doing
 * that per recomposition — or per diagram, with several on a page — would
 * turn a colour change into a re-parse of the whole anatomy.
 *
 * The box, not the artwork's viewport, is what the figure is fitted to. The
 * library cut its bodies from shared artboards with margins around them, and
 * fitting the viewport spent that margin as padding inside every diagram —
 * the user asked for the figures as large as the space allows.
 */
internal class Figure(val shapes: Map<String, List<Path>>, val box: Rect)

private val figures = HashMap<BodyArt, Figure>()

internal fun figureFor(art: BodyArt): Figure = synchronized(figures) {
    figures.getOrPut(art) {
        val shapes = art.parts.mapValues { (_, paths) -> paths.map { PathParser().parsePathString(it).toPath() } }
        val box = shapes.values.flatten().map { it.getBounds() }
            .reduceOrNull { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom)) }
            ?: Rect(art.minX, art.minY, art.minX + art.width, art.minY + art.height)
        Figure(shapes, box)
    }
}

/** Width over height of a body as drawn — for sizing its box to it exactly. */
fun bodyAspect(view: BodyView, gender: BodyGender = BodyGender.Male): Float =
    figureFor(artFor(view, gender)).box.let { it.width / it.height }

/**
 * Which muscle is under a tap, or null for the background and the parts with
 * no muscle behind them.
 *
 * Hit-testing goes through `android.graphics.Region`, which is the only
 * containment test available for a filled path — Compose's `Path` has bounds
 * but no `contains`. Bounds alone would be wrong here and visibly so: the
 * bounding box of an arm overlaps the torso, so tapping the chest would select
 * the biceps about as often as not.
 */
private fun muscleAt(
    offset: Offset,
    width: Int,
    height: Int,
    figure: Figure,
    view: BodyView,
): String? {
    val box = figure.box
    val shapes = figure.shapes
    val scale = fitScale(box, width.toFloat(), height.toFloat())
    // Back into artwork coordinates, undoing the centring and the origin shift.
    val x = (offset.x - (width - box.width * scale) / 2f) / scale + box.left
    val y = (offset.y - (height - box.height * scale) / 2f) / scale + box.top

    val clip = android.graphics.Region(
        box.left.toInt() - 1,
        box.top.toInt() - 1,
        box.right.toInt() + 1,
        box.bottom.toInt() + 1,
    )
    val region = android.graphics.Region()

    for ((slug, paths) in shapes) {
        for (path in paths) {
            region.setPath(path.asAndroidPath(), clip)
            if (region.contains(x.toInt(), y.toInt())) {
                return muscleForSlug(slug, view)
            }
        }
    }
    return null
}

/**
 * The muscle a tapped part stands for.
 *
 * Several regions collapse onto one shape, so this has to pick. It takes the
 * first mapping for the view, which is the left/right pair of the same muscle
 * in every case except the ones where the artwork is genuinely coarser than
 * the vocabulary — `quadriceps` covers quads and hip flexors, and quads is the
 * right answer for a tap because it is the one someone means by pointing at a
 * thigh.
 */
private fun muscleForSlug(slug: String, view: BodyView): String? {
    val wanted = if (view == BodyView.Front) "front" else "back"
    return BODY_VIEWER_REGIONS.values
        .firstOrNull { it.slug == slug && it.view == wanted }
        ?.muscle
}

private fun artFor(view: BodyView, gender: BodyGender): BodyArt = when {
    gender == BodyGender.Female && view == BodyView.Front -> FEMALE_FRONT
    gender == BodyGender.Female -> FEMALE_BACK
    view == BodyView.Front -> MALE_FRONT
    else -> MALE_BACK
}

/** Slate, for a part with no muscle behind it. The browser's DEFAULT_MUSCLE. */
private val NEUTRAL = Color(0xFF64748B)

/** The library's own border colour, which is what separates adjacent shapes. */
private val OUTLINE = Color(0xFFDFDFDF)
