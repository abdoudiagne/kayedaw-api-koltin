package com.kayedaw.meteo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
// `currentTime` est une propriété d'EXTENSION sur TestScope : contrairement à
// un membre, elle doit être importée nommément pour être visible ici.
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ TESTER DES COROUTINES — coEvery / coVerify                              │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * MockK fournit `coEvery` et `coVerify` pour les fonctions `suspend`.
 * Un `every` classique ne compilerait pas sur une fonction suspendue.
 */
class EnrichissementMeteoServiceTest {

    private val client = mockk<MeteoClient>()
    // Météo-France neutralisé ici : cette classe teste l'orchestration Open-Meteo.
    // L'intégration DPClim a ses propres tests (AnalyseurCsvDpclimTest).
    private val meteoFrance = mockk<MeteoFranceClient>()
    private val service = EnrichissementMeteoService(client, meteoFrance)

    init {
        // Par défaut Météo-France ne répond rien : on isole le comportement Open-Meteo
        coEvery { meteoFrance.observations(any(), any()) } returns null
    }

    private val date: LocalDate = LocalDate.parse("2026-07-19")
    private val lille = Coordonnees("Lille", 50.63, 3.06)

    private fun archive(tempMax: Double?, tempMin: Double? = null, vent: Double? = null, pluie: Double? = null) =
        ArchiveMeteoReponse(DonneesQuotidiennes(
            time = listOf(date.toString()),
            temperature_2m_max = listOf(tempMax),
            temperature_2m_min = listOf(tempMin),
            precipitation_sum = listOf(pluie),
            wind_speed_10m_max = listOf(vent)
        ))

    /**
     * L'API renvoie une série HORAIRE : on simule quelques heures, dont le pic
     * doit être celui retenu par `pm25Max()`.
     */
    private fun air(pm25: Double?) =
        QualiteAirReponse(DonneesAir(
            time = List(3) { date.toString() },
            pm2_5 = listOf(pm25?.minus(5), pm25, pm25?.minus(2))
        ))

    @Test
    fun `agrege le geocodage, la meteo et la qualite de l air`() = runTest {
        coEvery { client.coordonnees("Lille") } returns lille
        coEvery { client.meteoDuJour(lille, date) } returns archive(27.4, 15.1, 18.5, 0.0)
        coEvery { client.qualiteAir(lille, date) } returns air(12.0)

        val conditions = service.conditions("Lille", date.atTime(18, 30))

        assertThat(conditions).isNotNull
        assertThat(conditions!!.ville).isEqualTo("Lille")
        assertThat(conditions.temperatureMaxC).isEqualTo(27.4)
        assertThat(conditions.ventMaxKmH).isEqualTo(18.5)
        assertThat(conditions.pm25).isEqualTo(12.0)
        assertThat(conditions.alertes()).isEmpty()
    }

    @Test
    fun `signale les conditions difficiles`() = runTest {
        coEvery { client.coordonnees("Lille") } returns lille
        coEvery { client.meteoDuJour(lille, date) } returns archive(32.0, -1.0, 45.0, 15.0)
        coEvery { client.qualiteAir(lille, date) } returns air(30.0)

        val alertes = service.conditions("Lille", date.atTime(18, 30))!!.alertes()

        assertThat(alertes).containsExactlyInAnyOrder(
            "forte chaleur", "gel", "vent fort", "fortes précipitations", "qualité de l'air dégradée")
    }

    @Test
    fun `retourne null quand la ville est inconnue et n appelle pas la meteo`() = runTest {
        coEvery { client.coordonnees("Zzzz") } returns null

        assertThat(service.conditions("Zzzz", date.atTime(18, 30))).isNull()

        // Aucun appel inutile : on n'interroge pas la météo sans coordonnées
        coVerify(exactly = 0) { client.meteoDuJour(any(), any()) }
        coVerify(exactly = 0) { client.qualiteAir(any(), any()) }
    }

    @Test
    fun `reste exploitable quand la qualite de l air est indisponible`() = runTest {
        coEvery { client.coordonnees("Lille") } returns lille
        coEvery { client.meteoDuJour(lille, date) } returns archive(22.0, 12.0, 15.0, 0.0)
        coEvery { client.qualiteAir(lille, date) } returns null      // service en panne

        val conditions = service.conditions("Lille", date.atTime(18, 30))

        // Dégradation gracieuse : on garde ce qu'on a
        assertThat(conditions).isNotNull
        assertThat(conditions!!.temperatureMaxC).isEqualTo(22.0)
        assertThat(conditions.pm25).isNull()
    }

    @Test
    fun `les deux appels sont lances en parallele`() = runTest {
        coEvery { client.coordonnees("Lille") } returns lille
        // Chaque appel dure 200 ms de temps virtuel
        coEvery { client.meteoDuJour(lille, date) } coAnswers { delay(200); archive(20.0) }
        coEvery { client.qualiteAir(lille, date) } coAnswers { delay(200); air(10.0) }

        val debut = currentTime
        service.conditions("Lille", date.atTime(18, 30))
        val ecoule = currentTime - debut

        // Séquentiel donnerait 400 ms ; en parallèle on reste proche de 200 ms.
        // `currentTime` (runTest) mesure le temps VIRTUEL : test instantané et déterministe.
        assertThat(ecoule).isLessThan(400)
    }

    @Test
    fun `abandonne si le budget global est depasse`() = runTest {
        coEvery { client.coordonnees("Lille") } coAnswers { delay(10_000); lille }

        // withTimeoutOrNull borne l'enrichissement : on renonce plutôt que d'attendre
        assertThat(service.conditions("Lille", date.atTime(18, 30))).isNull()
    }
}
