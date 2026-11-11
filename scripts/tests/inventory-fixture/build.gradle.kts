plugins { java }
repositories { mavenCentral(); maven("https://libraries.minecraft.net/"); maven("https://repo.papermc.io/repository/maven-public/") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
dependencies { compileOnly("io.papermc.paper:paper-api:26.2.build.130-stable") }
tasks.withType<JavaCompile> { options.release.set(25) }
