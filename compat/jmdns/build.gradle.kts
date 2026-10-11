plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // Same logging API as the original org.jmdns:jmdns:3.6.3 dependency.
    api("org.slf4j:slf4j-api:2.0.7")
    testImplementation("junit:junit:4.13.2")
}

tasks.jar {
    from("LICENSE") { into("META-INF"); rename { "LICENSE.jmdns" } }
    from("NOTICE") { into("META-INF"); rename { "NOTICE.jmdns" } }
}
