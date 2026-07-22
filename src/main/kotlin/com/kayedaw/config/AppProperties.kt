package com.kayedaw.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 2.4 — @Value ou @ConfigurationProperties ?                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * @Value("\${ma.propriete}") fonctionne, mais :
 *   - il faut ÉCHAPPER le $ en Kotlin (il sert à l'interpolation de chaîne) ;
 *   - aucune validation au démarrage ;
 *   - propriétés éparpillées dans tout le code.
 *
 * @ConfigurationProperties sur une data class : typé, groupé, testable, et
 * l'application refuse de démarrer si une propriété obligatoire manque.
 *
 * Astuce Kotlin : une `data class` avec constructeur = binding par constructeur,
 * donc propriétés IMMUABLES (`val`). Pas de setters, pas d'état modifiable.
 */
@ConfigurationProperties(prefix = "kayedaw")
data class AppProperties(
    val jwt: Jwt,
    val entrainement: Entrainement,
    val meteo: Meteo,
    val meteoFrance: MeteoFrance,
    val httpClient: HttpClient
) {
    data class Jwt(
        /** Clé HMAC encodée en Base64. En production : Vault / variable d'env. */
        val secret: String,
        /** Durée de validité du jeton. Duration se parse depuis "1h", "30m"… */
        val validite: Duration
    )

    data class Entrainement(
        /** Plafond de volume hebdomadaire, en kilomètres. */
        val plafondHebdoKm: Double,
        /** Distance maximale acceptée pour une séance unique. */
        val distanceMaxSeanceKm: Double,
        /**
         * Horizon de PLANIFICATION : au-delà, la séance est refusée.
         *
         * ⚠️ Plus long que la portée des prévisions Open-Meteo (16 jours de
         * fenêtre, soit J+15) : entre les deux, une séance est valide mais
         * revient sans météo. Le front l'annonce à la saisie.
         */
        val planificationMaxJours: Long = 30
    )

    /** Services externes appelés via WebClient (API Open-Meteo, sans clé). */
    data class Meteo(
        val urlGeocodage: String,
        val urlArchive: String,
        val urlQualiteAir: String,
        /** Prévisions : utilisées pour une séance PLANIFIÉE (jusqu'à 16 jours). */
        val urlPrevision: String = "https://api.open-meteo.com/v1/forecast",
        /** Nombre de tentatives supplémentaires en cas d'échec transitoire. */
        val tentatives: Long,
        /** Délai initial du backoff exponentiel entre deux tentatives. */
        val delaiEntreTentatives: Duration
    )

    /**
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ Météo-France — OAuth2 client_credentials                          │
     * └───────────────────────────────────────────────────────────────────┘
     *
     * `applicationId` est le base64 de `client_id:client_secret` fourni par le
     * portail. C'est un SECRET : il vient d'une variable d'environnement et
     * n'est jamais commité. S'il est absent, l'intégration se désactive
     * proprement et l'application retombe sur Open-Meteo.
     */
    data class MeteoFrance(
        val applicationId: String? = null,
        val urlToken: String = "https://portail-api.meteofrance.fr/token",
        val urlDpclim: String = "https://public-api.meteofrance.fr/public/DPClim/v1",
        /** Marge de renouvellement : on rafraîchit avant l'expiration réelle. */
        val margeRenouvellement: Duration = Duration.ofMinutes(5),
        /** Nombre de tentatives de récupération du fichier commandé. */
        val tentativesFichier: Int = 5,
        val delaiEntreTentatives: Duration = Duration.ofMillis(400)
    ) {
        /** L'intégration n'est active que si le secret est fourni. */
        val actif: Boolean get() = !applicationId.isNullOrBlank()
    }

    /** Timeouts du client HTTP sortant : jamais de valeur par défaut infinie. */
    data class HttpClient(
        val connexion: Duration,
        val lecture: Duration,
        val reponse: Duration,
        val maxConnexions: Int
    )
}
