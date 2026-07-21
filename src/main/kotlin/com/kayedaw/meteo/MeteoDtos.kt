package com.kayedaw.meteo

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * DTO d'API externe : on ne mappe QUE les champs utiles.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)` est indispensable sur un
 * contrat qu'on ne maîtrise pas : si le fournisseur ajoute un champ demain,
 * la désérialisation ne doit pas casser. C'est le principe de robustesse
 * (« sois strict sur ce que tu envoies, tolérant sur ce que tu reçois »).
 */

// ---------- Géocodage : ville -> coordonnées ----------
@JsonIgnoreProperties(ignoreUnknown = true)
data class GeocodageReponse(val results: List<ResultatGeocodage>? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ResultatGeocodage(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    /** Codes postaux de la commune : c'est d'eux qu'on déduit le département. */
    val postcodes: List<String>? = null
)

/** Objet-valeur du domaine, découplé du format de l'API externe. */
data class Coordonnees(
    val ville: String,
    val latitude: Double,
    val longitude: Double,
    /** Numéro de département, requis par DPClim (« 59 », « 2A », « 974 »…). */
    val departement: String? = null
)

/**
 * Département déduit du code postal.
 *
 * Trois cas particuliers, sinon on se trompe de département :
 *   - l'outre-mer est sur TROIS chiffres (97x / 98x) ;
 *   - la Corse partage le préfixe 20 et se note 2A / 2B — on renvoie 20,
 *     que DPClim accepte ;
 *   - Paris et la petite couronne n'ont rien de spécial, mais un code postal
 *     comme 75001 doit donner 75, pas 7.
 */
fun departementDepuisCodePostal(codePostal: String?): String? {
    // ⚠️ Le géocodage renvoie aussi des libellés CEDEX : « 69061 CEDEX 06 ».
    // Exiger une chaîne entièrement numérique faisait échouer Lyon, Marseille
    // et toutes les grandes villes — on ne garde donc que les chiffres de tête.
    val cp = codePostal?.trim()?.takeWhile(Char::isDigit)?.takeIf { it.length >= 2 } ?: return null
    return if (cp.startsWith("97") || cp.startsWith("98")) cp.take(3) else cp.take(2)
}

// ---------- Météo du jour ----------
@JsonIgnoreProperties(ignoreUnknown = true)
data class ArchiveMeteoReponse(
    val daily: DonneesQuotidiennes? = null,
    /** Série HORAIRE, demandée uniquement sur la prévision. */
    val hourly: DonneesHoraires? = null
) {
    /**
     * Température à l'heure demandée.
     *
     * Sans elle, une prévision affichait les mêmes valeurs à 7 h et à 18 h :
     * l'utilisateur ne pouvait pas comparer deux créneaux, ce qui était
     * pourtant tout l'intérêt de la planification.
     */
    fun temperatureA(heure: Int): Double? {
        val temps = hourly?.time ?: return null
        val index = temps.indexOfFirst { it.endsWith("T%02d:00".format(heure)) }
        return if (index >= 0) hourly.temperature_2m?.getOrNull(index) else null
    }

    fun ventA(heure: Int): Double? {
        val temps = hourly?.time ?: return null
        val index = temps.indexOfFirst { it.endsWith("T%02d:00".format(heure)) }
        return if (index >= 0) hourly.wind_speed_10m?.getOrNull(index) else null
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DonneesHoraires(
    val time: List<String>? = null,
    val temperature_2m: List<Double?>? = null,
    val wind_speed_10m: List<Double?>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DonneesQuotidiennes(
    val time: List<String>? = null,
    val temperature_2m_max: List<Double?>? = null,
    val temperature_2m_min: List<Double?>? = null,
    val precipitation_sum: List<Double?>? = null,
    val wind_speed_10m_max: List<Double?>? = null
)

// ---------- Qualité de l'air ----------
/**
 * L'API ne propose les PM2.5 qu'en série HORAIRE (aucune variable quotidienne).
 * On expose donc `hourly`, et `pm25Max()` en tire l'indicateur du jour.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class QualiteAirReponse(val hourly: DonneesAir? = null) {

    /** Pic de la journée : c'est lui qui détermine si l'air est respirable. */
    fun pm25Max(): Double? = hourly?.pm2_5?.filterNotNull()?.maxOrNull()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DonneesAir(
    val time: List<String>? = null,
    val pm2_5: List<Double?>? = null
)

/**
 * D'où vient la donnée : l'utilisateur doit savoir s'il lit une OBSERVATION
 * ou une PRÉVISION — les deux n'ont pas la même valeur dans un carnet.
 */
enum class SourceMeteo {
    /** Observations de station Météo-France (DPClim) — le plus fiable. */
    OBSERVATION_METEO_FRANCE,
    /** Réanalyse Open-Meteo, pour une séance passée hors couverture DPClim. */
    ARCHIVE_OPEN_METEO,
    /** Prévision Open-Meteo, pour une séance PLANIFIÉE. */
    PREVISION_OPEN_METEO;

    val estPrevision: Boolean get() = this == PREVISION_OPEN_METEO
}

/**
 * Résultat métier de l'enrichissement.
 * Tous les champs sont nullables : un service externe indisponible ne doit
 * jamais empêcher l'enregistrement d'une séance.
 */
data class ConditionsSeance(
    val ville: String,
    val temperatureMaxC: Double? = null,
    val temperatureMinC: Double? = null,
    /** Température à l'heure de la séance — le vrai apport de la date-heure. */
    val temperatureALHeureC: Double? = null,
    val precipitationMm: Double? = null,
    val ventMaxKmH: Double? = null,
    val pm25: Double? = null,
    val source: SourceMeteo = SourceMeteo.ARCHIVE_OPEN_METEO,
    /** Station Météo-France retenue, quand la source en est une. */
    val station: String? = null
) {
    /**
     * Petite règle métier locale : signale des conditions difficiles.
     * `buildList` : construction idiomatique d'une liste immuable.
     */
    fun alertes(): List<String> = buildList {
        temperatureMaxC?.let { if (it >= 30) add("forte chaleur") }
        temperatureMinC?.let { if (it <= 0) add("gel") }
        ventMaxKmH?.let { if (it >= 40) add("vent fort") }
        precipitationMm?.let { if (it >= 10) add("fortes précipitations") }
        pm25?.let { if (it >= 25) add("qualité de l'air dégradée") }
    }
}
