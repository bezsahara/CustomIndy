plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.gradle.java.test.fixtures)
    alias(libs.plugins.gradle.idea)
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.jetbrains.dokka-javadoc") version "2.1.0"
    `maven-publish`
    signing
}


sourceSets {
    main {
        java.setSrcDirs(listOf("src"))
        resources.setSrcDirs(listOf("resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("test", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
}

idea {
    module.generatedSourceDirs.add(projectDir.resolve("test-gen"))
}

val annotationsRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }
val testArtifacts: Configuration by configurations.creating

dependencies {
    compileOnly(libs.kotlin.compiler)
    implementation(project(":customindy-annotations"))

    testFixturesApi(libs.kotlin.test.junit5)
    testFixturesApi(libs.kotlin.test.framework)
    testFixturesApi(libs.kotlin.compiler)
    testFixturesImplementation(project)

    annotationsRuntimeClasspath(project(":customindy-annotations"))
    annotationsRuntimeClasspath("org.ow2.asm:asm:9.9.1")
    // Dependencies required to run the internal test framework.
    testArtifacts(libs.kotlin.stdlib)
    testArtifacts(libs.kotlin.stdlib.jdk8)
    testArtifacts(libs.kotlin.reflect)
    testArtifacts(libs.kotlin.test)
    testArtifacts(libs.kotlin.script.runtime)
    testArtifacts(libs.kotlin.annotations.jvm)
}

buildConfig {
    useKotlinOutput {
        internalVisibility = true
    }

    packageName(group.toString())
    buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${rootProject.group}\"")
}

tasks.test {
    dependsOn(annotationsRuntimeClasspath)

    useJUnitPlatform()
    workingDir = rootDir

    systemProperty("annotationsRuntime.classpath", annotationsRuntimeClasspath.asPath)

    // Properties required to run the internal test framework.
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
}

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("org.bezsahara.customindy.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = testArtifacts.files
        .find { """$jarName-\d.*""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
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
            artifactId = "customindy-compiler-plugin"
            version = project.version.toString()

            artifact(tasks.named("dokkaJavadocJar"))
            artifact(tasks.named("kotlinSourcesJar"))

            pom {
                name.set("CustomIndy")
                description.set("Kotlin compiler plugin that rewrites calls to @CustomIndy functions into JVM invokedynamic instructions with user‑defined bootstrap methods and arguments.")
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
            url = file("../generated/compiler").toURI()
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
