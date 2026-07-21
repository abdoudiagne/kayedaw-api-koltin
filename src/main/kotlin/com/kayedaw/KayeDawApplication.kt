package com.kayedaw

import com.kayedaw.config.AppProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class KayeDawApplication

fun main(args: Array<String>) {
    runApplication<KayeDawApplication>(*args)
}
