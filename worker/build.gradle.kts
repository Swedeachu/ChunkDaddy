plugins {
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "gg.swim.chunkdaddy"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":chunker"))
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains:annotations:26.1.0")
    implementation("it.unimi.dsi:fastutil:8.5.19")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

application {
    mainClass.set("gg.swim.chunkdaddy.worker.WorkerMain")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

tasks.shadowJar {
    archiveBaseName.set("chunkdaddy-worker")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest { attributes["Main-Class"] = "gg.swim.chunkdaddy.worker.WorkerMain" }
}

tasks.build { dependsOn(tasks.shadowJar) }

// The native app locates the worker jar relative to the application directory.
// `installWorker` puts it where CMake's build tree expects it.
val installWorker = tasks.register<Copy>("installWorker") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("dist"))
}
