package com.kayedaw.meteo

import com.kayedaw.common.arrondi2
import com.kayedaw.config.AppProperties
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.web.util.UriComponentsBuilder
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ MÉTÉO-FRANCE DPClim — observations RÉELLES, en trois temps              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Contrairement à une API REST classique, DPClim est ASYNCHRONE :
 *
 *   1. `/liste-stations/horaire?id-departement=59` → les stations du département
 *   2. `/commande-station/horaire?...`             → 202 + un numéro de commande
 *   3. `/commande/fichier?id-cmde=...`             → 201 avec le CSV, ou 204
 *                                                    « pas encore prêt »
 *
 * D'où la boucle de récupération : on redemande le fichier tant qu'on reçoit
 * 204, avec un nombre d'essais borné. En pratique il est prêt au premier essai,
 * mais s'appuyer là-dessus rendrait l'intégration fragile.
 *
 * POURQUOI DPCLIM PLUTÔT QU'UN MODÈLE DE PRÉVISION (AROME) : ce sont des
 * OBSERVATIONS de stations, ce qu'on veut pour un carnet d'entraînement.
 * AROME ne publie que des grilles GRIB de prévision, sur quelques jours.
 */
@Component
class MeteoFranceClient(
    private val webClient: WebClient,
    private val jetonService: JetonMeteoFranceService,
    private val proprietes: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config get() = proprietes.meteoFrance

    /**
     * La liste des stations d'un département ne bouge pas d'une requête à
     * l'autre : on la mémorise pour éviter un appel réseau par séance.
     */
    private val stationsParDepartement = ConcurrentHashMap<String, List<StationClim>>()

    val actif: Boolean get() = config.actif

    private companion object {
        /** Nombre de stations tentées avant de renoncer et de basculer sur Open-Meteo. */
        const val CANDIDATES_MAX = 3
    }

    /**
     * Observations horaires de la journée, à la station la plus proche.
     * Retourne null dès qu'un maillon manque : l'appelant bascule sur Open-Meteo.
     */
    suspend fun observations(coordonnees: Coordonnees, dateHeure: LocalDateTime): ObservationsJour? {
        if (!config.actif) {
            return null
        }

        val departement = coordonnees.departement ?: return null.also {
            log.debug("département inconnu pour {}, DPClim ignoré", coordonnees.ville)
        }

        return runCatching {
            val jeton = jetonService.jeton() ?: return null

            /*
             * Toutes les stations n'ont pas de données tous les jours : la plus
             * proche peut très bien ne rien renvoyer (Toulouse en a fait la
             * démonstration). On essaie donc les CANDIDATES par ordre de
             * proximité plutôt que d'abandonner à la première.
             */
            stationsProches(departement, coordonnees, jeton).firstNotNullOfOrNull { station ->
                commanderPuisRecuperer(station, dateHeure, jeton)
                    ?.let { AnalyseurCsvDpclim.analyser(it, dateHeure) }
                    ?.copy(station = station.nom)
            }
        }.onFailure {
            log.warn("DPClim indisponible ({}), repli sur Open-Meteo", it.message)
        }.getOrNull()
    }

    // ── 1. Stations ──────────────────────────────────────────────────────────
    /** Les stations ouvertes du département, de la plus proche à la plus lointaine. */
    private suspend fun stationsProches(
        departement: String, coordonnees: Coordonnees, jeton: String
    ): List<StationClim> {
        val stations = stationsParDepartement.getOrPut(departement) {
            appeler<List<StationClim>>(
                "${config.urlDpclim}/liste-stations/horaire?id-departement=$departement", jeton)
                ?: emptyList()
        }

        // Distance euclidienne sur lat/lon : approximation acceptable à
        // l'échelle d'un département, et sans dépendance géospatiale.
        return stations.filter { it.posteOuvert }.sortedBy {
            val dLat = it.lat - coordonnees.latitude
            val dLon = (it.lon - coordonnees.longitude) * 0.64   // correction méridien ~45°N
            dLat * dLat + dLon * dLon
        }.take(CANDIDATES_MAX)
    }

    // ── 2 et 3. Commande puis récupération du fichier ────────────────────────
    private suspend fun commanderPuisRecuperer(
        station: StationClim, dateHeure: LocalDateTime, jeton: String
    ): String? = runCatching {
        val jour = dateHeure.toLocalDate()
        val uri = UriComponentsBuilder.fromUriString("${config.urlDpclim}/commande-station/horaire")
            .queryParam("id-station", station.id)
            .queryParam("date-deb-periode", "${jour}T00:00:00Z")
            .queryParam("date-fin-periode", "${jour}T23:00:00Z")
            .build().encode().toUri()

        val commande = webClient.get().uri(uri)
            .header("Authorization", "Bearer $jeton")
            .retrieve()
            .awaitBody<ReponseCommande>()
            .elaboreProduitAvecDemandeResponse?.retour ?: return null

        repeat(config.tentativesFichier) { essai ->
            val reponse = webClient.get()
                .uri("${config.urlDpclim}/commande/fichier?id-cmde=$commande")
                .header("Authorization", "Bearer $jeton")
                .retrieve()
                .toEntity(String::class.java)
                .awaitSingleOrNull()

            // 201 = prêt · 204 = en cours de fabrication · autre = abandon
            when (reponse?.statusCode?.value()) {
                201 -> return reponse.body
                204 -> delay(config.delaiEntreTentatives.toMillis())
                else -> return null
            }
            if (essai == config.tentativesFichier - 1) {
                log.info("fichier DPClim toujours pas prêt après {} essais", config.tentativesFichier)
            }
        }
        null
    }.getOrElse {
        // 404 = cette station n'a pas de fichier pour ce jour : on passe à la suivante
        log.debug("station {} sans données ({})", station.nom, it.message)
        null
    }

    private suspend inline fun <reified T : Any> appeler(uri: String, jeton: String): T? =
        runCatching {
            webClient.get().uri(uri)
                .header("Authorization", "Bearer $jeton")
                .retrieve()
                .awaitBody<T>()
        }.onFailure { log.warn("appel DPClim en échec ({})", it.message) }.getOrNull()
}

