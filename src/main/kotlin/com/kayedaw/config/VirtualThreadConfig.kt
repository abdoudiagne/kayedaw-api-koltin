package com.kayedaw.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.annotation.EnableAsync
import java.util.concurrent.Executors

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ JAVA 21 — Threads virtuels (Project Loom)                               │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * `spring.threads.virtual.enabled=true` suffit pour les requêtes HTTP.
 * Cette configuration étend le principe aux tâches @Async.
 *
 * Thread plateforme : ~1 Mo de pile. Thread virtuel : quelques centaines
 * d'octets. Quand un thread virtuel bloque (JDBC, HTTP sortant), il est
 * DÉMONTÉ de son thread porteur, qui repart servir une autre tâche.
 *
 * LIMITES À CITER EN ENTRETIEN (c'est ce qui distingue) :
 *   1. Le pool HikariCP reste le vrai plafond : 10 000 threads virtuels pour
 *      10 connexions ne servent à rien.
 *   2. `synchronized` provoque du PINNING : le thread virtuel reste collé à
 *      son porteur → utiliser ReentrantLock (voir CompteurRequetes).
 *   3. Inutile pour du CPU-bound : les threads virtuels servent aux I/O.
 *   4. Pas de pooling : un thread virtuel par tâche, on ne les recycle pas.
 */
@Configuration
@EnableAsync
@ConditionalOnProperty(name = ["spring.threads.virtual.enabled"], havingValue = "true")
class VirtualThreadConfig {

    @Bean
    fun applicationTaskExecutor(): AsyncTaskExecutor =
        TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor())
}
