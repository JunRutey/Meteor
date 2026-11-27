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
// Dependencies must stay one-directional and acyclic; the declared edge table
// lives in tools/check-module-graph.sh and CI runs it as its first job.
// ---------------------------------------------------------------------------

fun meteorModule(name: String) {
    include(":$name")
    project(":$name").projectDir = file(name.replaceFirst('-', '/'))
}

// --- v0.1 module set -------------------------------------------------------
meteorModule("core-time")
meteorModule("core-notify")
meteorModule("core-crypto")
meteorModule("core-storage")
meteorModule("core-backup")
meteorModule("core-index")
meteorModule("core-log")
meteorModule("feature-settings")
meteorModule("feature-calendar")
meteorModule("feature-editor")
meteorModule("feature-people")
meteorModule("feature-gallery")
meteorModule("feature-media")
meteorModule("feature-question")
meteorModule("feature-ai")
meteorModule("app")
