// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.feeds

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app the user can decide about, in the blacklist. */
data class RelayApp(val packageName: String, val label: String)

/**
 * The apps to offer in the notification blacklist.
 *
 * ## Why launchers rather than every installed package
 *
 * Android 11 hid the package list behind either `QUERY_ALL_PACKAGES` — a
 * permission that exists to be justified, and one an app whose whole pitch is
 * "it reads your notifications and keeps them" should not be asking for — or a
 * `<queries>` declaration naming what you actually need. The manifest declares
 * the launcher intent, which makes every app with an icon in the drawer
 * visible. That is the population a person means by "block this app".
 *
 * The gap is apps that notify without a launcher icon: carrier services, some
 * system components, the odd background-only app. Those are filled in from
 * [TracksNotificationListener.seenPackages] — the packages that have actually
 * posted while the relay was running — so anything that has ever reached the
 * watch can be blocked, without enumerating anything that has not.
 */
object InstalledApps {

    /**
     * @param extra packages to include even when they have no launcher entry —
     *   see the class note. Ones that cannot be resolved at all are listed
     *   under their package name rather than dropped, because a blocked app
     *   that has since been uninstalled must still be un-blockable.
     */
    suspend fun relayCandidates(
        context: Context,
        extra: Set<String> = emptySet(),
    ): List<RelayApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val own = context.packageName
        val apps = LinkedHashMap<String, RelayApp>()

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (resolved in pm.queryIntentActivities(launcher, 0)) {
            val packageName = resolved.activityInfo?.packageName ?: continue
            if (packageName == own) continue
            apps[packageName] = RelayApp(
                packageName = packageName,
                label = resolved.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    ?: packageName,
            )
        }

        for (packageName in extra) {
            if (packageName == own || packageName in apps) continue
            apps[packageName] = RelayApp(packageName, label(context, packageName))
        }

        apps.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun label(context: Context, packageName: String): String = try {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0))
            .toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    /**
     * An app's icon, small and cached.
     *
     * Drawn into a fixed-size bitmap rather than handed to Compose as a
     * Drawable: adaptive icons are vector-backed and several hundred pixels a
     * side at full size, and a list of two hundred of them at full size is tens
     * of megabytes for something rendered at thumbnail size.
     *
     * Loaded per row as the list scrolls, so a phone with a large drawer pays
     * for the dozen icons on screen instead of all of them at once. The cache
     * is what keeps scrolling back up free.
     */
    suspend fun icon(context: Context, packageName: String): Bitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val drawable = runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull() ?: return@withContext null
            val bitmap = drawable.toBitmap(ICON_PX)
            if (bitmap != null) cache.put(packageName, bitmap)
            bitmap
        }
    }

    private const val ICON_PX = 96

    private val cache = LruCache<String, Bitmap>(128)

    private fun Drawable.toBitmap(size: Int): Bitmap? {
        (this as? BitmapDrawable)?.bitmap?.let { source ->
            if (!source.isRecycled) return Bitmap.createScaledBitmap(source, size, size, true)
        }
        if (intrinsicWidth == 0 || intrinsicHeight == 0) return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        return bitmap
    }
}
