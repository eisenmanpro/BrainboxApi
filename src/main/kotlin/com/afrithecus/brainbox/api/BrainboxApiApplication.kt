package com.afrithecus.brainbox.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BrainboxApiApplication

fun main(args: Array<String>) {
	runApplication<BrainboxApiApplication>(*args)
}
