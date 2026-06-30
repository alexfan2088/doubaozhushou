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
        maven(url = "https://artifact.bytedance.com/repository/Volcengine/")
    }
}

rootProject.name = "DoubaoNewlineBridge"
include(":app")
