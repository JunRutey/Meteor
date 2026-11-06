package com.meteor.memory.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Sends the user to the system pages that decide whether the hidden icon can be
 * recovered (ADR-031).
 *
 * MIUI / HyperOS does not treat "start an activity from the background" as a
 * plain Android permission: it is split into the two switches 「自启动」 and
 * 「后台弹出界面」 inside its own permission centre. With either of them off, the
 * `*#*#314159#*#*` receiver never manages to bring the app forward, which means
 * the user has hidden the icon and cannot get back in.
 *
 * Those switches are not queryable through the public SDK, and guessing MIUI
 * AppOps op codes would break silently on the next ROM update, so this class
 * deliberately does not try to read them. All it does is recognise the ROM and
 * open the right page; every entry point falls back to the ordinary "app info"
 * screen so the button never does nothing.
 */
class LauncherPermissionGuide(context: Context) {

    private val appContext = context.applicationContext

    /** True when the return path needs switches only this ROM family exposes. */
    fun needsManualGrants(): Boolean {
        // Public fields only: `ro.miui.ui.version.name` would need getprop or a
        // hidden API and is not worth the risk just to pick one sentence of copy.
        val family = setOf("xiaomi", "redmi", "poco")
        return Build.MANUFACTURER.lowercase(Locale.ROOT) in family ||
            Build.BRAND.lowercase(Locale.ROOT) in family
    }

    /** @return true when MIUI's permission editor opened, false when we fell back. */
    fun openAppPermissionEditor(): Boolean = open(
        Intent(MIUI_APP_PERM_EDITOR).putExtra(EXTRA_PACKAGE_NAME, appContext.packageName),
        appDetailsIntent(),
    )

    /** @return true when MIUI's auto-start manager opened, false when we fell back. */
    fun openAutoStartSettings(): Boolean = open(
        Intent(MIUI_AUTO_START).addCategory(Intent.CATEGORY_DEFAULT),
        Intent(MIUI_APP_PERM_EDITOR).putExtra(EXTRA_PACKAGE_NAME, appContext.packageName),
        appDetailsIntent(),
    )

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

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", appContext.packageName, null))

    private companion object {
        const val MIUI_APP_PERM_EDITOR = "miui.intent.action.APP_PERM_EDITOR"
        const val MIUI_AUTO_START = "miui.intent.action.OP_AUTO_START"
        const val EXTRA_PACKAGE_NAME = "extra_pkgname"
    }
}
