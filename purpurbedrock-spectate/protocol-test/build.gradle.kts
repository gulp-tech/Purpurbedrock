plugins { java }
repositories {
    mavenCentral()
    maven("https://repo.opencollab.dev/main/")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    testImplementation("org.cloudburstmc.protocol:bedrock-connection:3.0.0.Beta14-20261019.150006-9")
    testImplementation("org.cloudburstmc:nbt:3.1.0.Final")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile> { options.release.set(25); options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
