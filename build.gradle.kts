plugins {
    alias(libs.plugins.kotlin.jvm) apply false
//    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.binary.compatibility.validator) apply false
    alias(libs.plugins.buildconfig) apply false
}

allprojects {
    group = "org.bezsahara.customindy"
    version = "0.2.2"
}

tasks.register("publishLocalAll") {
    group = "publishing"
    description = "Publishes compiler-plugin and annotations to the localRepo maven repository."
    dependsOn(
        ":customindy-compiler-plugin:publishAllPublicationsToLocalRepoRepository",
        ":customindy-annotations:publishAllPublicationsToLocalRepoRepository"
    )
}

tasks.register("publishToMavenLocalAll") {
    group = "publishing"
    description = "Publishes all subprojects to Maven Local."
    dependsOn(
        ":customindy-annotations:publishToMavenLocal",
        ":customindy-compiler-plugin:publishToMavenLocal",
        ":customindy-gradle-plugin:publishToMavenLocal"
    )
}

tasks.register<Zip>("zipLocalAnnotation") {
    group = "publishing"
    description = "Zips generated/annotation after local publishing."
    dependsOn("publishLocalAll")
    from("generated/annotation")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("customindy-annotations.zip")
}

tasks.register<Zip>("zipLocalCompiler") {
    group = "publishing"
    description = "Zips generated/compiler after local publishing."
    dependsOn("publishLocalAll")
    from("generated/compiler")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("customindy-compiler-plugin.zip")
}

tasks.register("zipLocalAll") {
    group = "publishing"
    description = "Publishes locally and creates both annotation and compiler zip archives."
    dependsOn("zipLocalAnnotation", "zipLocalCompiler")
}
