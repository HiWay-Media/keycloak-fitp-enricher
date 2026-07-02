plugins {
    `java-library`
}

group = "com.hiwaymedia.keycloak"
version = "0.3.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

val keycloakVersion = "22.0.1"
val jacksonVersion = "2.15.2"

dependencies {
    // Keycloak SPI: gia presente nel runtime, scope compileOnly
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")

    // Jackson e JBoss Logging: gia nel classpath Keycloak
    compileOnly("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    compileOnly("org.jboss.logging:jboss-logging:3.5.0.Final")

    // Test: replicare le compileOnly come testImplementation
    // (Gradle, a differenza di Maven 'provided', non propaga compileOnly al test classpath)
    testImplementation("org.keycloak:keycloak-server-spi:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-services:$keycloakVersion")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("org.jboss.logging:jboss-logging:3.5.0.Final")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("org.mockito:mockito-core:5.5.0")
    testImplementation("com.github.tomakehurst:wiremock-jre8-standalone:2.35.1")
}

tasks.jar {
    archiveBaseName.set("fitp-enricher")
    archiveVersion.set(version.toString())
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
