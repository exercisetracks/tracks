// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.map

import android.content.Context
import android.view.MotionEvent
import org.maplibre.android.gestures.ShoveGestureDetector
import org.maplibre.android.maps.MapLibreMap

/**
 * Gesture thresholds for the map you navigate by.
 *
 * Two changes to MapLibre's defaults, both about the same thing: on a map
 * someone is reading in the field, a gesture should do the one thing it was
 * meant to do and nothing else.
 *
 * ## Make the two-finger tilt the whole gesture, from the first millimetre
 *
 * ### The slide before the tilt
 *
 * Out of the box, two fingers dragged up the screen pan the map for a moment and
 * only then start tilting it. That is not sloppiness in the gesture library, it
 * is three defaults meeting:
 *
 * - `ShoveGestureDetector` refuses to start until the fingers have travelled
 *   **16dp** vertically (`mapbox_defaultShovePixelThreshold`).
 * - `MoveGestureDetector` needs only **one** pointer and has a move threshold of
 *   **zero**, so a two-finger drag is already a pan on its first frame.
 * - MapLibre marks shove as mutually exclusive with scale and rotate, but never
 *   with move — so once the map is panning, tilting does not stop it. The two
 *   apply at once and the map slides while it leans.
 *
 * So the map pans for 16dp, then pans *and* tilts. Both halves are wrong, and
 * the second is why simply lowering the threshold is not enough on its own.
 *
 * ### What this does instead
 *
 * - Drops the shove threshold to [SHOVE_THRESHOLD_DP]. Kept a little under the
 *   7dp of span change that arms a pinch, rather than pushed to nothing: shove
 *   and scale *are* mutually exclusive, so a tilt that armed far too eagerly
 *   would win diagonal pinches and leave zoom unreachable for that gesture. The
 *   guard that makes a small threshold safe is not the distance at all — it is
 *   the library's 20-degree rule that the two fingers be side by side, plus the
 *   fact that shove measures the *average* vertical travel of both, which a
 *   pinch barely moves.
 * - Raises the pan threshold to [MULTI_FINGER_PAN_DP] while more than one finger
 *   is down, so nothing slides during the moment tilt is arming. A deliberate
 *   two-finger pan still works — it just has to mean it. One finger is untouched
 *   and pans as immediately as it always did.
 * - Turns scrolling off outright once a tilt begins, and interrupts a pan already
 *   under way. The threshold alone cannot do this: `MoveGestureDetector` measures
 *   distance from where the touch *started*, so during a long tilt the fingers
 *   eventually clear any threshold and the map would start sliding mid-lean.
 *
 * ## Give rotation a dead zone
 *
 * MapLibre arms a rotation after **3 degrees** of twist
 * (`MapGestureDetector` calls `setAngleThreshold(3f)`), which is less than the
 * hands do by accident. Nobody pinches with their fingers on a perfectly fixed
 * axis; a normal zoom rolls a few degrees on the way in, and the map arrives
 * askew.
 *
 * MapLibre does have two guards for this, and neither reaches the case. It adds
 * 25 degrees to the threshold once a *scale* is under way
 * (`isIncreaseRotateThresholdWhenScaling`) and can suppress rotation outright
 * then (`isDisableRotateWhenScaling`) — but both need the scale to have been
 * recognised first, and a scale is not recognised until the span between the
 * fingers has changed by 7dp. Three degrees of twist happens well inside that
 * 7dp, so rotation arms first, and from there
 * `isIncreaseScaleThresholdWhenRotating` makes the zoom the *harder* of the two
 * to reach. The gesture is decided in the wrong direction before the guards
 * have anything to guard.
 *
 * So the base threshold goes up instead, to [ROTATE_THRESHOLD_DEGREES] — enough
 * that a twist has to be meant. It costs nothing when it is: deliberate rotation
 * carries far past this in the first few frames, and once it has armed there is
 * no further threshold to fight.
 *
 * Nothing here is written for the dashboard's map, which switches tilt off
 * entirely; this is opt-in for that reason.
 */
internal class MapGestures(context: Context) {

    private val density = context.resources.displayMetrics.density
    private var map: MapLibreMap? = null

    /** Called once the map exists — the gestures manager does not exist before. */
    fun attach(map: MapLibreMap) {
        this.map = map
        val gestures = map.gesturesManager
        gestures.shoveGestureDetector.pixelDeltaThreshold = SHOVE_THRESHOLD_DP * density
        gestures.rotateGestureDetector.angleThreshold = ROTATE_THRESHOLD_DEGREES
        // MapLibre's defaults, restated rather than relied on: with the base
        // threshold raised, these are what keep a long pinch from drifting into
        // a rotation once it is well under way, and the pair is only coherent
        // read together with the number above.
        map.uiSettings.isDisableRotateWhenScaling = true
        map.uiSettings.isIncreaseRotateThresholdWhenScaling = true

        map.addOnShoveListener(
            object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(detector: ShoveGestureDetector) {
                    // A pan that is already running ignores any threshold change,
                    // so it is ended rather than out-argued. This is the case
                    // where one finger was panning and a second joined to tilt.
                    gestures.moveGestureDetector.interrupt()
                    // Checked in `onMoveBegin`, so no new pan can start either.
                    map.uiSettings.isScrollGesturesEnabled = false
                }

                override fun onShove(detector: ShoveGestureDetector) = Unit

                override fun onShoveEnd(detector: ShoveGestureDetector) {
                    map.uiSettings.isScrollGesturesEnabled = true
                }
            },
        )
    }

    /**
     * Every touch event, before MapLibre sees it — an `OnTouchListener` runs
     * ahead of `onTouchEvent`, which is what makes the threshold swap land in
     * time to matter for the very event that carries the second finger.
     */
    fun onTouch(event: MotionEvent) {
        val map = map ?: return
        map.gesturesManager.moveGestureDetector.moveThreshold =
            if (event.pointerCount > 1) MULTI_FINGER_PAN_DP * density else 0f

        // Scrolling is restored by `onShoveEnd`, but a map that cannot be panned
        // is a dead map, so it does not rest on that one callback firing. The
        // hand leaving the glass restores it too.
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            map.uiSettings.isScrollGesturesEnabled = true
        }
    }

    private companion object {
        /** Down from MapLibre's 16dp. */
        const val SHOVE_THRESHOLD_DP = 6f

        /** Up from zero, and only while a second finger is down. */
        const val MULTI_FINGER_PAN_DP = 24f

        /**
         * Up from MapLibre's 3 degrees.
         *
         * Chosen against the hand rather than the screen: with fingers a
         * comfortable 5 cm apart this is about 8 mm of travel each, sideways —
         * far more than a pinch rolls by accident, and a fraction of the turn
         * anyone makes when they actually want the map to face a different way.
         */
        const val ROTATE_THRESHOLD_DEGREES = 18f
    }
}