/** Station climatologique telle que listée par DPClim. */
data class StationClim(
    val id: String,
    val nom: String,
    val posteOuvert: Boolean = true,
    val lat: Double,
    val lon: Double
)

/** Enveloppe SOAP-esque de la réponse de commande. */
data class ReponseCommande(
    val elaboreProduitAvecDemandeResponse: RetourCommande? = null
)

data class RetourCommande(
    @com.fasterxml.jackson.annotation.JsonProperty("return")
    val retour: String? = null
)

/** Observations retenues pour une journée, plus la valeur à l'heure de la séance. */
data class ObservationsJour(
    val temperatureMaxC: Double? = null,
    val temperatureMinC: Double? = null,
    val temperatureALHeureC: Double? = null,
    val precipitationMm: Double? = null,
    val ventMaxKmH: Double? = null,
    val station: String? = null
)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ ANALYSE DU CSV DPClim                                                   │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Format : séparateur `;`, une ligne par heure, DATE au format `YYYYMMDDHH`,
 * et — piège classique d'un fichier français — la VIRGULE comme séparateur
 * décimal (`13,7`). Les colonnes utiles ici :
 *   T    température de l'air (°C)      RR1  précipitation horaire (mm)
 *   FF   vent moyen 10 min (m/s)
 *
 * Les cellules vides sont fréquentes : chaque conversion est donc tolérante.
 */
object AnalyseurCsvDpclim {

    fun analyser(csv: String, dateHeure: LocalDateTime): ObservationsJour? {
        val lignes = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lignes.size < 2) {
            return null
        }

        val colonnes = lignes.first().split(';').withIndex().associate { (i, nom) -> nom.trim() to i }
        val iDate = colonnes["DATE"] ?: return null

        val mesures = lignes.drop(1).mapNotNull { ligne ->
            val cases = ligne.split(';')
            val horodatage = cases.getOrNull(iDate)?.trim() ?: return@mapNotNull null
            MesureHoraire(
                heure = horodatage.takeLast(2).toIntOrNull() ?: return@mapNotNull null,
                temperature = cases.decimal(colonnes["T"]),
                pluie = cases.decimal(colonnes["RR1"]),
                ventMs = cases.decimal(colonnes["FF"])
            )
        }
        if (mesures.isEmpty()) {
            return null
        }

        val temperatures = mesures.mapNotNull { it.temperature }
        // Heure la plus proche de celle de la séance (et non la première venue)
        val aLHeure = mesures.minByOrNull { abs(it.heure - dateHeure.hour) }?.temperature

        return ObservationsJour(
            temperatureMaxC = temperatures.maxOrNull(),
            temperatureMinC = temperatures.minOrNull(),
            temperatureALHeureC = aLHeure,
            // `arrondi2` partout : sans lui, 6,9 m/s ressort en 24.840000000000003
            precipitationMm = mesures.mapNotNull { it.pluie }.takeIf { it.isNotEmpty() }?.sum()?.arrondi2(),
            // m/s -> km/h : l'API expose du m/s, l'utilisateur raisonne en km/h
            ventMaxKmH = mesures.mapNotNull { it.ventMs }.maxOrNull()?.let { (it * 3.6).arrondi2() }
        )
    }

    private data class MesureHoraire(
        val heure: Int,
        val temperature: Double?,
        val pluie: Double?,
        val ventMs: Double?
    )

    /** Cellule vide ou illisible -> null ; virgule décimale -> point. */
    private fun List<String>.decimal(index: Int?): Double? =
        index?.let { getOrNull(it) }?.trim()?.replace(',', '.')?.toDoubleOrNull()
}
