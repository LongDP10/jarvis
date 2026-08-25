package com.jarvis.assistant.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The installed-app index that "open YouTube" resolves against.
 *
 * Nothing here is hard-coded. The list is read from the package manager, so a
 * newly installed app is launchable as soon as the cache refreshes, and an app
 * the user does not have is honestly reported as missing instead of being
 * matched to a guessed package name.
 *
 * Ranking lives in [AppMatcher], which has no Android dependency and is tested
 * directly.
 */
@Singleton
class AppRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Why the last enumeration produced nothing, or null when it worked. Read by
     * the open_app tool so it can tell "this phone has no app by that name" apart
     * from "JARVIS cannot see any apps at all" -- two very different problems
     * that used to share one error message.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    private val cacheLock = Mutex()
    private var cache: List<AppEntry> = emptyList()
    private var cachedAtMs: Long = 0

    suspend fun installedApps(forceRefresh: Boolean = false): List<AppEntry> = cacheLock.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cache.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) {
            return cache
        }
        val loaded = withContext(Dispatchers.IO) { loadLaunchableApps() }
        cache = loaded
        cachedAtMs = now
        loaded
    }

    /**
     * Every app matching [query], best first. More than one result means the
     * query was ambiguous and the caller should ask rather than choose.
     */
    suspend fun resolve(query: String): List<AppEntry> =
        AppMatcher.rank(query, installedApps())

    fun launchIntentFor(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun labelFor(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    /**
     * Enumerates launchable apps, and says why when it cannot.
     *
     * The previous version wrapped the whole query in runCatching and defaulted
     * to an empty list, which turned every possible failure into the same
     * symptom: "no installed app matches X", for every app, forever. That is the
     * worst kind of error handling -- it converts a diagnosable fault into a
     * silent one.
     *
     * Two changes matter here:
     *
     *  - MATCH_ALL is gone. Each ResolveInfo carries a full ActivityInfo and
     *    ApplicationInfo, and a phone with a few hundred apps can push the
     *    result past the 1 MB Binder transaction limit, at which point the query
     *    throws instead of returning. Default flags return the same launcher
     *    activities with a much smaller payload.
     *  - If the query still comes back empty, a second pass walks installed
     *    applications and keeps the ones that have a launch intent. It reaches
     *    the same set by a different, lighter road.
     */
    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, 0)
        } catch (e: Exception) {
            Log.e(TAG, "queryIntentActivities failed", e)
            lastFailure = "Android refused to list installed apps: ${e.javaClass.simpleName}"
            emptyList()
        }

        val fromIntent = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?: return@mapNotNull null
            AppEntry(label = label.trim(), packageName = packageName)
        }

        val entries = fromIntent.ifEmpty {
            Log.w(TAG, "Launcher-intent query returned nothing; falling back to installed packages")
            loadViaInstalledApplications(pm)
        }

        val usable = entries
            .filter { it.label.isNotEmpty() && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        if (usable.isEmpty() && lastFailure == null) {
            lastFailure = "No launchable apps were visible to JARVIS. " +
                "This is Android package visibility filtering, not a missing app."
        } else if (usable.isNotEmpty()) {
            lastFailure = null
        }
        return usable
    }

    private fun loadViaInstalledApplications(pm: PackageManager): List<AppEntry> = try {
        pm.getInstalledApplications(0).mapNotNull { info ->
            if (pm.getLaunchIntentForPackage(info.packageName) == null) return@mapNotNull null
            AppEntry(
                label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                    ?.trim().orEmpty(),
                packageName = info.packageName,
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "getInstalledApplications fallback failed", e)
        lastFailure = "Android refused to list installed apps: ${e.javaClass.simpleName}"
        emptyList()
    }

    private companion object {
        const val TAG = "AppRegistry"
        /**
         * Short enough that installing an app and immediately asking for it
         * works, long enough that a chain of commands does not re-query the
         * package manager on every step.
         */
        const val CACHE_TTL_MS = 60_000L
    }
}
