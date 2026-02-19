plugins {
    kotlin("jvm")
    id("java-library")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":backend:core"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.zaxxer:HikariCP")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")
    runtimeOnly("io.trino:trino-jdbc:479")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
