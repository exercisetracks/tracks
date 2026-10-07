// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.donate

import android.content.Context
import android.content.Intent
import android.net.Uri

const val DONATE_URL = "https://ko-fi.com/hawkf"

fun openDonatePage(context: Context) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
}
