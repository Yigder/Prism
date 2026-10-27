import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("androidx.baselineprofile")
}

// Room writes each database version's schema here (checked in); MigrationTest builds old versions from them.
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.prism.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prism.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 45
        versionName = "1.7.6"
        vectorDrawables { useSupportLibrary = true }
        // The name under the launcher icon; a side-by-side build (below) is called "Prism Dev" so it can't be mistaken for Prism.
        manifestPlaceholders["appLabel"] = "@string/app_name"
        // Only the side-by-side test copy can be profiled from adb (simpleperf / Perfetto), for chasing jank (1.7.4).
        manifestPlaceholders["profileable"] = "false"
        // Optional: `lastfm.apiKey=…` in local.properties lets genre detection ask Last.fm too.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProps.getProperty("lastfm.apiKey", "")}\"")
    }

    // Releases are signed with the key in keystore.properties (gitignored, with the .jks beside it).
    // Without it — or with -PdebugSign — they fall back to the debug key so anyone can build.
    val keyProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val useReleaseKey = keyProps.isNotEmpty() && !project.hasProperty("debugSign")
    signingConfigs {
        if (useReleaseKey) create("release") {
            storeFile = rootProject.file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // `-PsideBySide`: a debug Prism that installs next to the real one (for trying changes on your own phone), as
            // "Prism Dev". `-PsideBySide=name` installs as com.prism.music.name instead of .dev, beside an older dev copy.
            if (project.hasProperty("sideBySide")) {
                applicationIdSuffix = "." + (project.property("sideBySide") as String).ifBlank { "dev" }
                manifestPlaceholders["appLabel"] = "Prism Dev"
            }
        }
        release {
            // Kept unminified: NewPipeExtractor + Rhino rely on reflection.
            isMinifyEnabled = false
            // `-Pr8` (1.7.4 trial, not for releases yet): R8 optimizes Prism's, Compose's and Kotlin's own code and keeps
            // every other library whole (r8-trial.pro), for measuring what it does to jank.
            if (project.hasProperty("r8")) {
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "r8-trial.pro")
            }
            signingConfig = signingConfigs.getByName(if (useReleaseKey) "release" else "debug")
            // `-PsideBySide` on a release build: the same "Prism Dev" test copy, but optimised like a release (debug
            // builds run Prism's own audio code slower, which matters when chasing stutters). Always the debug key, so
            // it can't be mistaken for (or installed over) the real Prism.
            if (project.hasProperty("sideBySide")) {
                applicationIdSuffix = "." + (project.property("sideBySide") as String).ifBlank { "dev" }
                manifestPlaceholders["appLabel"] = "Prism Dev"
                manifestPlaceholders["profileable"] = "true"
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests can read the app's own resources (strings, the widgets' layouts).
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        // ONNX Runtime's engine (13+ MB per CPU type) comes with Sing's download (ModelStore.Model.SING_RUNTIME_*), not
        // in the APK. Its small Java bridge (~120 KB) stays: Android only finds that one in the APK (it has no soname).
        jniLibs {
            excludes += setOf("**/libonnxruntime.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/CONTRIBUTORS.md",
                "META-INF/*.kotlin_module",
                "mozilla/public-suffix-list.txt",
                // Kuromoji's dictionary: downloaded when Japanese lyrics are first romanized (ModelStore.Model.JAPANESE).
                "com/atilika/kuromoji/ipadic/*.bin",
            )
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

// The builds :baselineprofile makes from release (benchmarkRelease, nonMinifiedRelease) can be traced and profiled from
// adb, as the side-by-side test copy can; the real release can't.
androidComponents {
    onVariants { v ->
        if (v.buildType != "release") v.manifestPlaceholders.put("profileable", "true")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Window size and fold posture (tablets, foldables, landscape): Apache-2.0, brings androidx.window.
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Hands the APK's baseline profile (src/main/generated/baselineProfiles, written by :baselineprofile) to Android, so
    // Prism's busiest code is compiled ahead instead of interpreted after an install or update (1.7.4). Apache-2.0.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Runs yt-dlp's EJS solver (assets/ejs) on WebView's V8 to decipher signed-in stream URLs.
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("org.jsoup:jsoup:1.23.2")
    // Japanese lyrics' kanji readings for romanization (Apache-2.0). Its 13 MB dictionary isn't packed into the APK
    // (see packaging below): it's downloaded on first use (ModelStore). Tests read it from the jar.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // Sing's vocal separation (Spleeter) runs on ONNX Runtime (MIT). Its native libraries (13+ MB per CPU type) aren't
    // packed into the APK (see packaging below): they're downloaded with Sing's model on first use (ModelStore).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    // Blend: QR codes made and read (ZXing core, Apache-2.0), the camera for scanning one (CameraX, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // On the PC, tests run the same models with ONNX Runtime's desktop build.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")

    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")

    testImplementation("junit:junit:4.13.2")
    // Draws composables on the PC (DesignGalleryTest: the redesign's components, light and dark), Apache-2.0, test-only;
    // the manifest piece is debug-only (it registers the empty activity tests draw in).
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Renders the backgrounds to PNGs on the PC (BackdropRenderTest), with Robolectric's real graphics.
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// The tests use ONNX Runtime's desktop build (with its PC libraries); the Android one's classes would clash with it.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

/**
 * Prism's icons are drawn as SVG files in design/icons (see its README). This turns them into
 * `PrismIconData` (generated Kotlin: each icon's path data and how it's drawn), which `PrismIcons` builds from, so the
 * app can't drift from the drawings. It reads the small part of SVG the drawings use: path, circle and rect (with rx),
 * class "f" (filled) or "o" (outline that the selected twin fills), stroke-width, and on the root data-filled
 * ("tint" or "solid": the icon has a selected twin, NameFilled) and data-mirror ("true": flipped right to left).
 */
abstract class GeneratePrismIcons : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val drawings: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Android drawables made from the same drawings, for what Compose doesn't draw: widgets, the tile, Android Auto. */
    @get:OutputDirectory
    abstract val resDir: DirectoryProperty

    /** A drawable: [icon]'s drawing ([filled]: its selected twin), white, turned half round when [flip]. */
    private class Drawable(val icon: String, val filled: Boolean = false, val flip: Boolean = false, val note: String)

    private val drawables = mapOf(
        "ic_w_play" to Drawable("Play", note = "Widgets: play"),
        "ic_w_pause" to Drawable("Pause", note = "Widgets: pause"),
        "ic_w_next" to Drawable("Next", note = "Widgets: next"),
        "ic_w_prev" to Drawable("Previous", note = "Widgets: previous"),
        "ic_w_heart" to Drawable("Heart", filled = true, note = "Widgets: liked"),
        "ic_w_heart_outline" to Drawable("Heart", note = "Widgets: like"),
        "ic_w_thumb" to Drawable("ThumbDown", filled = true, flip = true, note = "YouTube Music-style widget: liked (thumb up)"),
        "ic_w_thumb_outline" to Drawable("ThumbDown", flip = true, note = "YouTube Music-style widget: like (thumb up)"),
        "ic_tile_recognize" to Drawable("Recognize", note = "Song recognition's Quick Settings tile and widget"),
        "ic_s_search" to Drawable("Search", note = "App icon shortcut: Search"),
        "ic_s_downloads" to Drawable("DownloadDone", note = "App icon shortcut: Downloads"),
    )

    /** One generated path: its data, kind (0 line, 1 filled, 2 line the twin fills) and line width. */
    private class GenPart(val d: String, val kind: Int, val width: Double)

    private fun vector(name: String, spec: Triple<Boolean, Int, List<GenPart>>, dr: Drawable): String {
        val (mirror, twin, parts) = spec
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<!-- ${dr.note}. Generated from design/icons/${dr.icon}.svg by app/build.gradle.kts (GeneratePrismIcons). -->\n")
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        sb.append("    android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"")
        sb.append(if (mirror) "\n    android:autoMirrored=\"true\">\n" else ">\n")
        if (dr.flip) sb.append("    <group android:rotation=\"180\" android:pivotX=\"12\" android:pivotY=\"12\">\n")
        val ind = if (dr.flip) "        " else "    "
        for (p in parts) {
            if (p.kind == 1) { sb.append("$ind<path android:fillColor=\"#FFFFFFFF\" android:pathData=\"${p.d}\" />\n"); continue }
            val fill = dr.filled && p.kind == 2 && twin != 0
            sb.append("$ind<path android:pathData=\"${p.d}\"\n")
            sb.append("$ind    android:fillColor=\"${if (fill) "#FFFFFFFF" else "#00000000"}\"")
            if (fill && twin == 1) sb.append(" android:fillAlpha=\"0.18\"")
            sb.append("\n$ind    android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"${num(p.width)}\"")
            sb.append(" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" />\n")
        }
        if (dr.flip) sb.append("    </group>\n")
        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun num(v: Double): String = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Path data with every command and number apart, so any parser reads it the same. */
    private fun normalize(d: String, where: String): String {
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
        val leftover = d.replace(Regex("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?|[\\s,]"), "")
        require(leftover.isEmpty()) { "$where: can't read '$leftover' in its path" }
        require(tokens.firstOrNull()?.let { it == "M" || it == "m" } == true) { "$where: a path has to start with M" }
        return tokens.joinToString(" ") { t -> t.toDoubleOrNull()?.let(::num) ?: t }
    }

    @TaskAction
    fun generate() {
        val files = drawings.get().asFile.listFiles { f -> f.extension == "svg" }!!.sortedBy { it.name }
        require(files.isNotEmpty()) { "No drawings in ${drawings.get().asFile}" }
        val parser = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val generated = HashMap<String, Triple<Boolean, Int, List<GenPart>>>()
        val out = StringBuilder()
        out.append("// Generated from design/icons/*.svg by app/build.gradle.kts (GeneratePrismIcons). Edit the drawings, not this.\n")
        out.append("package com.prism.music.ui.theme\n\n")
        out.append("internal object PrismIconData {\n")
        out.append("    /** [kind]: 0 a line, 1 filled, 2 a line the selected twin fills. [width] in grid units (24 a side). */\n")
        out.append("    class Part(val d: String, val kind: Int, val width: Float)\n")
        out.append("    /** [twin]: 0 none, 1 tinted fill, 2 solid fill. */\n")
        out.append("    class Spec(val mirror: Boolean, val twin: Int, val parts: Array<Part>)\n\n")
        out.append("    val names = arrayOf(${files.joinToString(", ") { "\"${it.nameWithoutExtension}\"" }})\n\n")
        out.append("    fun spec(name: String): Spec = when (name) {\n")
        for (f in files) {
            val name = f.nameWithoutExtension
            require(Regex("[A-Z][A-Za-z0-9]*").matches(name)) { "${f.name}: name it like the Kotlin icon (Home.svg)" }
            val root = parser.parse(f).documentElement
            val mirror = root.getAttribute("data-mirror") == "true"
            val twin = when (val t = root.getAttribute("data-filled")) { "" -> 0; "tint" -> 1; "solid" -> 2; else -> error("${f.name}: data-filled '$t'") }
            val baseWidth = root.getAttribute("stroke-width").toDoubleOrNull() ?: 1.5
            val parts = mutableListOf<String>()
            val genParts = mutableListOf<GenPart>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? org.w3c.dom.Element ?: continue
                fun a(n: String) = el.getAttribute(n).toDoubleOrNull() ?: 0.0
                val d = when (el.tagName) {
                    "style", "title", "desc" -> continue
                    "path" -> normalize(el.getAttribute("d"), f.name)
                    "circle" -> {
                        val (cx, cy, r) = Triple(a("cx"), a("cy"), a("r"))
                        "M ${num(cx + r)} ${num(cy)} A ${num(r)} ${num(r)} 0 1 1 ${num(cx - r)} ${num(cy)} A ${num(r)} ${num(r)} 0 1 1 ${num(cx + r)} ${num(cy)} Z"
                    }
                    "rect" -> {
                        val (x, y) = a("x") to a("y")
                        val (w, h) = a("width") to a("height")
                        val r = minOf(a("rx"), w / 2, h / 2)
                        if (r <= 0.0) "M ${num(x)} ${num(y)} H ${num(x + w)} V ${num(y + h)} H ${num(x)} Z"
                        else listOf(
                            "M ${num(x + r)} ${num(y)} H ${num(x + w - r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + w)} ${num(y + r)}",
                            "V ${num(y + h - r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + w - r)} ${num(y + h)}",
                            "H ${num(x + r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x)} ${num(y + h - r)}",
                            "V ${num(y + r)} A ${num(r)} ${num(r)} 0 0 1 ${num(x + r)} ${num(y)} Z",
                        ).joinToString(" ")
                    }
                    else -> error("${f.name}: <${el.tagName}> isn't supported (use path, circle or rect)")
                }
                val kind = when (val c = el.getAttribute("class")) { "" -> 0; "f" -> 1; "o" -> 2; else -> error("${f.name}: class '$c'") }
                val width = el.getAttribute("stroke-width").toDoubleOrNull() ?: baseWidth
                parts += "Part(\"$d\", $kind, ${num(width)}f)"
                genParts += GenPart(d, kind, width)
            }
            generated[name] = Triple(mirror, twin, genParts)
            require(parts.isNotEmpty()) { "${f.name} draws nothing" }
            require(twin == 0 || "2, " in parts.joinToString()) { "${f.name}: data-filled needs a class=\"o\" shape to fill" }
            out.append("        \"$name\" -> Spec($mirror, $twin, arrayOf(\n")
            parts.forEach { out.append("            $it,\n") }
            out.append("        ))\n")
        }
        out.append("        else -> error(\"No drawing for icon \$name in design/icons\")\n    }\n}\n")
        val dir = outputDir.get().asFile.resolve("com/prism/music/ui/theme").apply { mkdirs() }
        dir.resolve("PrismIconData.kt").writeText(out.toString())

        val res = resDir.get().asFile.resolve("drawable").apply { deleteRecursively(); mkdirs() }
        for ((file, dr) in drawables) {
            val spec = generated[dr.icon] ?: error("$file needs design/icons/${dr.icon}.svg")
            res.resolve("$file.xml").writeText(vector(file, spec, dr))
        }
    }
}

val generatePrismIcons = tasks.register<GeneratePrismIcons>("generatePrismIcons") {
    drawings.set(rootProject.layout.projectDirectory.dir("design/icons"))
    outputDir.set(layout.buildDirectory.dir("generated/prismIcons/kotlin"))
    resDir.set(layout.buildDirectory.dir("generated/prismIcons/res"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generatePrismIcons, GeneratePrismIcons::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(generatePrismIcons, GeneratePrismIcons::resDir)
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
