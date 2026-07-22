package com.kayedaw.meteo

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ ORCHESTRATION DE PLUSIEURS APPELS SORTANTS                              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Séquence réelle :
 *   1. géocodage (obligatoire d'abord : il fournit les coordonnées)
 *   2. météo ET qualité de l'air, lancées EN PARALLÈLE
 *
 * Séquentiel : t1 + t2 + t3. Ici : t1 + max(t2, t3).
 *
 * `coroutineScope` applique la CONCURRENCE STRUCTURÉE : si une tâche fille
 * échoue ou si l'appelant est annulé, les autres sont annulées automatiquement.
 * Aucune requête orpheline ne continue en arrière-plan — c'est le gros avantage
 * sur un CompletableFuture mal maîtrisé.
 *
 * `withTimeoutOrNull` : borne globale de l'enrichissement. Même si chaque appel
 * individuel a son timeout, on garantit ici un plafond pour l'ensemble.
 */
@Service
class EnrichissementMeteoService(
    private val client: MeteoClient,
    private val meteoFrance: MeteoFranceClient
) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Au-delà, on renonce à l'enrichissement : la séance prime. */
        private const val BUDGET_TOTAL_MS = 5_000L

        /**
         * Budget PROPRE à Météo-France, plus court que le budget global.
         *
         * Sans cette borne interne, une chaîne DPClim lente (commande + fichier,
         * éventuellement sur plusieurs stations) épuisait le budget total et
         * faisait perdre AUSSI les résultats Open-Meteo déjà obtenus : la séance
         * ressortait sans ville ni météo. La borne interne isole ce risque.
         */
        private const val BUDGET_METEO_FRANCE_MS = 2_500L
    }

    /**
     * Une seule signature, en LocalDateTime : deux surcharges (date / date-heure)
     * créaient une ambiguïté de résolution dès qu'un test passait `any()`.
     */
    suspend fun conditions(
        ville: String,
        dateHeure: LocalDateTime,
        pays: String = MeteoClient.PAYS_PAR_DEFAUT
    ): ConditionsSeance? =
        withTimeoutOrNull(BUDGET_TOTAL_MS) {
            val date: LocalDate = dateHeure.toLocalDate()
            val coordonnees = client.coordonnees(ville, pays)
            if (coordonnees == null) {
                log.info("ville inconnue : {} ({}), enrichissement ignoré", ville, pays)
                return@withTimeoutOrNull null
            }

            coroutineScope {
                // Les deux appels partent simultanément
                val meteoDeferred = async { client.meteoDuJour(coordonnees, date) }
                val airDeferred = async { client.qualiteAir(coordonnees, date) }
                // Troisième appel lancé en parallèle, seulement s'il a du sens
                val observationsDeferred =
                    if (date.isAfter(LocalDate.now())) null
                    else async {
                        withTimeoutOrNull(BUDGET_METEO_FRANCE_MS) {
                            meteoFrance.observations(coordonnees, dateHeure)
                        }
                    }

                val meteo = meteoDeferred.await()
                val air = airDeferred.await()

                val estFuture = date.isAfter(LocalDate.now())
                // Aujourd'hui passe aussi par la prévision : l'archive s'arrête à hier
                val previsionUtilisee = !date.isBefore(LocalDate.now())

                /*
                 * Météo-France en PREMIER pour une séance passée : ce sont des
                 * observations de station, pas une réanalyse. Le repli sur
                 * Open-Meteo couvre les cas où DPClim n'a rien (station absente,
                 * département indéterminé, service en panne, intégration
                 * désactivée faute de secret).
                 *
                 * Pour une séance PLANIFIÉE la question ne se pose pas : aucune
                 * observation n'existe encore, seule la prévision a du sens.
                 */
                val observations = if (estFuture) null else observationsDeferred?.await()

                ConditionsSeance(
                    ville = coordonnees.ville,
                    temperatureMaxC = observations?.temperatureMaxC
                        ?: meteo?.daily?.temperature_2m_max?.firstOrNull(),
                    temperatureMinC = observations?.temperatureMinC
                        ?: meteo?.daily?.temperature_2m_min?.firstOrNull(),
                    // Passé -> observation Météo-France ; futur -> prévision horaire
                    temperatureALHeureC = observations?.temperatureALHeureC
                        ?: meteo?.temperatureA(dateHeure.hour),
                    precipitationMm = observations?.precipitationMm
                        ?: meteo?.daily?.precipitation_sum?.firstOrNull(),
                    ventMaxKmH = observations?.ventMaxKmH
                        ?: meteo?.ventA(dateHeure.hour)
                        ?: meteo?.daily?.wind_speed_10m_max?.firstOrNull(),
                    pm25 = air?.pm25Max(),
                    source = when {
                        observations != null -> SourceMeteo.OBSERVATION_METEO_FRANCE
                        // On annonce la PRÉVISION dès qu'elle est la source réelle,
                        // sinon l'écran présenterait une prévision comme un relevé
                        previsionUtilisee -> SourceMeteo.PREVISION_OPEN_METEO
                        else -> SourceMeteo.ARCHIVE_OPEN_METEO
                    },
                    station = observations?.station
                )
            }
        }
}
