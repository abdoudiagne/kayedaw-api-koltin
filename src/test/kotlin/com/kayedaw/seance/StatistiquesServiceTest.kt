package com.kayedaw.seance

import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ Tester des coroutines : runTest                                         │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * `runTest` (kotlinx-coroutines-test) fournit un scope de test et permet
 * d'appeler des fonctions `suspend` sans bloquer. Il avance aussi le temps
 * virtuel : un `delay(10_000)` s'exécute instantanément.
 */
class StatistiquesServiceTest {

    private val seanceService = mockk<SeanceService>()
    private val service = StatistiquesService(seanceService)

    private val abdou = Utilisateur(email = "abdou@test.fr", motDePasse = "hash", nom = "Abdou", villeParDefaut = "Lille", role = Role.USER, id = 1L)
    private val debut: LocalDate = LocalDate.parse("2026-07-01")
    private val fin: LocalDate = LocalDate.parse("2026-07-31")

    private fun seance(distance: Double, duree: Int, type: TypeSeance = TypeSeance.ENDURANCE) =
        Seance(type, distance, duree, LocalDateTime.parse("2026-07-15T18:30"), utilisateur = abdou, id = 1L)

    @Test
    fun `calcule les statistiques agregees sur la periode`() {
        // La comparaison charge aussi la période PRÉCÉDENTE : stub par défaut,
        // puis stub spécifique pour la période observée (le plus précis gagne).
        every { seanceService.seancesDeLaPeriode(any(), any(), any()) } returns emptyList()
        every { seanceService.seancesDeLaPeriode("abdou@test.fr", debut, fin) } returns listOf(
            seance(10.0, 50),
            seance(10.0, 40, TypeSeance.FRACTIONNE)
        )
        every { seanceService.volumeParType("abdou@test.fr", debut, fin) } returns mapOf(
            TypeSeance.ENDURANCE to 10.0,
            TypeSeance.FRACTIONNE to 10.0
        )

        val stats = service.calculer("abdou@test.fr", debut, fin)

        assertThat(stats.nombreSeances).isEqualTo(2)
        assertThat(stats.distanceTotaleKm).isEqualTo(20.0)
        assertThat(stats.dureeTotaleMinutes).isEqualTo(90)
        assertThat(stats.allureMoyenneMinParKm).isEqualTo(4.5)     // 90 min / 20 km
        assertThat(stats.volumeParType).containsEntry(TypeSeance.FRACTIONNE, 10.0)
    }

    @Test
    fun `retourne des statistiques vides sans seance`() {
        every { seanceService.seancesDeLaPeriode(any(), any(), any()) } returns emptyList()
        every { seanceService.volumeParType(any(), any(), any()) } returns emptyMap()

        val stats = service.calculer("abdou@test.fr", debut, fin)

        assertThat(stats.nombreSeances).isZero()
        assertThat(stats.distanceTotaleKm).isZero()
    }

    @Test
    fun `la fonction suspend calcule la distance totale`() = runTest {
        // La comparaison charge aussi la période PRÉCÉDENTE : stub par défaut,
        // puis stub spécifique pour la période observée (le plus précis gagne).
        every { seanceService.seancesDeLaPeriode(any(), any(), any()) } returns emptyList()
        every { seanceService.seancesDeLaPeriode("abdou@test.fr", debut, fin) } returns listOf(
            seance(10.5, 55), seance(9.5, 48)
        )

        // Appel direct d'une fonction `suspend`, possible grâce à runTest
        val total = service.distanceTotale("abdou@test.fr", debut, fin)

        assertThat(total).isEqualTo(20.0)
    }
}
