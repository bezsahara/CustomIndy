pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "CustomIndy"

include("customindy-compiler-plugin")
project(":customindy-compiler-plugin").projectDir = file("compiler-plugin")

include("customindy-gradle-plugin")
project(":customindy-gradle-plugin").projectDir = file("gradle-plugin")

include("customindy-annotations")
project(":customindy-annotations").projectDir = file("plugin-annotations")
