plugins {
    kotlin("jvm")
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
