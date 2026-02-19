package com.badgermole.app

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BadgermoleApplication

fun main(args: Array<String>) {
    runApplication<BadgermoleApplication>(*args)
}
