plugins {
    kotlin("jvm")
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":backend:core"))
    implementation(project(":backend:datasource"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
