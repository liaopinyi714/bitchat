import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

val githubReleaseCertSha256 = providers
    .environmentVariable("BITCHAT_GITHUB_RELEASE_CERT_SHA256")
    .orElse(providers.gradleProperty("BITCHAT_GITHUB_RELEASE_CERT_SHA256"))
    .orElse("")
val normalizedGithubReleaseCertSha256 = githubReleaseCertSha256.get()
    .replace(":", "")
    .trim()
    .lowercase()
require(
    normalizedGithubReleaseCertSha256.isEmpty() ||
        normalizedGithubReleaseCertSha256.matches(Regex("[a-f0-9]{64}"))
) {
    "BITCHAT_GITHUB_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint"
}

// Opt-in, local installation of the optimized app with the development certificate.
// Normal release builds remain unsigned; formal releases use the maintainer's key.
val previewSigning = providers.gradleProperty("bitchat.previewSigning")
    .map { it.toBooleanStrict() }
    .orElse(false)

// Keep the content-binding format/rules in sync with tools/build_provenance.py.
// This deliberately includes uncommitted source files, but excludes host-specific
// local.properties, tests, documentation and generated build outputs.
abstract class GenerateReleaseBuildProvenance : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = repositoryDirectory.get().asFile.toPath().toRealPath()
        val textSuffixes = setOf(
            ".kt", ".java", ".xml", ".pro", ".gradle", ".kts", ".properties",
            ".toml", ".json", ".txt", ".md", ".html", ".js", ".css", ".svg",
            ".yml", ".yaml", ".lockfile", ".csv", ".pem", ".base64", ".sh", ".bat"
        )
        val files = sourceFiles.files.associateBy { source ->
            check(!Files.isSymbolicLink(source.toPath()) && source.toPath().toRealPath().startsWith(root)) {
                "Release source inputs must be files within the repository"
            }
            root.relativize(source.toPath().toRealPath()).toString().replace('\\', '/')
        }
        check(files.keys.containsAll(listOf("android/app/build.gradle.kts", "android/app/src/main/AndroidManifest.xml"))) {
            "Required phone source inputs unavailable"
        }
        val paths = files.keys.sortedWith { left, right ->
            val a = left.toByteArray(Charsets.UTF_8)
            val b = right.toByteArray(Charsets.UTF_8)
            var comparison = 0
            for (index in 0 until minOf(a.size, b.size)) {
                comparison = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
                if (comparison != 0) break
            }
            if (comparison != 0) comparison else a.size.compareTo(b.size)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("bitchat-apk-source-v1\n".toByteArray(Charsets.US_ASCII))
        for (path in paths) {
            val source = files.getValue(path)
            var data = source.readBytes()
            if ("." + source.extension.lowercase() in textSuffixes || source.name == "gradlew") {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString()
                data = text.replace("\r\n", "\n").replace("\r", "\n").toByteArray(Charsets.UTF_8)
            }
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).toByteArray(Charsets.US_ASCII))
            digest.update('\n'.code.toByte())
        }
        val output = outputDirectory.file("bitchat-build-provenance.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            "{\"schema\":1,\"algorithm\":\"bitchat-apk-source-v1\",\"sourceSha256\":\"${HexFormat.of().formatHex(digest.digest())}\",\"fileCount\":${files.size}}\n",
            Charsets.UTF_8
        )
    }
}

val generateReleaseBuildProvenance = tasks.register<GenerateReleaseBuildProvenance>("generateReleaseBuildProvenance") {
    repositoryDirectory.set(rootProject.layout.projectDirectory.dir(".."))
    sourceFiles.from(rootProject.fileTree("..") {
        include(
            "android/app/src/main/**", "android/app/src/release/**",
            "android/app/*.gradle", "android/app/*.gradle.kts", "android/app/*.pro", "android/app/*.lockfile",
            "android/app/gradle/**", "android/*.gradle", "android/*.gradle.kts", "android/*.properties",
            "android/*.lockfile", "android/gradle/**", "android/gradlew", "android/gradlew.bat",
            "android/wear/*.gradle", "android/wear/*.gradle.kts", "android/wear/*.lockfile"
        )
        exclude("**/.gradle/**", "**/.git/**", "android/local.properties")
    })
    outputDirectory.set(layout.buildDirectory.dir("generated/releaseBuildProvenance/assets"))
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateReleaseBuildProvenance,
            GenerateReleaseBuildProvenance::outputDirectory
        )
    }
}

android {
    namespace = "com.bitchat.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "xyz.liaopinyi714.bitchat"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 12
        versionName = "0.2.4"
        buildConfigField("String", "RELAY_URL", "\"wss://chat.123456714.xyz\"")
        buildConfigField(
            "String",
            "GITHUB_RELEASE_CERT_SHA256",
            "\"$normalizedGithubReleaseCertSha256\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        debug {
            ndk {
                // Include x86_64 for emulator support during development
                abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (previewSigning.get()) {
                signingConfig = signingConfigs.getByName("debug")
            }
            vcsInfo {
                // BUILDINFO.json and attestations carry the verified commit
                // without depending on host-specific Git/worktree paths.
                include = false
            }
        }
    }

    // Each ABI APK has identical app features and only that ABI's native libraries.
    // AAB for Play Store handles architecture distribution automatically
    // Auto-detects: splits enabled for assemble tasks, disabled for bundle tasks
    // Works in Android Studio GUI and CLI without needing extra properties
    val enableSplits = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("assemble", ignoreCase = true) &&
        !taskName.contains("bundle", ignoreCase = true)
    }

    splits {
        abi {
            isEnable = enableSplits
            reset()
            include("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
            isUniversalApk = true  // For F-Droid and fallback
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Community language packs intentionally fall back to English where incomplete.
        // Chinese/English completeness and placeholders are enforced by contract tests.
        warning += "MissingTranslation"
    }
}

composeCompiler {
    // Kotlin 2.4.10's optional Compose group-key mapping depends on unspecified
    // class-file iteration order. Keep the normal R8 mapping, but omit that
    // augmentation until its producer is deterministic across clean builds.
    includeComposeMappingFile.set(false)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.process)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Permissions
    implementation(libs.accompanist.permissions)

    // QR
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    
    // Cryptography
    implementation(libs.bundles.cryptography)
    
    // JSON
    implementation(libs.gson)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // Bluetooth
    implementation(libs.nordic.ble)

    // WebSocket
    implementation(libs.okhttp)

    // WorkManager for background APK downloads
    implementation(libs.androidx.work.runtime.ktx)

    // HTTP Server for hotspot APK sharing
    implementation(libs.nanohttpd)

    // Arti (Tor in Rust): native libraries in src/main/jniLibs/ for all four ABIs.
    // ABI splits avoid shipping other architectures without removing Tor support.

    // Google Play Services Location
    implementation(libs.gms.location)

    // Security preferences
    implementation(libs.androidx.security.crypto)
    
    // EXIF orientation handling for images
    implementation(libs.androidx.exifinterface)
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.bundles.compose.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric resolves Android runtime jars itself (outside Gradle dependency resolution).
// Its legacy repo1 endpoint rejects cold GitHub-hosted runners with HTTP 403.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://repo.maven.apache.org/maven2"
    )
}
