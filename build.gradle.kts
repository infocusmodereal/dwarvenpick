plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.spring") version "2.1.10" apply false
    id("org.springframework.boot") version "3.4.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

val defaultDevVersion = "0.1.0-SNAPSHOT"
val computedVersion =
    System.getenv("DWARVENPICK_VERSION")?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv("GITHUB_REF_NAME")
            ?.trim()
            ?.takeIf { it.startsWith("v") && it.length > 1 }
            ?.removePrefix("v")
        ?: defaultDevVersion

allprojects {
    group = "com.dwarvenpick"
    version = computedVersion

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("io.spring.dependency-management") {
        extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.2")
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.5.0")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
