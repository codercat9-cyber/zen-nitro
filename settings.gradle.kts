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
        flatDir { dirs("app/libs") }   // сюда кладётся libbox.aar (ядро sing-box)
    }
}
rootProject.name = "ZenBlaster"
include(":app")
