pluginManagement {
    repositories {
        google()
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

rootProject.name = "CrownMedia"

include(":app")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:design")
include(":domain")
include(":data:xtream")
include(":data:activation")
include(":player")
include(":features:activation")
include(":features:home")
include(":features:live")
include(":features:movies")
include(":features:series")
include(":features:search")
include(":features:account")
include(":features:settings")
