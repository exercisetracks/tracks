// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

/**
 * Release versions, compared the way the server compares them
 * (backend/app/services/update_check.py `parse_version`), so the phone and
 * the web never disagree about which side is behind.
 *
 * Numbers, not strings: as text 1.10.0 sorts below 1.9.0, and the panel would
 * tell someone to "update" to an older release. A pre-release sorts below its
 * release (1.2.0-beta.1 < 1.2.0). Found anywhere in the text, so `v1.2.0` and
 * `1.2.0-debug` both parse.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: String? = null,
) : Comparable<ReleaseVersion> {

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(
            this, other,
            { it.major }, { it.minor }, { it.patch },
            // Release (no label) above any pre-release of the same number.
            { it.pre == null }, { it.pre ?: "" },
        )

    override fun toString(): String = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?""")

        fun parse(text: String?): ReleaseVersion? {
            val m = PATTERN.find(text ?: return null) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt(), pre.ifEmpty { null })
        }

        /**
         * This build's release version, from its `versionName`. A debug build
         * carries `-debug` (app/build.gradle.kts `versionNameSuffix`), which
         * read as a pre-release label would sort it below the release it was
         * built from — and every debug build would be told to update to itself.
         */
        fun ofBuild(versionName: String): String = versionName.removeSuffix("-debug")

        /**
         * True only when both parse and [candidate] is strictly newer. An
         * unparseable version is never an update: a "please update" that can
         * never be satisfied teaches people to ignore the panel.
         */
        fun isNewer(candidate: String?, than: String?): Boolean {
            val a = parse(candidate) ?: return false
            val b = parse(than) ?: return false
            return a > b
        }
    }
}
