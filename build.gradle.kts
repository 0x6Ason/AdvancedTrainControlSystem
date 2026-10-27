plugins {
    java
}

group = "dev.advancedtrains"
version = "0.2.2-SNAPSHOT"

// The oldest server API ATCS compiles against. Anything newer in the 1.21 line keeps working,
// so stick to this API surface instead of bumping it for convenience. CI's compatibility matrix
// overrides these (-PpaperApi=... -PtrainCarts=... -PbkCommonLib=... -PsignLink=...) to prove
// the build against newer servers and TrainCarts builds without changing the baseline here.
fun versionOf(property: String, default: String): String =
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() } ?: default

val paperApiVersion = versionOf("paperApi", "1.21.4-R0.1-SNAPSHOT")
val trainCartsVersion = versionOf("trainCarts", "2.0.0")
val bkCommonLibVersion = versionOf("bkCommonLib", "2.0.1")
val signLinkVersion = versionOf("signLink", "1.21.5-v1")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://ci.mg-dev.eu/plugin/repository/everything/") {
        name = "MGDev"
    }
    maven("https://repo.bluecolored.de/releases") {
        name = "BlueColored"
    }
    maven("https://repo.extendedclip.com/releases/") {
        name = "ExtendedClip"
    }
    maven("https://jitpack.io") {
        name = "JitPack"
        content { includeModule("com.github.MilkBowl", "VaultAPI") }
    }
    maven("https://maven.enginehub.org/repo/") {
        name = "EngineHub"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // Provided by the server. Only the long-stable parts of these APIs are used, so any
    // TrainCarts 2.x build works; never add an exact-version check at runtime.
    compileOnly("com.bergerkiller.bukkit:TrainCarts:$trainCartsVersion") { isTransitive = false }
    compileOnly("com.bergerkiller.bukkit:BKCommonLib:$bkCommonLibVersion") { isTransitive = false }

    // Optional integrations, activated only when the plugin is installed.
    compileOnly("com.bergerkiller.bukkit:SignLink:$signLinkVersion") { isTransitive = false }
    compileOnly("de.bluecolored:bluemap-api:2.7.3")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.9") { isTransitive = false }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        // Java 21 is what the plugin needs; Paper 26.x API artifacts are built for Java 25, so the
        // compatibility matrix passes -PjavaVersion=25 for those rows.
        languageVersion.set(JavaLanguageVersion.of(versionOf("javaVersion", "21").toInt()))
    }
}

val englishPolicyFiles = fileTree(projectDir) {
    include("src/**/*.java", "src/**/*.yml", "*.md", "docs/**/*.md")
    exclude("build/**")
}

val verifyEnglishTextPolicy by tasks.registering {
    group = "verification"
    description = "Rejects CJK text in source, bundled configuration, and documentation."
    inputs.files(englishPolicyFiles)
    doLast {
        val disallowedScripts = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")
        val violations = englishPolicyFiles.files
            .sortedBy { it.invariantSeparatorsPath }
            .flatMap { file ->
                file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    if (disallowedScripts.containsMatchIn(line)) {
                        "${file.relativeTo(projectDir).invariantSeparatorsPath}:${index + 1}"
                    } else {
                        null
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException("English-only text policy violations:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(versionOf("javaVersion", "21").toInt())
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
}

tasks.processResources {
    val properties = mapOf("version" to project.version)
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check {
    dependsOn(verifyEnglishTextPolicy)
}

// Writes docs/reference/*.md from the command tree, sign parsers, plugin.yml and config.yml.
// ReferenceDocsTest fails the build when the committed pages differ from what this writes.
val generateDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Regenerates the reference pages under docs/reference from the code."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.advancedtrains.atcs.command.ReferenceDocs")
    args("docs/reference")
    workingDir = projectDir
}

// Renders sample displays (roadmap, board, map, status, logo) into docs/images/displays from the
// staging network fixture, for the documentation. Not checked by a test: text rendering differs
// slightly between JDKs.
val generatePreviews by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Renders sample display pictures under docs/images/displays."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.advancedtrains.atcs.display.DisplayPreviews")
    args("docs/images/displays")
    workingDir = projectDir
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
