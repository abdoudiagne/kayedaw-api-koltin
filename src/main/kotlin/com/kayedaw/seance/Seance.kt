package com.kayedaw.seance

import com.kayedaw.common.arrondi2
import com.kayedaw.user.Utilisateur
import jakarta.persistence.*
import java.time.LocalDateTime

enum class TypeSeance { ENDURANCE, FRACTIONNE, SORTIE_LONGUE, RECUPERATION, MARCHE }

@Entity
@Table(
    name = "seance",
    indexes = [Index(name = "idx_seance_user_date", columnList = "utilisateur_id,date_heure")]
)
class Seance(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: TypeSeance,

    @Column(nullable = false)
    var distanceKm: Double,

    @Column(nullable = false)
    var dureeMinutes: Int,

    /**
     * Date ET HEURE de la séance : une sortie de 7 h et une de 19 h n'ont ni la
     * même météo ni le même sens dans un plan d'entraînement. Le jour seul ne
     * permettait pas non plus de planifier deux séances distinctes le même jour.
     */
    @Column(name = "date_heure", nullable = false)
    var dateHeure: LocalDateTime,

    @Column(length = 500)
    var commentaire: String? = null,

    // ---- Enrichissement météo (best-effort : peut rester null) ----
    @Column(length = 100)
    var ville: String? = null,

    /**
     * `temperatureMaxC` et non `temperatureC` : la valeur est le MAXIMUM du jour.
     * Un nom vague à côté d'un `temperatureMinC` serait un piège à contresens.
     */
    var temperatureMaxC: Double? = null,

    var temperatureMinC: Double? = null,

    /** Température relevée à l'heure de la séance (source Météo-France). */
    var temperatureALHeureC: Double? = null,

    var precipitationMm: Double? = null,

    var ventKmH: Double? = null,

    /** Pic de particules fines PM2.5 du jour, en µg/m³. */
    var pm25: Double? = null,

    /** Provenance de la donnée : observation, archive ou prévision. */
    @Column(length = 40)
    var sourceMeteo: String? = null,

    /** Station Météo-France retenue, le cas échéant. */
    @Column(length = 100)
    var stationMeteo: String? = null,

    @Column(length = 200)
    var alertesMeteo: String? = null,

    /**
     * FetchType.LAZY volontaire : sans cela, chaque chargement de séance
     * ramènerait l'utilisateur — un des visages du problème N+1.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    var utilisateur: Utilisateur,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
) {
    /**
     * Une séance à venir est PLANIFIÉE : elle compte dans le plafond
     * hebdomadaire (pour éviter de sur-planifier) mais jamais dans les
     * statistiques, qui ne doivent refléter que ce qui a réellement été couru.
     */
    fun estPlanifiee(reference: LocalDateTime = LocalDateTime.now()): Boolean =
        dateHeure.isAfter(reference)

    /** Allure moyenne en minutes par kilomètre — la métrique du coureur. */
    fun allureMinParKm(): Double = (dureeMinutes / distanceKm).arrondi2()

    /** Vitesse moyenne en km/h. */
    fun vitesseKmH(): Double = (distanceKm / (dureeMinutes / 60.0)).arrondi2()

    /**
     * Intensité relative — illustre un `when` sur enum.
     * Le compilateur vérifie l'exhaustivité car le résultat est utilisé
     * comme expression (pas besoin de `else` si tous les cas sont couverts).
     */
    fun intensite(): String = when (type) {
        TypeSeance.FRACTIONNE -> "élevée"
        TypeSeance.SORTIE_LONGUE -> "modérée-longue"
        TypeSeance.ENDURANCE -> "modérée"
        TypeSeance.RECUPERATION -> "faible"
        TypeSeance.MARCHE -> "très faible"
    }
}
