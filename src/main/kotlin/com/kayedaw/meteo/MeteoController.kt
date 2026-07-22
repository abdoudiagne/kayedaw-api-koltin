package com.kayedaw.meteo

import kotlinx.coroutines.runBlocking
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import com.kayedaw.common.ouSiVide
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

data class ConditionsResponse(
    val ville: String,
    val temperatureMaxC: Double?,
    val temperatureMinC: Double?,
    /** Valeur à l'heure demandée : c'est elle qui guide le choix du créneau. */
    val temperatureALHeureC: Double?,
    val precipitationMm: Double?,
    val ventMaxKmH: Double?,
    val pm25: Double?,
    /** OBSERVATION_METEO_FRANCE, ARCHIVE_OPEN_METEO ou PREVISION_OPEN_METEO. */
    val source: String,
    val station: String?,
    val alertes: List<String>
)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ LE PONT ENTRE SPRING MVC ET LES COROUTINES                              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Ce projet est sur la stack SERVLET (spring-boot-starter-web), pas WebFlux.
 * Le handler ne peut donc pas être `suspend` : on ouvre un scope avec
 * `runBlocking` à la frontière.
 *
 * EST-CE UN PROBLÈME ? Non, et c'est le point à savoir défendre en entretien :
 * avec Java 21, ce `runBlocking` s'exécute sur un THREAD VIRTUEL. Le blocage
 * démonte le thread virtuel de son porteur, qui repart servir d'autres requêtes.
 * On garde donc un modèle simple à lire, sans payer le prix du blocage.
 *
 * En WebFlux, ce controller serait directement `suspend fun` et le pont
 * disparaîtrait — mais toute l'application devrait être réactive de bout en bout
 * (y compris l'accès aux données, ce que JPA ne permet pas).
 */
@RestController
@RequestMapping("/api/meteo")
class MeteoController(
    private val enrichissement: EnrichissementMeteoService,
    private val client: MeteoClient
) {

    /**
     * Référentiel des pays proposés à la saisie.
     *
     * Servi par l'API et non écrit en dur côté front : le géocodage dépend de
     * ce même référentiel, et deux listes divergentes produiraient des pays
     * sélectionnables pour lesquels aucune ville ne serait trouvable.
     */
    @GetMapping("/pays")
    fun pays(): List<PaysDto> = Pays.TOUS

    /**
     * Autocomplétion du champ ville. Le front n'appelle PAS le géocodeur
     * directement : passer par l'API évite d'exposer un service tiers au
     * navigateur, et permettrait d'y ajouter un cache ou un quota.
     */
    @GetMapping("/villes")
    fun villes(
        @RequestParam(name = "q") terme: String,
        /**
         * Optionnel et non obligatoire : l'autocomplétion est publique (elle
         * sert à l'inscription, avant toute session) et un ancien client qui
         * ne l'envoie pas doit continuer de fonctionner — sur la France.
         */
        @RequestParam(required = false) pays: String?
    ): List<SuggestionVille> =
        runBlocking { client.rechercherVilles(terme, pays = pays.ouSiVide(MeteoClient.PAYS_PAR_DEFAUT)) }
            /*
             * Le département quand on l'a — c'est le repère qu'attend un
             * utilisateur français — la RÉGION à défaut. Hors de France, le
             * code postal ne donne rien : la liste n'affichait alors que des
             * noms nus, sans rien qui les rattache au pays choisi. Le filtre
             * était pourtant correct ; c'est la LISIBILITÉ qui manquait.
             */
            .map {
                SuggestionVille(it.ville, it.departement ?: it.region, it.latitude, it.longitude)
            }

    /**
     * Conditions pour une ville et un instant.
     *
     * `dateHeure` est privilégié quand il est fourni : l'aperçu du formulaire
     * de séance doit refléter l'HEURE choisie, pas une moyenne de journée —
     * courir à 7 h ou à 18 h n'a ni la même température ni le même vent.
     * `date` reste accepté pour les appels qui ne connaissent que le jour ;
     * on vise alors midi, heure représentative.
     */
    @GetMapping("/conditions")
    fun conditions(
        @RequestParam ville: String,
        @RequestParam(required = false) pays: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) dateHeure: LocalDateTime?
    ): ResponseEntity<ConditionsResponse> {
        val instant = dateHeure
            ?: date?.atTime(12, 0)
            ?: return ResponseEntity.badRequest().build()

        val conditions = runBlocking {
            enrichissement.conditions(ville, instant, pays.ouSiVide(MeteoClient.PAYS_PAR_DEFAUT))
        }
            ?: return ResponseEntity.noContent().build()      // 204 : service indisponible ou ville inconnue

        return ResponseEntity.ok(
            ConditionsResponse(
                ville = conditions.ville,
                temperatureMaxC = conditions.temperatureMaxC,
                temperatureMinC = conditions.temperatureMinC,
                temperatureALHeureC = conditions.temperatureALHeureC,
                precipitationMm = conditions.precipitationMm,
                ventMaxKmH = conditions.ventMaxKmH,
                pm25 = conditions.pm25,
                source = conditions.source.name,
                station = conditions.station,
                alertes = conditions.alertes()
            )
        )
    }
}

/** Suggestion d'autocomplétion : juste ce dont le front a besoin. */
data class SuggestionVille(
    val nom: String,
    /**
     * Repère géographique affiché sous le nom : numéro de département en
     * France, région administrative ailleurs. Le champ garde son nom pour ne
     * pas casser le client Angular, qui le rend tel quel.
     */
    val departement: String? = null,
    val latitude: Double,
    val longitude: Double
)
