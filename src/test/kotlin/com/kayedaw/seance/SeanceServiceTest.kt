package com.kayedaw.seance

import com.kayedaw.common.AccesRefuseException
import com.kayedaw.common.ResultatCreationSeance
import com.kayedaw.common.SeanceIntrouvableException
import com.kayedaw.config.AppProperties
import com.kayedaw.meteo.EnrichissementMeteoService
import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import com.kayedaw.meteo.ConditionsSeance
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 4.2 — Pourquoi MockK plutôt que Mockito en Kotlin ?            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Mockito gère mal les classes `final` (le défaut en Kotlin), les objets et
 * les fonctions d'extension. MockK est conçu pour Kotlin : syntaxe DSL
 * (`every { } returns`), support des coroutines (`coEvery`), et des relaxed mocks.
 *
 * Ces tests ne démarrent PAS Spring : ils s'exécutent en millisecondes.
 */
class SeanceServiceTest {

    private val seanceRepository = mockk<SeanceRepository>()
    private val utilisateurRepository = mockk<UtilisateurRepository>()

    // Configuration typée injectée directement : pas besoin de contexte Spring
    private val proprietes = AppProperties(
        jwt = AppProperties.Jwt("c2VjcmV0", Duration.ofHours(1)),
        entrainement = AppProperties.Entrainement(plafondHebdoKm = 80.0, distanceMaxSeanceKm = 200.0),
        meteo = AppProperties.Meteo("http://x/geo", "http://x/arch", "http://x/air",
            "http://x/prev", 2, Duration.ofMillis(10)),
        meteoFrance = AppProperties.MeteoFrance(),
        httpClient = AppProperties.HttpClient(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 10)
    )

    // L'enrichissement météo est une dépendance externe : on la neutralise ici.
    // Son comportement est testé séparément dans EnrichissementMeteoServiceTest.
    private val enrichissement = mockk<EnrichissementMeteoService>()

    private val service = SeanceService(
        seanceRepository, utilisateurRepository, proprietes, enrichissement)

    private val abdou = Utilisateur(email = "abdou@test.fr", motDePasse = "hash", nom = "Abdou", villeParDefaut = "Lille", role = Role.USER, id = 1L)
    private val autre = Utilisateur(email = "autre@test.fr", motDePasse = "hash", nom = "Autre", villeParDefaut = "Lille", role = Role.USER, id = 2L)
    private val admin = Utilisateur(email = "admin@test.fr", motDePasse = "hash", nom = "Admin", villeParDefaut = "Lille", role = Role.ADMIN, id = 3L)

    private val hier: LocalDateTime = LocalDateTime.now().minusDays(1)


    /** Simule ce que fait le save() de JPA : rendre l'entité avec un id attribué. */
    private fun avecId(s: Seance, id: Long) = Seance(
        type = s.type, distanceKm = s.distanceKm, dureeMinutes = s.dureeMinutes,
        dateHeure = s.dateHeure, commentaire = s.commentaire, ville = s.ville,
        temperatureMaxC = s.temperatureMaxC, temperatureMinC = s.temperatureMinC,
        precipitationMm = s.precipitationMm, ventKmH = s.ventKmH, pm25 = s.pm25,
        alertesMeteo = s.alertesMeteo,
        utilisateur = s.utilisateur, id = id)

    private fun seanceDe(proprietaire: Utilisateur, id: Long = 10L, distance: Double = 10.0) =
        Seance(TypeSeance.ENDURANCE, distance, 50, hier, utilisateur = proprietaire, id = id)

