package com.kayedaw.seance

import com.kayedaw.common.arrondi2
import jakarta.validation.constraints.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 2.5 — Pourquoi @field:NotNull et pas simplement @NotNull ?     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Une propriété déclarée dans le constructeur primaire correspond à TROIS
 * cibles possibles : le paramètre du constructeur, le champ, le getter.
 * Sans préfixe, Kotlin applique l'annotation au PARAMÈTRE — et Bean Validation
 * ne la voit pas. La validation ne se déclenche donc jamais.
 *
 * `@field:` place l'annotation sur le champ : c'est ce que lit Hibernate Validator.
 * Erreur extrêmement fréquente, excellent point à mentionner en entretien.
 */
data class CreerSeanceRequest(
    @field:NotNull(message = "le type est obligatoire")
    val type: TypeSeance?,

    @field:Positive(message = "la distance doit être positive")
    @field:Max(value = 200, message = "distance irréaliste pour une séance")
    val distanceKm: Double,

    @field:Min(value = 1, message = "la durée doit être d'au moins 1 minute")
    val dureeMinutes: Int,

    /**
     * Date ET heure de la séance, au format ISO `2026-07-20T18:30`.
     * Peut être dans le futur : la séance est alors PLANIFIÉE (l'horizon
     * autorisé est vérifié côté service, pas ici — c'est une règle métier).
     */
    @field:NotNull(message = "la date et l'heure sont obligatoires")
    val dateHeure: LocalDateTime?,

    @field:Size(max = 500, message = "commentaire trop long (500 caractères max)")
    val commentaire: String? = null,

    /**
     * Optionnel : si renseigné, l'API interroge un service météo externe
     * (WebClient + coroutines) pour enrichir la séance. En cas d'indisponibilité,
     * la séance est créée sans ces informations.
     */
    @field:Size(max = 100)
    val ville: String? = null
)

data class ModifierSeanceRequest(
    @field:NotNull val type: TypeSeance?,
    @field:Positive val distanceKm: Double,
    @field:Min(1) val dureeMinutes: Int,
    @field:NotNull val dateHeure: LocalDateTime?,
    @field:Size(max = 500) val commentaire: String? = null
)

/**
 * DTO de sortie : `data class` cette fois — immuable, avec equals/hashCode/copy.
 * On n'expose JAMAIS l'entité directement (fuite du modèle interne, risque de
 * sérialiser un mot de passe ou une relation lazy).
 */
data class SeanceResponse(
    val id: Long,
    val type: TypeSeance,
    val distanceKm: Double,
    val dureeMinutes: Int,
    val dateHeure: LocalDateTime,
    val estPlanifiee: Boolean,
    val commentaire: String?,
    val allureMinParKm: Double,
    val vitesseKmH: Double,
    val intensite: String,
    val ville: String? = null,
    val temperatureMaxC: Double? = null,
    val temperatureMinC: Double? = null,
    val temperatureALHeureC: Double? = null,
    val precipitationMm: Double? = null,
    val ventKmH: Double? = null,
    val pm25: Double? = null,
    val sourceMeteo: String? = null,
    val stationMeteo: String? = null,
    val alertesMeteo: List<String> = emptyList()
) {
    companion object {
        /**
         * Factory Method. `companion object` = équivalent des membres statiques,
         * mais c'est un véritable objet singleton attaché à la classe.
         */
        fun de(s: Seance) = SeanceResponse(
            id = requireNotNull(s.id) { "séance non persistée" },
            type = s.type,
            distanceKm = s.distanceKm,
            dureeMinutes = s.dureeMinutes,
            dateHeure = s.dateHeure,
            estPlanifiee = s.estPlanifiee(),
            commentaire = s.commentaire,
            allureMinParKm = s.allureMinParKm(),
            vitesseKmH = s.vitesseKmH(),
            intensite = s.intensite(),
            ville = s.ville,
            temperatureMaxC = s.temperatureMaxC,
            temperatureMinC = s.temperatureMinC,
            temperatureALHeureC = s.temperatureALHeureC,
            precipitationMm = s.precipitationMm,
            ventKmH = s.ventKmH,
            pm25 = s.pm25,
            sourceMeteo = s.sourceMeteo,
            stationMeteo = s.stationMeteo,
            // split sur une chaîne vide renvoie [""] : on filtre
            alertesMeteo = s.alertesMeteo?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        )
    }
}

data class StatistiquesResponse(
    val nombreSeances: Int,
    val distanceTotaleKm: Double,
    val dureeTotaleMinutes: Int,
    val allureMoyenneMinParKm: Double,
    val volumeParType: Map<TypeSeance, Double>,
    val calculeEnMs: Long,
    /** Série hebdomadaire, pour tracer l'évolution du volume. */
    val evolution: List<PointEvolution> = emptyList(),
    /** Même durée, juste avant : donne un sens au chiffre brut. */
    val comparaison: Comparaison? = null
) {
    companion object {
        fun vide() = StatistiquesResponse(0, 0.0, 0, 0.0, emptyMap(), 0)
    }
}

/** Un point de la courbe : le lundi de la semaine et son volume. */
data class PointEvolution(
    val semaine: LocalDate,
    val distanceKm: Double,
    val nombreSeances: Int
)

/**
 * Une valeur seule ne dit rien : 120 km sur 30 jours, est-ce beaucoup ?
 * On compare donc à la période précédente de MÊME DURÉE.
 */
data class Comparaison(
    val distanceTotaleKm: Double,
    val nombreSeances: Int,
    val variationDistancePourcent: Double?
)

/** Records personnels, calculés sur les séances RÉALISÉES. */
data class RecordsResponse(
    val plusLongueDistanceKm: Double? = null,
    val plusLongueDuree: Int? = null,
    val meilleureAllureMinParKm: Double? = null,
    val plusGrosseSemaineKm: Double? = null,
    val nombreTotalSeances: Int = 0,
    val distanceCumuleeKm: Double = 0.0
)

/** Réponse renvoyée quand la règle métier bloque la création (HTTP 422). */
data class RefusMetierResponse(
    val motif: String,
    val detail: String,
    val volumeCalculeKm: Double? = null,
    val plafondKm: Double? = null
)
