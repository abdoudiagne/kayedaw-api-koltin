package com.kayedaw.seance

import com.kayedaw.common.arrondi2
import com.kayedaw.common.lundiDeLaSemaine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.system.measureTimeMillis

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 1.8 — Qu'est-ce qu'une coroutine ? Différence avec un thread ? │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Une coroutine est une tâche SUSPENDABLE, très légère : des milliers tiennent
 * sur quelques threads. `suspend` marque une fonction qui peut se mettre en
 * pause SANS BLOQUER son thread.
 *
 * Ici on parallélise deux agrégations indépendantes avec async/await.
 * Séquentiel : t1 + t2. Parallèle : max(t1, t2).
 *
 * `coroutineScope` applique la CONCURRENCE STRUCTURÉE : si une tâche fille
 * échoue, les autres sont annulées et l'exception remonte. Pas de fuite de
 * tâche orpheline, contrairement à un `Executor` mal géré.
 *
 * NUANCE HONNÊTE À DIRE EN ENTRETIEN : avec Java 21 et les threads virtuels,
 * paralléliser des appels JDBC bloquants a moins d'intérêt qu'avant — Loom
 * traite déjà bien le blocage. Les coroutines gardent l'avantage sur
 * l'annulation, les timeouts et les flux (Flow).
 */
@Service
class StatistiquesService(
    private val seanceService: SeanceService
) {

    /**
     * Point d'entrée bloquant appelé par le controller MVC.
     * `runBlocking` fait le pont entre le monde bloquant (Servlet) et les
     * coroutines. Dans une application WebFlux, le controller serait
     * directement `suspend` et ce pont disparaîtrait.
     */
    fun calculer(email: String, debut: LocalDate, fin: LocalDate): StatistiquesResponse {
        lateinit var resultat: StatistiquesResponse
        val duree = measureTimeMillis {
            resultat = runBlocking { calculerEnParallele(email, debut, fin) }
        }
        return resultat.copy(calculeEnMs = duree)      // copy() : apport des data classes
    }

    private suspend fun calculerEnParallele(
        email: String, debut: LocalDate, fin: LocalDate
    ): StatistiquesResponse = coroutineScope {

        // Les deux requêtes partent en parallèle
        val seancesDeferred = async(Dispatchers.IO) {
            seanceService.seancesDeLaPeriode(email, debut, fin)
        }
        val volumeParTypeDeferred = async(Dispatchers.IO) {
            seanceService.volumeParType(email, debut, fin)
        }
        /*
         * Période PRÉCÉDENTE de même durée, chargée en parallèle : c'est elle
         * qui donne un sens au volume brut (« +12 % » plutôt que « 120 km »).
         */
        val jours = ChronoUnit.DAYS.between(debut, fin)
        val precedentesDeferred = async(Dispatchers.IO) {
            seanceService.seancesDeLaPeriode(
                email, debut.minusDays(jours + 1), debut.minusDays(1))
        }

        val seances = seancesDeferred.await()
        val volumeParType = volumeParTypeDeferred.await()
        val precedentes = precedentesDeferred.await()

        if (seances.isEmpty()) return@coroutineScope StatistiquesResponse.vide()

        // Calcul pur : pas d'I/O, on reste sur le thread courant
        val distance = seances.sumOf { it.distanceKm }
        val duree = seances.sumOf { it.dureeMinutes }

        val distancePrecedente = precedentes.sumOf { it.distanceKm }

        StatistiquesResponse(
            nombreSeances = seances.size,
            distanceTotaleKm = distance.arrondi2(),
            dureeTotaleMinutes = duree,
            allureMoyenneMinParKm = (duree / distance).arrondi2(),
            volumeParType = volumeParType,
            calculeEnMs = 0,
            evolution = evolutionHebdomadaire(seances),
            comparaison = Comparaison(
                distanceTotaleKm = distancePrecedente.arrondi2(),
                nombreSeances = precedentes.size,
                // Division par zéro : sans période précédente, aucun pourcentage
                // n'a de sens — on rend null plutôt qu'un « +∞ % » absurde.
                variationDistancePourcent = if (distancePrecedente > 0)
                    (((distance - distancePrecedente) / distancePrecedente) * 100).arrondi2()
                else null
            )
        )
    }

    /** Regroupe les séances par lundi de leur semaine, dans l'ordre chronologique. */
    private fun evolutionHebdomadaire(seances: List<Seance>): List<PointEvolution> =
        seances.groupBy { it.dateHeure.toLocalDate().lundiDeLaSemaine() }
            .map { (lundi, lot) ->
                PointEvolution(
                    semaine = lundi,
                    distanceKm = lot.sumOf { it.distanceKm }.arrondi2(),
                    nombreSeances = lot.size
                )
            }
            .sortedBy { it.semaine }

    /**
     * Records personnels — sur les séances RÉALISÉES uniquement : un record
     * établi par une séance planifiée n'aurait aucun sens.
     */
    fun records(email: String): RecordsResponse {
        val seances = seanceService.toutesLesSeancesRealisees(email)
        if (seances.isEmpty()) {
            return RecordsResponse()
        }

        val parSemaine = seances.groupBy { it.dateHeure.toLocalDate().lundiDeLaSemaine() }
            .mapValues { (_, lot) -> lot.sumOf { it.distanceKm } }

        return RecordsResponse(
            plusLongueDistanceKm = seances.maxOf { it.distanceKm },
            plusLongueDuree = seances.maxOf { it.dureeMinutes },
            // La MEILLEURE allure est la plus PETITE valeur (min/km)
            meilleureAllureMinParKm = seances.minOf { it.allureMinParKm() },
            plusGrosseSemaineKm = parSemaine.values.maxOrNull()?.arrondi2(),
            nombreTotalSeances = seances.size,
            distanceCumuleeKm = seances.sumOf { it.distanceKm }.arrondi2()
        )
    }

    /**
     * Variante `suspend` pure, testable avec runTest sans démarrer Spring.
     * `withContext(Dispatchers.IO)` : bascule sur le pool adapté aux I/O
     * bloquantes — on ne bloque jamais Dispatchers.Default (CPU).
     */
    suspend fun distanceTotale(email: String, debut: LocalDate, fin: LocalDate): Double =
        withContext(Dispatchers.IO) {
            seanceService.seancesDeLaPeriode(email, debut, fin).sumOf { it.distanceKm }.arrondi2()
        }
}
