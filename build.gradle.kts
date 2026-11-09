plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "6.25.0"
    id("com.github.spotbugs") version "6.0.22"
}

group = "ru.azenizzka"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

configurations.all {
    exclude(group = "commons-logging", module = "commons-logging")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    implementation("org.glassfish.jaxb:jaxb-runtime:4.0.5")
    implementation("javax.xml.bind:jaxb-api:2.3.1")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.telegram:telegrambots:6.9.7.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("org.testcontainers:junit-jupiter:1.19.7")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named("bootJar") {
    dependsOn("spotlessApply")
}

tasks.named("bootRun") {
    dependsOn("spotlessApply")
}

tasks.named("build") {
    dependsOn("spotlessApply")
}
spotbugs {
    excludeFilter.set(file("spotbugs-exclude.xml"))
}
