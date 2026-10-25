plugins {
    java
}

group = "org.purpurmc.purpur"
version = "26.2.0"
description = "PurpurBedrockAC — высокооптимизированный адаптивный античит под Purpurbedrock 26.2"

val purpurApiVersion = providers.gradleProperty("purpurVersion").orElse("26.2-R0.1-SNAPSHOT")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    maven("https://repo.purpurmc.org/snapshots")
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
}

dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:${purpurApiVersion.get()}")
}

tasks.withType<JavaCompile> {
    options.encoding = Charsets.UTF_8.name()
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
}

tasks.processResources {
    filteringCharset = Charsets.UTF_8.name()
}

tasks.jar {
    archiveBaseName.set("PurpurBedrockAC")
    archiveVersion.set("26.2")
}
