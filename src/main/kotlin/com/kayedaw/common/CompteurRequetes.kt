package com.kayedaw.common

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ JAVA 21 — Pourquoi ReentrantLock plutôt que synchronized ?              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Avec les threads virtuels, un bloc `synchronized` provoque du PINNING :
 * le thread virtuel ne peut pas être démonté de son thread porteur pendant
 * qu'il détient le moniteur. Le porteur est alors immobilisé, ce qui annule
 * tout le bénéfice de Loom.
 *
 * `ReentrantLock` n'a pas ce défaut : le thread virtuel se suspend proprement.
 *
 * (En pratique, préférer encore les structures atomiques quand c'est possible :
 * AtomicLong ci-dessous ne prend aucun verrou.)
 */
@Component
class CompteurRequetes {

    private val total = AtomicLong()                 // sans verrou : idéal
    private val verrou = ReentrantLock()             // et NON synchronized
    private val parRoute = mutableMapOf<String, Long>()

    fun incrementer(route: String) {
        total.incrementAndGet()
        // `withLock` : extension Kotlin sur Lock, gère le unlock dans un finally
        verrou.withLock {
            parRoute[route] = (parRoute[route] ?: 0) + 1
        }
    }

    fun total(): Long = total.get()

    fun detail(): Map<String, Long> = verrou.withLock { parRoute.toMap() }
}
