package com.kayedaw.preference

import com.kayedaw.seance.TypeSeance
import com.kayedaw.user.Utilisateur
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ VALEUR PAR DÉFAUT D'UN TYPE DE SÉANCE, POUR UN UTILISATEUR              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Une ligne = un couple (utilisateur, type). Le formulaire de création s'en
 * sert pour pré-remplir distance et durée dès qu'on choisit un type : un
 * coureur régulier refait souvent la même sortie, ressaisir « 10 » et « 55 »
 * à chaque fois est du travail que la machine peut faire.
 *
 * ⚠️ PAQUET SÉPARÉ, et non un champ de Utilisateur.
 * L'entité a besoin de TypeSeance (paquet `seance`), lequel dépend déjà de
 * Utilisateur (paquet `user`) : loger la relation dans `user` créerait un
 * cycle entre les deux paquets. `preference` dépend des deux, personne ne
 * dépend de lui.
 *
 * L'unicité (utilisateur, type) est portée par la BASE et pas seulement par le
 * code : c'est la seule garantie qui résiste à deux requêtes concurrentes.
 */
@Entity
@Table(
    name = "preference_seance",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_preference_utilisateur_type",
        columnNames = ["utilisateur_id", "type"]
    )]
)
class PreferenceSeance(

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    var utilisateur: Utilisateur,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var type: TypeSeance,

    @Column(name = "distance_km", nullable = false)
    var distanceKm: Double,

    @Column(name = "duree_minutes", nullable = false)
    var dureeMinutes: Int,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)

interface PreferenceSeanceRepository : JpaRepository<PreferenceSeance, Long> {

    fun findByUtilisateurId(utilisateurId: Long): List<PreferenceSeance>

    fun deleteByUtilisateurId(utilisateurId: Long)
}
