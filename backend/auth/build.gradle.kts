plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":backend:core"))

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.ldap:spring-ldap-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
