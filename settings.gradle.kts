pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Meteor"

// ---------------------------------------------------------------------------
// Module registry.
//
// Directory convention:  :core-time  -> core/time
//                       :feature-ai -> feature/ai
//                       :app        -> app
//
// R3 (pluggable): pass -Pmeteor.excludeModules=feature-ai,core-index to drop
// modules from the build. A module may only be excluded once no remaining
// module references it; tools/check-module-graph.sh enforces that rule in CI.
// ---------------------------------------------------------------------------

val excludedModules: Set<String> =
    (providers.gradleProperty("meteor.excludeModules").orNull ?: "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

gradle.rootProject {
    extra["meteorExcludedModules"] = excludedModules
}

fun meteorModule(name: String) {
    if (name in excludedModules) {
        logger.lifecycle("Meteor: skipping :$name (excluded)")
        return
    }
    include(":$name")
    project(":$name").projectDir = file(name.replaceFirst('-', '/'))
}

// --- v0.1 module set -------------------------------------------------------
meteorModule("core-time")
meteorModule("core-crypto")
meteorModule("core-storage")
meteorModule("core-backup")
meteorModule("core-index")
meteorModule("core-log")
meteorModule("feature-settings")
meteorModule("feature-calendar")
meteorModule("feature-editor")
meteorModule("app")
