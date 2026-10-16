import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask

plugins {
    alias(libs.plugins.android.application)
}

// The WM EX5 head unit uses 32-bit ARMv7. Opt in to other ABIs for emulators.
val wmUniversalAbis = providers.gradleProperty("wmUniversalAbis")
    .map { it.toBooleanStrict() }.getOrElse(false)

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
    ?: rootProject.file(".private/runtime-assets").takeIf { it.isDirectory }?.canonicalFile
    ?: rootProject.file("auth-assets").takeIf { it.isDirectory }?.canonicalFile

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 19
        targetSdk = 37
        multiDexEnabled = true
        versionCode = 74
        versionName = "0.2.17-legacy.1"
        testInstrumentationRunner = "com.shilapi.xcertplay.WmAuthenticationInstrumentation"
        ndk {
            abiFilters += if (wmUniversalAbis) {
                setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            } else {
                setOf("armeabi-v7a")
            }
        }
    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            val localKeystore = rootProject.file("release-signing.jks").takeIf { it.isFile }
                ?: rootProject.file("release-signing.keystore").takeIf { it.isFile }
            storeFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                .orNull?.let { file(it) } ?: localKeystore ?: file("missing-release-keystore.jks")
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("diplay123456")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("diplay")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("diplay123456")
        }
    }

    buildTypes {
        debug {
            isDebuggable = false
            applicationIdSuffix = ".wmex5"
            versionNameSuffix = "-wm-ex5-perf30-key-repeat"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = if (signingConfigs.getByName("release").storeFile?.isFile == true) { signingConfigs.getByName("release") } else { signingConfigs.getByName("debug") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

val authenticationProbeRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// A complete test-side desugaring library must not shadow target-app methods on Android 6.
afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) keepRulesConfigurations.add("-keep class j$.** { *; }")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation("androidx.multidex:multidex:2.0.1")
    authenticationProbeRuntime(libs.bouncycastle)
    authenticationProbeRuntime("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val requireStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
val verifyStandaloneAuthentication by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Parse the selected authentication input and verify repeated challenge signatures before packaging."
    dependsOn(requireStandaloneAuthentication, ":shared:bundleLibRuntimeToJarDebug")
    classpath(authenticationProbeRuntime,
        project(":shared").layout.buildDirectory.file(
            "intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"))
    mainClass.set("com.shilapi.xcertplay.mfi.LocalMfiProbe")
    localAuthenticationAssets?.let { args(it.resolve("offline-mfi").absolutePath) }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a signed standalone APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
