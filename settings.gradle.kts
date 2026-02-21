plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "dwarvenpick"

include(
    "backend:app",
    "backend:auth",
    "backend:core",
    "backend:datasource",
    "backend:db",
    "backend:query",
)
