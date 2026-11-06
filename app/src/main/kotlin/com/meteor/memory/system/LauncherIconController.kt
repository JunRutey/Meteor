package com.meteor.memory.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.meteor.memory.MainActivity

/**
 * Shows or hides the launcher entry declared in `AndroidManifest.xml` (ADR-031).
 *
 * The visible icon is the `LauncherEntry` alias. MainActivity itself carries no
 * `MAIN`/`LAUNCHER` filter, because disabling it would also disable the entry
 * point the `*#*#314159#*#*` receiver launches.
 *
 * The alias is addressed by its class name, which the manifest merger expands
 * from the module namespace (`com.meteor.memory`), **not** from the variant's
 * applicationId (`com.meteor.memory.debug`). The component's package part is
 * therefore the runtime package name, exactly as `ComponentName(context, cls)`
 * builds it. Both spellings are derived here so a rename of the package cannot
 * silently leave the switch pointing at nothing.
 */
class LauncherIconController(context: Context) {

    private val appContext = context.applicationContext

    private val launcherAlias = ComponentName(
        appContext,
        "${mainActivityPackage()}.$ALIAS_NAME",
    )

    /** The effective state; a component left at `DEFAULT` counts as visible. */
    fun isHidden(): Boolean =
        appContext.packageManager.getComponentEnabledSetting(launcherAlias) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /**
     * Applies [hidden] only when the component disagrees, so a cold start does
     * not wake the launcher with a no-op change.
     *
     * @return true when the component state actually changed.
     */
    fun sync(hidden: Boolean): Boolean {
        if (isHidden() == hidden) return false
        setHidden(hidden)
        return true
    }

    /**
     * `DONT_KILL_APP`: flipping the icon must not tear down the session the
     * user is standing in — they would lose the screen they toggled from.
     */
    fun setHidden(hidden: Boolean) {
        appContext.packageManager.setComponentEnabledSetting(
            launcherAlias,
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    private companion object {
        const val ALIAS_NAME = "LauncherEntry"

        /**
         * The manifest declares the alias as `.LauncherEntry`, which the merger
         * expands against the module namespace — the same package MainActivity
         * lives in — not against the variant's applicationId.
         */
        fun mainActivityPackage(): String =
            checkNotNull(MainActivity::class.qualifiedName).substringBeforeLast('.')
    }
}
