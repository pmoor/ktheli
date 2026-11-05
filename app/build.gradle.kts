plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":physics"))
    implementation(project(":input"))
    implementation(libs.serialization.json)
    implementation(libs.gdx)
    implementation(libs.gdx.backend.lwjgl3)
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    implementation(libs.imgui.binding)
    implementation(libs.imgui.lwjgl3) {
        exclude(group = "org.lwjgl", module = "lwjgl-sdl") // we use the GLFW backend only
    }
    val platform = providers.gradleProperty("targetPlatform").orElse(
        providers.systemProperty("os.name").map { name ->
            val os = name.lowercase()
            when {
                os.contains("win") -> "windows"
                os.contains("mac") -> "macos"
                else -> "linux"
            }
        },
    ).get()
    runtimeOnly(
        when (platform) {
            "windows" -> libs.imgui.natives.windows
            "macos" -> libs.imgui.natives.macos
            else -> libs.imgui.natives.linux
        },
    )

    // Headless 3D rendering in tests: an OpenGL context via EGL without any window system (Mesa).
    testImplementation(libs.lwjgl.egl)
    testImplementation(libs.gdx.backend.headless)
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.lwjgl") useVersion(libs.versions.lwjgl.get())
    }
}

tasks.withType<Test>().configureEach {
    // Rendered-image tests use Mesa's software rasterizer by default so pixels match across machines;
    // -Pgpu renders on the real GPU instead. -PupdateGolden rewrites the reference images.
    if (!project.hasProperty("gpu")) environment("LIBGL_ALWAYS_SOFTWARE", "1")
    systemProperty("ktheli.updateGolden", project.hasProperty("updateGolden"))
    systemProperty("ktheli.goldenDir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
}

application {
    mainClass = "ktheli.app.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

/** ImGui's built-in font only covers ASCII; anything else renders as '?'. */
val checkUiAscii = tasks.register("checkUiAscii") {
    group = "verification"
    description = "Fails on non-ASCII characters in string literals of the app module."
    val sources = fileTree("src/main/kotlin")
    val root = projectDir
    inputs.files(sources)
    doLast {
        val literal = Regex(""""(?:[^"\\]|\\.)*"""")
        val violations = sources.files.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") &&
                    literal.findAll(line).any { m -> m.value.any { it.code > 127 } }
            }.map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: ${line.trim()}" }
        }
        if (violations.isNotEmpty()) throw GradleException("Non-ASCII UI strings:\n" + violations.joinToString("\n"))
    }
}

tasks.named("check") { dependsOn(checkUiAscii) }

/**
 * The app's layers, bottom to top: `sound` (acoustics, synth), `session` (simulation, input, settings, camera:
 * no toolkit), then `render` (libGDX) and `ui` (ImGui) side by side, each reading the session but not each
 * other, and the root package `ktheli.app`, the shell that wires them to the window, devices and audio.
 * Fails the build if a layer reaches above itself or into a toolkit it must not use.
 */
val checkLayers = tasks.register("checkLayers") {
    group = "verification"
    description = "Checks that app packages only depend on the layers below them."
    val sources = fileTree("src/main/kotlin/ktheli/app")
    val root = projectDir
    inputs.files(sources)
    doLast {
        val shell = """ktheli\.app\.(?!(?:session|sound|render|ui)\b)\w"""
        val gdx = """(?:com\.badlogic|org\.lwjgl)\."""
        // Displays see the aircraft through session.AircraftView, not its concrete types.
        val heli = """ktheli\.physics\.heli\."""
        val rules = mapOf(
            "sound" to listOf(gdx, """imgui\.""", """ktheli\.input\.""", """ktheli\.app\.(?!sound\.)"""),
            "session" to listOf(gdx, """imgui\.""", """ktheli\.app\.(?:render|ui)\.""", shell),
            "render" to listOf("""imgui\.""", """ktheli\.input\.""", """ktheli\.app\.(?:ui|sound)\.""", shell, heli),
            "ui" to listOf(gdx, """ktheli\.app\.render\.""", shell, heli),
        ).mapValues { (_, patterns) -> patterns.map { Regex("""\b$it""") } }
        val literal = Regex(""""(?:[^"\\]|\\.)*"""")
        val violations = sources.files.flatMap { file ->
            val layer = file.relativeTo(root.resolve("src/main/kotlin/ktheli/app")).invariantSeparatorsPath.substringBefore('/', "")
            val forbidden = rules[layer] ?: return@flatMap emptyList()
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.trimStart()
                !code.startsWith("package ") && !code.startsWith("//") && !code.startsWith("*") && !code.startsWith("/*") &&
                    forbidden.any { it.containsMatchIn(literal.replace(line, "\"\"")) }
            }.map { (i, line) -> "${file.relativeTo(root)}:${i + 1}: $layer must not use this\n    ${line.trim()}" }
        }
        if (violations.isNotEmpty()) throw GradleException("Layer check failed:\n" + violations.joinToString("\n"))
    }
}

tasks.named("check") { dependsOn(checkLayers) }
