import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.mieai.qqbot.plugin"
version = providers.gradleProperty("pluginVersion").orElse("0.0.2").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

val sdkRepository = providers.gradleProperty("qqbotSdkRepository")
    .orElse("D:/开发文档/miebot/build/plugin-sdk/repository")

repositories {
    maven { url = uri(sdkRepository.get()) }
    mavenCentral()
}

val embeddedLibraries by configurations.creating

configurations.compileOnly {
    extendsFrom(embeddedLibraries)
}

configurations.testRuntimeOnly {
    extendsFrom(embeddedLibraries)
}

dependencies {
    compileOnly("com.mieai.qqbot:qqbot-plugin-api:1.0.6")
    compileOnly("com.mieai.qqbot:qqbot-plugin-spi:1.0.6")
    embeddedLibraries("com.google.code.gson:gson:2.13.1")
    embeddedLibraries("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test-junit5"))
    testImplementation("com.mieai.qqbot:qqbot-plugin-testkit:1.0.6")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        javaParameters.set(true)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict", "-Xjvm-default=all"))
    }
}

tasks.jar {
    archiveBaseName.set("miers")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({ embeddedLibraries.map(::zipTree) }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/*.SF",
        )
    }
    manifest {
        attributes(
            "Plugin-Id" to "miers",
            "Plugin-Name" to "MieRS",
            "Plugin-Version" to project.version.toString(),
            "Plugin-Requires" to "3.2.0",
            "Plugin-Class" to "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "Plugin-Config-Schema" to "qqbot-plugin-schema.json",
            "Plugin-Default-Config" to "config.yml",
            "Plugin-Capabilities" to "event.read,event.subscribe,message.send,media.send",
        )
    }
}

tasks.test {
    useJUnitPlatform()
}
