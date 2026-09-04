package au.buzz.ryzewave.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

/** One app the user can pick in Settings > Notifications. */
data class InstalledApp(val packageName: String, val label: String)

/** Launcher apps on the phone (those with a MAIN/LAUNCHER activity, see the manifest `<queries>`). Blocking: call off the main thread. */
object InstalledApps {
    fun launcherApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val own = context.packageName
        val seen = HashMap<String, InstalledApp>()
        val resolved = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: RuntimeException) {
            emptyList()
        }
        for (r in resolved) {
            val pkg = r.activityInfo?.packageName ?: continue
            if (pkg == own || pkg in seen) continue
            val label = try {
                r.loadLabel(pm)?.toString()?.trim().orEmpty()
            } catch (e: RuntimeException) {
                ""
            }
            seen[pkg] = InstalledApp(pkg, label.ifEmpty { pkg })
        }
        return seen.values.sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }))
    }
}
