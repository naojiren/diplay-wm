// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

subprojects {
    // Use the same JmDNS 3.6.3 protocol code with an Android 6 socket-bind fix everywhere,
    // including the common module's direct test dependency. Never package both implementations.
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.jmdns:jmdns")).using(project(":mdnsCompat"))
        }
    }
    // Robolectric runs on the host JVM; Android's conscrypt_jni cannot load on desktop Linux.
    configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("org.conscrypt:conscrypt-android"))
                .using(module("org.conscrypt:conscrypt-openjdk-uber:2.6.2"))
        }
    }
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        jvmArgs("-Xmx512m", "-XX:+UseSerialGC")
    }
}
