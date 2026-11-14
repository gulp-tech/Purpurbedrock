plugins { java }
repositories { mavenCentral(); maven("https://libraries.minecraft.net/"); maven("https://repo.papermc.io/repository/maven-public/") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.130-stable")
    compileOnly("com.mojang:brigadier:1.3.10")
}
tasks.withType<JavaCompile> { options.release.set(25) }
tasks.jar { archiveFileName.set("typed-command-fixture.jar") }
