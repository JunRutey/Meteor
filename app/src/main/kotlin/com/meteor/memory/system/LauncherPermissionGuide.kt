package com.meteor.memory.system

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.meteor.memory.feature.settings.LauncherRecovery
import java.util.Locale

/**
 * Reads whether the system will let the dial-code receiver bring the app back,
 * and sends the user to the switch that decides it (ADR-031).
 *
 * A background receiver may only start an activity when the system grants a
 * background-activity-start exemption; without one the launch is dropped
 * silently, so `startActivity` returning normally proves nothing. There are two
 * verifiable paths:
 *
 * - **AOSP**: `SYSTEM_ALERT_WINDOW` ("display over other apps") is a documented
 *   exemption. `Settings.canDrawOverlays` reports it directly.
 * - **MIUI / HyperOS**: the vendor gate is its own AppOp on top of AOSP — the
 *   one whose switch is 「后台弹出界面」. AOSP has no public API for it, but the
 *   value is readable (AutoJs6, LinkSheet and Oolong all do it); the check fails
 *   open to [LauncherRecovery.UNKNOWN] so a ROM change degrades to "test it
 *   yourself" instead of reporting a false "blocked" or a false "ready".
 *
 * MIUI's own switches are still not queryable as switches, only as this op: the
 * guide therefore only opens the right system page and never claims to have
 * changed anything.
 */
class LauncherPermissionGuide(context: Context) {

    private val appContext = context.applicationContext

    /** True on MIUI / HyperOS, where an extra vendor gate exists. */
    fun needsManualGrants(): Boolean = isMiui()

    /**
     * Whether a dial-code launch is currently allowed to come to the foreground.
     *
     * `BLOCKED` is a real answer from the platform (or from MIUI's op) and the
     * settings dialog sends the user to the matching switch. `UNKNOWN` means the
     * ROM could not be read. Neither state authorises hiding on its own: the
     * dialog only unlocks after a dial code actually returns to the app.
     */
    fun backgroundStartState(): LauncherRecovery {
        if (isMiui()) return miuiBackgroundStartState()
        // AOSP: "display over other apps" is the documented BAL exemption.
        return if (Settings.canDrawOverlays(appContext)) {
            LauncherRecovery.READY
        } else {
            LauncherRecovery.BLOCKED
        }
    }

    /** @return true when some system page opened, false when every attempt failed. */
    fun openBackgroundStartSettings(): Boolean {
        if (isMiui() && open(*miuiPermissionEditorIntents())) return true
        return open(overlayIntent(), appDetailsIntent())
    }

    /** @return true when MIUI's permission editor opened, false when we fell back. */
    fun openAppPermissionEditor(): Boolean {
        val intents = miuiPermissionEditorIntents() + appDetailsIntent()
        return open(*intents)
    }

    /** @return true when MIUI's auto-start manager opened, false when we fell back. */
    fun openAutoStartSettings(): Boolean {
        val autoStart = arrayOf(
            Intent(MIUI_AUTO_START)
                .setPackage(MIUI_SECURITY_CENTER)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(EXTRA_PACKAGE_NAME, appContext.packageName)
                .putExtra(EXTRA_PACKAGE_NAME_ALT, appContext.packageName),
            Intent()
                .setComponent(ComponentName(MIUI_SECURITY_CENTER, MIUI_AUTO_START_ACTIVITY))
                .putExtra(EXTRA_PACKAGE_NAME, appContext.packageName),
        )
        val intents = autoStart + miuiPermissionEditorIntents() + appDetailsIntent()
        return open(*intents)
    }

    /**
     * MIUI has used several activity names for the same permission editor over
     * the years; try the explicit ones first so the user lands on the app, not
     * on the security centre home page. Extras copied from LinkSheet/AutoJs6.
     */
    private fun miuiPermissionEditorIntents(): Array<Intent> = arrayOf(
        miuiEditorIntent(MIUI_PERMISSIONS_EDITOR),
        miuiEditorIntent(MIUI_APP_PERMISSIONS_EDITOR),
        Intent(MIUI_APP_PERM_EDITOR)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(EXTRA_PACKAGE_NAME, appContext.packageName),
    )

    private fun miuiEditorIntent(activity: String): Intent =
        Intent(MIUI_APP_PERM_EDITOR)
            .setClassName(MIUI_SECURITY_CENTER, activity)
            .addCategory(Intent.CATEGORY_DEFAULT)
            .putExtra(EXTRA_PACKAGE_NAME, appContext.packageName)
            .putExtra(EXTRA_PACKAGE_NAME_ALT, appContext.packageName)
            .putExtra(EXTRA_PACKAGE_UID, Process.myUid())

    /**
     * MIUI's 「后台弹出界面」 is AppOp 10021 on top of AOSP. The method that reads
     * an op by int is hidden, so it is called reflectively; any failure (ROM
     * renamed the op, hidden-API policy changed, service missing) is reported as
     * [LauncherRecovery.UNKNOWN] rather than guessed.
     */
    private fun miuiBackgroundStartState(): LauncherRecovery {
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
            ?: return LauncherRecovery.UNKNOWN
        return runCatching {
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(
                appOps,
                MIUI_BACKGROUND_START_OP,
                Process.myUid(),
                appContext.packageName,
            ) as Int
            if (mode == AppOpsManager.MODE_ALLOWED) {
                LauncherRecovery.READY
            } else {
                LauncherRecovery.BLOCKED
            }
        }.getOrDefault(LauncherRecovery.UNKNOWN)
    }

    private fun isMiui(): Boolean {
        // Public fields only: `ro.miui.ui.version.name` would need getprop or a
        // hidden API and is not worth the risk just to pick one sentence of copy.
        val family = setOf("xiaomi", "redmi", "poco")
        return Build.MANUFACTURER.lowercase(Locale.ROOT) in family ||
            Build.BRAND.lowercase(Locale.ROOT) in family
    }

    /** Tries each intent in order, so a button never ends up doing nothing. */
    private fun open(vararg intents: Intent): Boolean {
        for (intent in intents) {
            val opened = runCatching {
                appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return true
        }
        return false
    }

    private fun overlayIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.fromParts("package", appContext.packageName, null))

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", appContext.packageName, null))

    private companion object {
        const val MIUI_SECURITY_CENTER = "com.miui.securitycenter"
        const val MIUI_APP_PERM_EDITOR = "miui.intent.action.APP_PERM_EDITOR"
        const val MIUI_PERMISSIONS_EDITOR =
            "com.miui.permcenter.permissions.PermissionsEditorActivity"
        const val MIUI_APP_PERMISSIONS_EDITOR =
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
        const val MIUI_AUTO_START = "miui.intent.action.OP_AUTO_START"
        const val MIUI_AUTO_START_ACTIVITY =
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        const val EXTRA_PACKAGE_NAME = "extra_pkgname"
        const val EXTRA_PACKAGE_NAME_ALT = "extra_package_name"
        const val EXTRA_PACKAGE_UID = "extra_package_uid"

        /** MIUI AppOp for 「后台弹出界面」; no AOSP equivalent, not a stable API. */
        const val MIUI_BACKGROUND_START_OP = 10021
    }
}
