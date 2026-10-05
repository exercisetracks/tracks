// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeStepTest {

    /** Closing the app mid-onboarding used to start it again from the welcome screen. */
    @Test
    fun `a closed app resumes at the step it was on`() {
        assertEquals(OnboardingStep.Zones, resumeStep("Zones", signedIn = false))
    }

    /**
     * The user had signed in, closed the app, and was offered the welcome
     * screen again. Resumes at the watch question, the step after sign-in.
     */
    @Test
    fun `a signed in phone never reopens at the welcome server or sign in steps`() {
        for (saved in listOf(null, "Welcome", "Server", "SignIn")) {
            assertEquals(OnboardingStep.Device, resumeStep(saved, signedIn = true), "saved=$saved")
        }
    }

    @Test
    fun `nothing saved starts at the welcome screen`() {
        assertEquals(OnboardingStep.Welcome, resumeStep(null, signedIn = false))
    }

    /** A renamed or removed step must not crash onboarding for someone mid-way through it. */
    @Test
    fun `an unknown saved step starts at the welcome screen`() {
        assertEquals(OnboardingStep.Welcome, resumeStep("Gone", signedIn = false))
    }
}
