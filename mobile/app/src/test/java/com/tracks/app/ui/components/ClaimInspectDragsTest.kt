// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pressing a chart and sliding along it reads the chart; it must not open the
 * navigation drawer, which listens for sideways drags anywhere on the page.
 * And a chart must not stop the page scrolling when the drag is vertical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class ClaimInspectDragsTest {

    @get:Rule val compose = createComposeRule()

    private lateinit var drawer: DrawerState
    private val scroll = ScrollState(0)

    private fun page(claim: Boolean) {
        drawer = DrawerState(DrawerValue.Closed)
        compose.setContent {
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = { ModalDrawerSheet { Text("Menu") } },
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(300.dp))
                    Box(
                        Modifier
                            .width(360.dp)
                            .height(150.dp)
                            .testTag("chart")
                            .then(if (claim) Modifier.claimInspectDrags() else Modifier),
                    )
                    Box(Modifier.fillMaxWidth().height(1200.dp))
                }
            }
        }
    }

    private fun holdThenSlideRight() = compose.onNodeWithTag("chart").performTouchInput {
        down(centerLeft.copy(x = 40f))
        advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
        repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(30f, 0f)) }
        up()
    }

    private fun slideRight() = compose.onNodeWithTag("chart").performTouchInput {
        down(centerLeft.copy(x = 40f))
        repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(30f, 0f)) }
        up()
    }

    @Test
    fun `holding a chart and sliding right does not open the drawer`() {
        page(claim = true)
        holdThenSlideRight()
        compose.waitForIdle()

        assertEquals(DrawerValue.Closed, drawer.currentValue)
    }

    @Test
    fun `sliding along a chart without holding does not open the drawer`() {
        page(claim = true)
        slideRight()
        compose.waitForIdle()

        assertEquals(DrawerValue.Closed, drawer.currentValue)
    }

    @Test
    fun `without the claim the same slide opens the drawer`() {
        // The control: proves the two tests above would catch the bug.
        page(claim = false)
        holdThenSlideRight()
        compose.waitForIdle()

        assertEquals(DrawerValue.Open, drawer.currentValue)
    }

    @Test
    fun `a vertical drag over a chart still scrolls the page`() {
        page(claim = true)
        compose.onNodeWithTag("chart").performTouchInput {
            down(center)
            repeat(20) { moveBy(androidx.compose.ui.geometry.Offset(0f, -30f)) }
            up()
        }
        compose.waitForIdle()

        assertTrue("page did not scroll", scroll.value > 0)
    }
}
