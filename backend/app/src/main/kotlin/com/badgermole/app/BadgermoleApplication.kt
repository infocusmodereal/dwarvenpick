package com.badgermole.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class BadgermoleApplication

fun main(args: Array<String>) {
    runApplication<BadgermoleApplication>(*args)
}
