package com.kayedaw.common

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class InfosExecution(
    val nomThread: String,
    val threadVirtuel: Boolean,
    val versionJava: String,
    val versionKotlin: String,
    val processeursDisponibles: Int
)

/** Diagnostic : prouve que les threads virtuels de Java 21 sont bien actifs. */
@RestController
@RequestMapping("/api/systeme")
class SystemeController {

    @GetMapping("/execution")
    fun execution(): InfosExecution {
        val thread = Thread.currentThread()
        return InfosExecution(
            nomThread = thread.name.ouSiVide("(anonyme)"),
            threadVirtuel = thread.isVirtual,               // API Java 21
            versionJava = Runtime.version().toString(),
            versionKotlin = KotlinVersion.CURRENT.toString(),
            processeursDisponibles = Runtime.getRuntime().availableProcessors()
        )
    }
}
