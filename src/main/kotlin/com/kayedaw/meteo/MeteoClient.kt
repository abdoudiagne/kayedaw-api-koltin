package com.kayedaw.meteo

import com.kayedaw.config.AppProperties
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.net.URI
import java.time.LocalDate

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ APPEL SORTANT — WebClient + coroutines Kotlin                           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Chaque méthode est `suspend` : elle SUSPEND la coroutine pendant l'attente
 * réseau au lieu de BLOQUER un thread. Le code se lit pourtant de haut en bas,
 * comme du synchrone — c'est tout l'intérêt des coroutines par rapport aux
 * chaînes d'opérateurs Reactor (`flatMap`, `zip`…), bien plus difficiles à
 * lire et à déboguer.
 *
 * `awaitSingleOrNull()` (kotlinx-coroutines-reactor) fait le pont Mono -> suspend.
 *
 * RÉSILIENCE — quatre garde-fous, tous indispensables en production :
 *   1. TIMEOUTS      → configurés dans WebClientConfig
 *   2. RETRY         → backoff EXPONENTIEL, et uniquement sur les erreurs
 *                      transitoires (5xx, réseau). Jamais sur un 4xx : réessayer
 *                      une requête invalide ne fera que marteler le partenaire.
 *   3. REPLI (fallback) → en cas d'échec, on retourne null. L'enrichissement est
 *                      un CONFORT : la séance doit être créée quoi qu'il arrive.
 *   4. ISOLATION     → l'échec d'un service externe ne remonte jamais en 500
 *                      à l'utilisateur de notre API.
 */
