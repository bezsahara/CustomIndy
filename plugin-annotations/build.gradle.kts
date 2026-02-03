@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
//    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.jvm)
//    alias(libs.plugins.kotlin.binary.compatibility.validator)
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.jetbrains.dokka-javadoc") version "2.1.0"
    `maven-publish`
    signing
}

//tasks.named("apiBuild") {
//    enabled = false
//}

kotlin {
    explicitApi()
}

val dokkaJavadocTask = tasks.named("dokkaGeneratePublicationJavadoc")

tasks.register<Jar>("dokkaJavadocJar") {
    dependsOn(dokkaJavadocTask)
    from(dokkaJavadocTask.map { it.outputs.files })
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = "org.bezsahara.customindy"
            artifactId = "customindy-annotations"
            version = project.version.toString()

            artifact(tasks.named("dokkaJavadocJar"))
            artifact(tasks.named("kotlinSourcesJar"))

            pom {
                name.set("CustomIndyAnnotations")
                description.set("Annotations for Kotlin compiler plugin that rewrites calls to @CustomIndy functions into JVM invokedynamic instructions with user‑defined bootstrap methods and arguments.")
                url.set("https://github.com/bezsahara/CustomIndy")
                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("bezsahara")
                        name.set("Hlib")
                        email.set("bezsahara888@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/bezsahara/CustomIndy.git")
                    developerConnection.set("scm:git:ssh://github.com/bezsahara/CustomIndy.git")
                    url.set("https://github.com/bezsahara/CustomIndy")
                }
            }
        }
    }
    repositories {
        maven {
            name = "localRepo"
            url = file("../generated/annotation").toURI()
        }
    }
}

tasks.named("generateMetadataFileForMavenJavaPublication") {
    dependsOn("kotlinSourcesJar")
}

val iniPath: String? = System.getenv("SK_P")

if (iniPath != null) {
    val signingKey = file("$iniPath\\bez\\d1_SECRET.asc").readText()
    val passPhrase = file("$iniPath\\bez\\pass_phrase.txt").readText().trim()
    signing {
        useInMemoryPgpKeys(signingKey, passPhrase)
        sign(publishing.publications["mavenJava"])
    }
}
