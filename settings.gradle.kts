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
        providers.gradleProperty("boomingSs.litertExperimentRepository")
            .orNull
            ?.takeIf { it.isNotBlank() }
            ?.let { repositoryPath ->
                maven {
                    name = "boomingSsLiteRtExperiment"
                    url = uri(repositoryPath)
                    content {
                        includeModule("com.google.ai.edge.litert", "litert")
                    }
                }
            }
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "BoomingMusic"
include(":app")
