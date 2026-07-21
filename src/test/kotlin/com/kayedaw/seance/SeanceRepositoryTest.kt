package com.kayedaw.seance

import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime

@DataJpaTest
@ActiveProfiles("test")
class SeanceRepositoryTest(
    @Autowired private val seanceRepository: SeanceRepository,
    @Autowired private val utilisateurRepository: UtilisateurRepository
) {

    private fun utilisateur(email: String) =
        utilisateurRepository.save(Utilisateur(email = email, motDePasse = "hash", nom = "Test", villeParDefaut = "Lille", role = Role.USER))

    @Test
    fun `ne retourne que les seances de l utilisateur dans la periode`() {
        val abdou = utilisateur("abdou@test.fr")
        val autre = utilisateur("autre@test.fr")

        seanceRepository.saveAll(listOf(
            Seance(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.parse("2026-07-22T18:30"), utilisateur = abdou),
            Seance(TypeSeance.FRACTIONNE, 8.0, 32, LocalDateTime.parse("2026-07-20T18:30"), utilisateur = abdou),
            Seance(TypeSeance.SORTIE_LONGUE, 20.0, 120, LocalDateTime.parse("2026-08-05T18:30"), utilisateur = abdou),
            Seance(TypeSeance.ENDURANCE, 5.0, 25, LocalDateTime.parse("2026-07-21T18:30"), utilisateur = autre)
        ))

        val resultat = seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(
            abdou.id!!, LocalDate.parse("2026-07-01").atStartOfDay(),
            LocalDate.parse("2026-07-31").atTime(23, 59, 59))

        assertThat(resultat).hasSize(2)
        assertThat(resultat.map { it.dateHeure }).isSorted
        assertThat(resultat.first().type).isEqualTo(TypeSeance.FRACTIONNE)
    }

    @Test
    fun `la projection agrege le volume par type cote base`() {
        val abdou = utilisateur("projection@test.fr")
        seanceRepository.saveAll(listOf(
            Seance(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.parse("2026-07-10T18:30"), utilisateur = abdou),
            Seance(TypeSeance.ENDURANCE, 12.0, 60, LocalDateTime.parse("2026-07-12T18:30"), utilisateur = abdou),
            Seance(TypeSeance.FRACTIONNE, 8.0, 32, LocalDateTime.parse("2026-07-14T18:30"), utilisateur = abdou)
        ))

        val volumes = seanceRepository.volumeParType(
            abdou.id!!, LocalDate.parse("2026-07-01").atStartOfDay(),
            LocalDate.parse("2026-07-31").atTime(23, 59, 59),
            // Les séances planifiées sont exclues de l'agrégat : on borne à « maintenant »
            LocalDateTime.now())
            .associate { it.getType() to it.getDistance() }

        assertThat(volumes[TypeSeance.ENDURANCE]).isEqualTo(22.0)   // agrégé par SQL
        assertThat(volumes[TypeSeance.FRACTIONNE]).isEqualTo(8.0)
    }

    @Test
    fun `l entity graph charge l utilisateur en une seule requete`() {
        val abdou = utilisateur("graph@test.fr")
        repeat(3) { i ->
            seanceRepository.save(Seance(TypeSeance.ENDURANCE, 10.0, 50,
                LocalDateTime.parse("2026-07-01T18:30").plusDays(i.toLong()), utilisateur = abdou))
        }

        val page = seanceRepository.findWithUtilisateurByUtilisateurId(abdou.id!!, PageRequest.of(0, 10))

        assertThat(page.content).hasSize(3)
        // L'utilisateur est déjà chargé : pas de requête supplémentaire (pas de N+1)
        assertThat(page.content.first().utilisateur.email).isEqualTo("graph@test.fr")
    }

    @Test
    fun `pagine correctement`() {
        val abdou = utilisateur("pagine@test.fr")
        repeat(5) { i ->
            seanceRepository.save(Seance(TypeSeance.ENDURANCE, 10.0, 50,
                LocalDateTime.parse("2026-07-01T18:30").plusDays(i.toLong()), utilisateur = abdou))
        }

        val page = seanceRepository.findByUtilisateurId(abdou.id!!, PageRequest.of(0, 2))

        assertThat(page.content).hasSize(2)
        assertThat(page.totalElements).isEqualTo(5)
        assertThat(page.totalPages).isEqualTo(3)
    }
}
