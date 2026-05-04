plugins {
    `java-library`
}

group = "com.hiwaymedia.keycloak"
version = "1.0.0"

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
}

tasks.jar {
    archiveBaseName.set("fitp-enricher")
    archiveVersion.set(version.toString())
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