@Component
class MeteoClient(
    private val webClient: WebClient,
    private val proprietes: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config get() = proprietes.meteo

    companion object {
        /** L'application cible la France : on borne le géocodage en conséquence. */
        private const val PAYS = "FR"
        private const val FUSEAU = "Europe/Paris"
    }

    /** Ville -> coordonnées. Retourne null si la ville est inconnue. */
    suspend fun coordonnees(ville: String): Coordonnees? {
        val reponse = appeler<GeocodageReponse>(
            uri = uri(config.urlGeocodage) {
                it.queryParam("name", ville.trim())
                    .queryParam("count", 1)
                    .queryParam("language", "fr")
                    .queryParam("format", "json")
                    // Sans ce filtre, « Paris » peut renvoyer Paris (Texas)
                    .queryParam("countryCode", PAYS)
            },
            libelle = "géocodage"
        ) ?: return null

        // Chaîne de safe calls : si un maillon est null, tout l'appel vaut null
        return reponse.results?.firstOrNull()?.let {
            Coordonnees(
                ville = it.name,
                latitude = it.latitude,
                longitude = it.longitude,
                // Le code postal porte le département, dont DPClim a besoin
                departement = departementDepuisCodePostal(it.postcodes?.firstOrNull())
            )
        }
    }

    /**
     * Recherche de villes pour l'autocomplétion : jusqu'à `limite` résultats,
     * bornés à la France. Même point d'entrée que `coordonnees`, mais on rend
     * la liste au lieu de ne garder que le premier.
     */
    suspend fun rechercherVilles(terme: String, limite: Int = 8): List<Coordonnees> {
        if (terme.trim().length < 2) {
            return emptyList()          // on n'interroge pas l'API sur une lettre
        }

        val reponse = appeler<GeocodageReponse>(
            uri = uri(config.urlGeocodage) {
                it.queryParam("name", terme.trim())
                    .queryParam("count", limite)
                    .queryParam("language", "fr")
                    .queryParam("format", "json")
                    .queryParam("countryCode", PAYS)
            },
            libelle = "recherche de villes"
        ) ?: return emptyList()

        return reponse.results.orEmpty().map {
            Coordonnees(
                ville = it.name,
                latitude = it.latitude,
                longitude = it.longitude,
                departement = departementDepuisCodePostal(it.postcodes?.firstOrNull())
            )
        }
    }

    /**
     * Météo du jour de la séance.
     *
     * ⚠️ L'archive s'arrête à HIER : elle refuse la date du jour avec
     * « start_date is out of allowed range ». Router aujourd'hui vers l'archive
     * renvoyait donc un aperçu totalement vide — exactement le cas de l'écran
     * de création, dont la date est pré-remplie à maintenant.
     *
     * Règle : strictement passé -> archive ; aujourd'hui ou à venir -> prévision,
     * qui couvre le jour courant et les 16 jours suivants, en horaire.
     */
    suspend fun meteoDuJour(coordonnees: Coordonnees, date: LocalDate): ArchiveMeteoReponse? =
        if (date.isBefore(LocalDate.now())) archive(coordonnees, date)
        else prevision(coordonnees, date)

    private suspend fun archive(coordonnees: Coordonnees, date: LocalDate): ArchiveMeteoReponse? =
        appeler(
            uri = uri(config.urlArchive) {
                it.queryParam("latitude", coordonnees.latitude)
                    .queryParam("longitude", coordonnees.longitude)
                    .queryParam("start_date", date)
                    .queryParam("end_date", date)
                    .queryParam("daily",
                        "temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max")
                    .queryParam("timezone", FUSEAU)
            },
            libelle = "météo"
        )

    /** Prévision pour une séance à venir : mêmes variables, autre point d'entrée. */
    private suspend fun prevision(coordonnees: Coordonnees, date: LocalDate): ArchiveMeteoReponse? =
        appeler(
            uri = uri(config.urlPrevision) {
                it.queryParam("latitude", coordonnees.latitude)
                    .queryParam("longitude", coordonnees.longitude)
                    .queryParam("start_date", date)
                    .queryParam("end_date", date)
                    .queryParam("daily",
                        "temperature_2m_max,temperature_2m_min,precipitation_sum,wind_speed_10m_max")
                    // La série horaire permet de comparer deux créneaux du jour
                    .queryParam("hourly", "temperature_2m,wind_speed_10m")
                    .queryParam("timezone", FUSEAU)
            },
            libelle = "prévision météo"
        )

    /**
     * Qualité de l'air (particules fines) à la même date.
     *
     * L'API qualité de l'air n'expose pas de variable QUOTIDIENNE pour les PM2.5 :
     * on demande la série HORAIRE, dont on tirera le maximum de la journée.
     */
    suspend fun qualiteAir(coordonnees: Coordonnees, date: LocalDate): QualiteAirReponse? =
        appeler(
            uri = uri(config.urlQualiteAir) {
                it.queryParam("latitude", coordonnees.latitude)
                    .queryParam("longitude", coordonnees.longitude)
                    .queryParam("start_date", date)
                    .queryParam("end_date", date)
                    .queryParam("hourly", "pm2_5")
                    .queryParam("timezone", FUSEAU)
            },
            libelle = "qualité de l'air"
        )

    /**
     * ⚠️ PIÈGE — `WebClient.uri(String)` traite la chaîne comme un TEMPLATE et
     * la ré-encode : un `%2F` déjà encodé devenait `%252F`, et l'API répondait
     * « Invalid timezone » en 400. On construit donc l'URI ici, une bonne fois,
     * et on la passe en `java.net.URI` — que WebClient utilise telle quelle.
     *
     * Bonus : les valeurs sont encodées correctement, ce qu'une concaténation
     * de chaînes ne faisait pas (une ville comme « Le Mans » cassait l'URL).
     */
    private fun uri(base: String, parametres: (UriComponentsBuilder) -> UriComponentsBuilder): URI =
        parametres(UriComponentsBuilder.fromUriString(base)).build().encode().toUri()

    /**
     * Méthode générique : `reified` permet de récupérer le type à l'exécution,
     * ce que Java ne peut pas faire (effacement de type). C'est ce qui autorise
     * `bodyToMono<T>()` sans passer une Class<T> en paramètre.
     */
    private suspend inline fun <reified T : Any> appeler(uri: URI, libelle: String): T? =
        runCatching {
            webClient.get()
                .uri(uri)
                .retrieve()
                // Un 4xx n'est PAS réessayable : on le transforme en échec immédiat
                .onStatus(HttpStatusCode::is4xxClientError) { reponse ->
                    Mono.error(WebClientResponseException.create(
                        reponse.statusCode().value(), "requête refusée par $libelle",
                        reponse.headers().asHttpHeaders(), ByteArray(0), null))
                }
                .bodyToMono<T>()
                .retryWhen(strategieRetry(libelle))
                .awaitSingleOrNull()                       // pont Reactor -> coroutine
        }.onFailure {
            // Repli : on journalise et on rend null. Jamais d'exception vers l'appelant.
            log.warn("service {} indisponible ({}), enrichissement ignoré", libelle, it.message)
        }.getOrNull()

    /** Backoff exponentiel, borné, et uniquement sur les erreurs transitoires. */
    fun strategieRetry(libelle: String): Retry =
        Retry.backoff(config.tentatives, config.delaiEntreTentatives)
            .filter { erreur -> estTransitoire(erreur) }
            .doBeforeRetry { log.info("nouvelle tentative sur {} : {}", libelle, it.failure().message) }
            .onRetryExhaustedThrow { _, signal -> signal.failure() }

    /**
     * Seules ces erreurs justifient une nouvelle tentative.
     * Un 400 ou un 404 se reproduiraient à l'identique.
     */
    fun estTransitoire(erreur: Throwable): Boolean = when (erreur) {
        is WebClientResponseException -> erreur.statusCode.is5xxServerError
        else -> true    // timeout, connexion refusée, coupure réseau…
    }
}
