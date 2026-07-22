package com.kayedaw.admin

import com.kayedaw.preference.PreferenceSeance
import com.kayedaw.preference.PreferenceSeanceRepository
import com.kayedaw.seance.Seance
import com.kayedaw.seance.SeanceRepository
import com.kayedaw.seance.TypeSeance
import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ « SUPPRIMER UN COMPTE EFFACE-T-IL VRAIMENT TOUT ? »                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Deux garanties distinctes, et la seconde est la plus importante :
 *
 *  1. Ce qui existe aujourd'hui part bien — séances et préférences comprises.
 *  2. Rien de NOUVEAU ne pourra survivre en silence. Le second test interroge
 *     le schéma réel : si une table venait à référencer `utilisateur_id` sans
 *     être ajoutée à `AdminService.supprimer`, il échoue. Sans lui, l'oubli ne
 *     se manifesterait qu'en production, sous la forme d'une violation de clé
 *     étrangère — ou pire, de données orphelines si la contrainte manquait.
 *
 * Un test sur la base RÉELLE et non des mocks : c'est le schéma qui fait foi.
 */
@SpringBootTest
@ActiveProfiles("test")
class SuppressionCompleteTest(
    @Autowired private val service: AdminService,
    @Autowired private val utilisateurRepository: UtilisateurRepository,
    @Autowired private val seanceRepository: SeanceRepository,
    @Autowired private val preferenceRepository: PreferenceSeanceRepository,
    @Autowired private val entityManager: EntityManager
) {

    @Test
    fun `supprimer un compte efface ses seances ET ses preferences`() {
        val cible = utilisateurRepository.save(
            Utilisateur(
                email = "asupprimer-${System.nanoTime()}@test.fr",
                motDePasse = "hash", nom = "À supprimer",
                villeParDefaut = "Lille", role = Role.USER
            )
        )
        val id = cible.id!!

        seanceRepository.save(
            Seance(type = TypeSeance.ENDURANCE, distanceKm = 10.0, dureeMinutes = 55,
                dateHeure = LocalDateTime.now().minusDays(2), utilisateur = cible)
        )
        preferenceRepository.save(
            PreferenceSeance(utilisateur = cible, type = TypeSeance.FRACTIONNE,
                distanceKm = 8.0, dureeMinutes = 45)
        )

        // On vérifie que la donnée EXISTE avant : sans ce point de départ, un
        // « zéro » final ne prouverait rien du tout.
        assertThat(seanceRepository.findByUtilisateurIdOrderByDateHeureAsc(id)).isNotEmpty()
        assertThat(preferenceRepository.findByUtilisateurId(id)).isNotEmpty()

        val resultat = service.supprimer(id, "admin@kayedaw.fr")

        assertThat(resultat).isEqualTo(ResultatAdmin.Applique)
        assertThat(utilisateurRepository.findById(id)).isEmpty
        assertThat(seanceRepository.findByUtilisateurIdOrderByDateHeureAsc(id)).isEmpty()
        assertThat(preferenceRepository.findByUtilisateurId(id)).isEmpty()
    }

    /**
     * CANARI DE SCHÉMA.
     *
     * On interroge le schéma pour lister toutes les tables portant une colonne
     * `utilisateur_id`, et on la compare à ce que la suppression traite. Ce
     * test n'a pas vocation à décrire le présent mais à FAIRE ÉCHOUER l'avenir :
     * ajouter une table liée à l'utilisateur sans compléter
     * `AdminService.supprimer` casse ici, à l'endroit exact où se lit la règle.
     *
     * Les statistiques n'y figurent pas et n'ont rien à y faire : elles sont
     * CALCULÉES à la volée à partir des séances, aucune ligne n'est stockée.
     */
    @Test
    fun `aucune table liee a l utilisateur n echappe a la suppression`() {
        @Suppress("UNCHECKED_CAST")
        val tables = entityManager.createNativeQuery(
            """
            SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS
            WHERE COLUMN_NAME = 'UTILISATEUR_ID'
            """.trimIndent()
        ).resultList as List<String>

        // Ce que AdminService.supprimer efface explicitement avant le compte
        val traitees = setOf("SEANCE", "PREFERENCE_SEANCE")

        assertThat(tables.map { it.uppercase() }.toSet())
            .describedAs(
                "Une table référence utilisateur_id sans être traitée par " +
                    "AdminService.supprimer : la suppression laissera des orphelins " +
                    "ou échouera sur la contrainte de clé étrangère."
            )
            .isEqualTo(traitees)
    }
}
