plugins {
    kotlin("jvm")
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":backend:core"))

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jooq:jooq")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