    // ───────────────────────── CREATE ─────────────────────────
    @Test
    fun `cree une seance et retourne le cas Creee`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(1L, any(), any()) } returns emptyList()
        val capture = slot<Seance>()
        // On réutilise `avecId` : le 6e paramètre positionnel de Seance est
        // `ville`, pas `utilisateur` — d'où l'intérêt des arguments NOMMÉS sur
        // un constructeur qui enchaîne autant de champs optionnels.
        every { seanceRepository.save(capture(capture)) } answers { avecId(capture.captured, 99L) }

        val resultat = service.creer(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier), "abdou@test.fr")

        // Smart cast après `is` : accès direct à seanceId, sans transtypage
        assertThat(resultat).isInstanceOf(ResultatCreationSeance.Creee::class.java)
        assertThat((resultat as ResultatCreationSeance.Creee).seanceId).isEqualTo(99L)
        verify(exactly = 1) { seanceRepository.save(any()) }
    }

    @Test
    fun `refuse une seance depassant le plafond hebdomadaire`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(1L, any(), any()) } returns
            listOf(seanceDe(abdou, distance = 75.0))

        val resultat = service.creer(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier), "abdou@test.fr")

        assertThat(resultat).isInstanceOf(ResultatCreationSeance.PlafondHebdoDepasse::class.java)
        val refus = resultat as ResultatCreationSeance.PlafondHebdoDepasse
        assertThat(refus.volumeCalculeKm).isEqualTo(85.0)
        assertThat(refus.depassementKm).isEqualTo(5.0)

        // Vérification essentielle : RIEN n'est persisté quand la règle bloque
        verify(exactly = 0) { seanceRepository.save(any()) }
    }

    @Test
    fun `refuse une seance planifiee au-dela de l horizon`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou

        val resultat = service.creer(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.now().plusDays(30)),
            "abdou@test.fr")

        assertThat(resultat).isEqualTo(ResultatCreationSeance.DateTropLointaine(14))
        verify(exactly = 0) { seanceRepository.save(any()) }
    }

    // ───────────────────────── READ / autorisation ─────────────────────────
    @Test
    fun `leve une exception quand la seance n existe pas`() {
        every { seanceRepository.findById(42L) } returns Optional.empty()

        assertThatThrownBy { service.parId(42L, "abdou@test.fr") }
            .isInstanceOf(SeanceIntrouvableException::class.java)
            .hasMessageContaining("42")
    }

    @Test
    fun `refuse l acces a la seance d un autre utilisateur`() {
        every { seanceRepository.findById(10L) } returns Optional.of(seanceDe(autre))
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou

        assertThatThrownBy { service.parId(10L, "abdou@test.fr") }
            .isInstanceOf(AccesRefuseException::class.java)
    }

    @Test
    fun `un admin peut consulter la seance d un autre utilisateur`() {
        every { seanceRepository.findById(10L) } returns Optional.of(seanceDe(autre))
        every { utilisateurRepository.findByEmail("admin@test.fr") } returns admin

        assertThat(service.parId(10L, "admin@test.fr").id).isEqualTo(10L)
    }

    // ───────────────────────── UPDATE / DELETE ─────────────────────────
    @Test
    fun `modifie une seance existante et recalcule l allure`() {
        val seance = seanceDe(abdou)
        every { seanceRepository.findById(10L) } returns Optional.of(seance)
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou

        val resultat = service.modifier(
            10L,
            ModifierSeanceRequest(TypeSeance.FRACTIONNE, 8.0, 32, hier, "  sur piste  "),
            "abdou@test.fr")

        assertThat(resultat.type).isEqualTo(TypeSeance.FRACTIONNE)
        assertThat(resultat.allureMinParKm).isEqualTo(4.0)
        assertThat(resultat.intensite).isEqualTo("élevée")
        assertThat(resultat.commentaire).isEqualTo("sur piste")   // trim() appliqué
    }

    @Test
    fun `supprime une seance dont on est proprietaire`() {
        val seance = seanceDe(abdou)
        every { seanceRepository.findById(10L) } returns Optional.of(seance)
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.delete(seance) } returns Unit

        service.supprimer(10L, "abdou@test.fr")

        verify(exactly = 1) { seanceRepository.delete(seance) }
    }

    // ───────────── Intégration de l'appel sortant (WebClient + coroutines) ─────────────
    @Test
    fun `enrichit la seance avec les conditions meteo quand une ville est fournie`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(1L, any(), any()) } returns emptyList()
        coEvery { enrichissement.conditions("Lille", hier) } returns ConditionsSeance(
            ville = "Lille", temperatureMaxC = 31.0, ventMaxKmH = 42.0, pm25 = 10.0)

        val capture = slot<Seance>()
        every { seanceRepository.save(capture(capture)) } answers { avecId(capture.captured, 50L) }

        service.creer(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier, ville = "Lille"),
            "abdou@test.fr")

        val enregistree = capture.captured
        assertThat(enregistree.ville).isEqualTo("Lille")
        assertThat(enregistree.temperatureMaxC).isEqualTo(31.0)
        assertThat(enregistree.ventKmH).isEqualTo(42.0)
        assertThat(enregistree.alertesMeteo).contains("forte chaleur").contains("vent fort")
    }

    @Test
    fun `cree la seance meme si le service meteo est indisponible`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(1L, any(), any()) } returns emptyList()
        // Le service externe est en panne : repli sur null
        coEvery { enrichissement.conditions(any(), any()) } returns null

        val capture = slot<Seance>()
        every { seanceRepository.save(capture(capture)) } answers { avecId(capture.captured, 51L) }

        val resultat = service.creer(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier, ville = "Lille"),
            "abdou@test.fr")

        // Le cas d'usage principal aboutit malgré la panne externe
        assertThat(resultat).isInstanceOf(ResultatCreationSeance.Creee::class.java)
        assertThat(capture.captured.ville).isNull()
        assertThat(capture.captured.temperatureMaxC).isNull()
    }

    @Test
    fun `n appelle pas le service meteo quand aucune ville n est fournie`() {
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
        every { seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(1L, any(), any()) } returns emptyList()
        val capture = slot<Seance>()
        every { seanceRepository.save(capture(capture)) } answers { avecId(capture.captured, 52L) }

        service.creer(CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier), "abdou@test.fr")

        // Pas d'appel réseau inutile
        io.mockk.coVerify(exactly = 0) { enrichissement.conditions(any(), any()) }
    }
}
