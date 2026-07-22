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
        /** Pays retenu quand l'utilisateur n'en a pas renseigné. */
        const val PAYS_PAR_DEFAUT = "France"

        /**
         * ⚠️ CONTOURNEMENT D'UN DÉFAUT D'OPEN-METEO, ne pas « simplifier » en 1.
         *
         * Le géocodeur renvoie ZÉRO résultat avec `count=1` là où `count=2` en
         * renvoie un. Mesuré et reproductible : « Bargny » et « Saint-Louis »
         * (Sénégal) sont introuvables à 1, trouvés à 2. Aucune ville française
         * testée n'est touchée, ce qui rendait le défaut invisible tant que
         * l'application était bornée à la France.
         *
         * On demande donc deux résultats et l'on garde le premier.
         */
        private const val MINIMUM_RESULTATS = 2

        /**
         * ┌───────────────────────────────────────────────────────────────────┐
         * │ Pourquoi 100, le MAXIMUM accepté, et pas « une marge raisonnable »│
         * └───────────────────────────────────────────────────────────────────┘
         *
         * Le géocodeur classe MONDIALEMENT avant de tronquer, puis applique le
         * filtre de pays. Une commune modeste est donc évincée par ses
         * homonymes plus peuplés bien avant que son pays ne soit pris en
         * compte. Mesuré sur « bambi » au Sénégal :
         *
         *   count=20  → 20 résultats (Angola, Tanzanie, Centrafrique…), 0 au Sénégal
         *   count=50  → 50 résultats, toujours 0 au Sénégal
         *   count=100 → 85 résultats, dont **Bambilor**
         *
         * Une marge proportionnelle à la limite d'affichage (`limite * 3`) ne
         * suffisait pas : elle borne ce qu'on AFFICHE, pas la profondeur à
         * laquelle la bonne réponse se trouve. Les grandes villes restent en
         * tête — « dak » rend toujours Dakar en premier — donc rien n'est perdu
         * à demander large.
         */
        private const val RESULTATS_GEOCODAGE = 100
    }

    /**
     * Correspondance de pays.
     *
     * On privilégie le CODE ISO, que le géocodeur accepte en paramètre et qui
     * ne dépend d'aucune orthographe. Le rapprochement par nom ne sert plus que
     * de repli, pour un pays saisi hors du référentiel.
     */
    private fun correspondAuPays(resultat: ResultatGeocodage, pays: String): Boolean {
        val attendu = Pays.code(pays)
        return if (attendu != null && resultat.country_code != null) {
            resultat.country_code.equals(attendu, ignoreCase = true)
        } else {
            normaliser(resultat.country) == normaliser(pays)
        }
    }

    /** Compare sans accents ni casse : « Sénégal » doit valoir « senegal ». */
    private fun normaliser(valeur: String?): String =
        java.text.Normalizer.normalize(valeur.orEmpty(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()

    private fun versCoordonnees(r: ResultatGeocodage) = Coordonnees(
        ville = r.name,
        latitude = r.latitude,
        longitude = r.longitude,
        // Le code postal porte le département, dont DPClim a besoin
        departement = departementDepuisCodePostal(r.postcodes?.firstOrNull()),
        region = r.admin1,
        pays = r.country,
        fuseau = r.timezone ?: "Europe/Paris"
    )

    /**
     * Ville + pays -> coordonnées. Retourne null si la ville est inconnue.
     *
     * Le pays lève l'homonymie que le `countryCode=FR` en dur réglait avant :
     * « Paris » ne peut plus renvoyer Paris (Texas) tant que le compte est en
     * France, et « Dakar » ne renvoie pas celui de Syrie pour un compte
     * sénégalais.
     */
    suspend fun coordonnees(ville: String, pays: String = PAYS_PAR_DEFAUT): Coordonnees? {
        val reponse = appeler<GeocodageReponse>(
            uri = uri(config.urlGeocodage) {
                it.queryParam("name", ville.trim())
                    .queryParam("count", RESULTATS_GEOCODAGE)
                    .queryParam("language", "fr")
                    .queryParam("format", "json")
                    .apply { Pays.code(pays)?.let { iso -> queryParam("countryCode", iso) } }
            },
            libelle = "géocodage"
        ) ?: return null

        val resultats = reponse.results.orEmpty()
        // On préfère une homonyme du BON pays ; à défaut on ne devine pas.
        return resultats.firstOrNull { correspondAuPays(it, pays) }?.let(::versCoordonnees)
    }

    /**
     * Recherche de villes pour l'autocomplétion : jusqu'à `limite` résultats,
     * bornés à la France. Même point d'entrée que `coordonnees`, mais on rend
     * la liste au lieu de ne garder que le premier.
     */
    suspend fun rechercherVilles(
        terme: String,
        limite: Int = 8,
        pays: String = PAYS_PAR_DEFAUT
    ): List<Coordonnees> {
        if (terme.trim().length < 2) {
            return emptyList()          // on n'interroge pas l'API sur une lettre
        }

        val reponse = appeler<GeocodageReponse>(
            uri = uri(config.urlGeocodage) {
                it.queryParam("name", terme.trim())
                    .queryParam("count", maxOf(RESULTATS_GEOCODAGE, MINIMUM_RESULTATS))
                    .queryParam("language", "fr")
                    .queryParam("format", "json")
                    /*
                     * `countryCode` REVIENT — mais dérivé du pays demandé, là où
                     * il était autrefois figé sur `FR`. Il allège la réponse et
                     * le géocodeur l'honore.
                     *
                     * ⚠️ Il ne DISPENSE PAS du filtrage local ni du `count`
                     * élevé : à `count=20`, « bambi » + `countryCode=SN` rend
                     * zéro résultat, et il en rend un à `count=100`. La
                     * troncature mondiale s'applique donc avant lui. Et il ne
                     * peut rien quand on ne connaît que le NOM du pays, sans
                     * code ISO correspondant.
                     */
                    .apply { Pays.code(pays)?.let { iso -> queryParam("countryCode", iso) } }
            },
            libelle = "recherche de villes"
        ) ?: return emptyList()

        return reponse.results.orEmpty()
            .filter { correspondAuPays(it, pays) }
            .take(limite)
            .map(::versCoordonnees)
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
                    /*
                     * La série HORAIRE manquait à l'archive alors que la
                     * prévision la demandait déjà. Conséquence : une séance
                     * passée enrichie par l'archive n'avait pas de
                     * `temperatureALHeureC` — on savait qu'il avait fait 32 °C
                     * ce jour-là, pas la chaleur subie à 7 h. Invisible en
                     * France, où DPClim fournit l'heure ; systématique partout
                     * ailleurs, l'archive y étant le seul recours.
                     */
                    .queryParam("hourly", "temperature_2m,wind_speed_10m")
                    .queryParam("timezone", coordonnees.fuseau)
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
                    .queryParam("timezone", coordonnees.fuseau)
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
                    .queryParam("timezone", coordonnees.fuseau)
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
