plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.gradle.plugin)
    id("com.gradle.plugin-publish") version "1.3.1"

}

sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    test {
        java.setSrcDirs(listOf("test"))
        resources.setSrcDirs(listOf("testResources"))
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin.api)
    testImplementation(libs.kotlin.test.junit5)
}

buildConfig {
    packageName(project.group.toString())

    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")

    val pluginProject = project(":customindy-compiler-plugin")
    buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${pluginProject.group}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${pluginProject.name}\"")
    buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${pluginProject.version}\"")

    val annotationsProject = project(":customindy-annotations")
    buildConfigField(
        type = "String",
        name = "ANNOTATIONS_LIBRARY_COORDINATES",
        expression = "\"${annotationsProject.group}:${annotationsProject.name}:${annotationsProject.version}\""
    )
}

gradlePlugin {
    website = "https://github.com/bezsahara/CustomIndy"
    vcsUrl = "https://github.com/bezsahara/CustomIndy.git"

    version = project.version.toString()

    plugins {
        create("customindyPlugin", Action {
            displayName = "CustomIndy Gradle Plugin"
            description = "Wires the CustomIndy compiler plugin and annotations into Kotlin builds."
            tags = listOf("kotlin", "compiler-plugin", "invokedynamic", "bytecode", "codegen")

            id = "org.bezsahara.customindy"
            implementationClass = "org.bezsahara.customindy.CustomIndyGradlePlugin"
        })
    }
}
