package com.kayedaw.preference

import com.kayedaw.config.AppProperties
import com.kayedaw.seance.TypeSeance
import com.kayedaw.user.Langue
import com.kayedaw.user.Role
import com.kayedaw.user.Theme
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ PRÉFÉRENCES — ce que ces tests protègent réellement                     │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * L'écran de profil affiche un tableau des CINQ types. La garantie qui compte
 * n'est donc pas « on relit ce qu'on a écrit » mais « la réponse est toujours
 * complète », y compris pour un compte neuf qui n'a jamais rien réglé.
 */
class PreferenceServiceTest {

    private val utilisateurRepository = mockk<UtilisateurRepository>()
    private val preferenceRepository = mockk<PreferenceSeanceRepository>(relaxed = true)

    // Configuration typée injectée directement : pas besoin de contexte Spring.
    // Seul `entrainement` est lu ici, le reste ne sert qu'à satisfaire le type.
    private val proprietes = AppProperties(
        jwt = AppProperties.Jwt("c2VjcmV0", Duration.ofHours(1)),
        entrainement = AppProperties.Entrainement(plafondHebdoKm = 80.0, distanceMaxSeanceKm = 200.0),
        meteo = AppProperties.Meteo("http://x/geo", "http://x/arch", "http://x/air",
            "http://x/prev", 2, Duration.ofMillis(10)),
        meteoFrance = AppProperties.MeteoFrance(),
        httpClient = AppProperties.HttpClient(
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 10)
    )

    private val abdou = Utilisateur(
        email = "abdou@test.fr", motDePasse = "hash", nom = "Abdou",
        villeParDefaut = "Lille", role = Role.USER, id = 1L
    )

    private lateinit var service: PreferenceService

    @BeforeEach
    fun avantChaque() {
        service = PreferenceService(utilisateurRepository, preferenceRepository, proprietes)
        every { utilisateurRepository.findByEmail("abdou@test.fr") } returns abdou
    }

    @Test
    fun `un compte neuf recoit les cinq types avec les valeurs d usine`() {
        every { preferenceRepository.findByUtilisateurId(1L) } returns emptyList()

        val reponse = service.preferences("abdou@test.fr")

        // Aucune ligne en base, et pourtant un tableau complet : c'est ce qui
        // permet à l'écran de ne jamais afficher de case vide.
        assertThat(reponse.seances).hasSize(TypeSeance.entries.size)
        assertThat(reponse.seances.map { it.type })
            .containsExactlyElementsOf(TypeSeance.entries)
        assertThat(reponse.theme).isEqualTo(Theme.SYSTEME)
        assertThat(reponse.langue).isEqualTo(Langue.FR)

        // Toutes les valeurs d'usine sont identiques : 5 km en 60 min
        assertThat(reponse.seances).allSatisfy {
            assertThat(it.distanceKm).isEqualTo(5.0)
            assertThat(it.dureeMinutes).isEqualTo(60)
        }
    }

    @Test
    fun `une valeur enregistree prime sur celle d usine, les autres restent`() {
        every { preferenceRepository.findByUtilisateurId(1L) } returns listOf(
            PreferenceSeance(abdou, TypeSeance.FRACTIONNE, distanceKm = 12.5, dureeMinutes = 62)
        )

        val reponse = service.preferences("abdou@test.fr")

        assertThat(reponse.seances).hasSize(TypeSeance.entries.size)
        val fractionne = reponse.seances.first { it.type == TypeSeance.FRACTIONNE }
        assertThat(fractionne.distanceKm).isEqualTo(12.5)
        assertThat(fractionne.dureeMinutes).isEqualTo(62)
        // Les types non réglés ne sont PAS écrasés par le type personnalisé
        assertThat(reponse.seances.first { it.type == TypeSeance.RECUPERATION }.distanceKm)
            .isEqualTo(5.0)
    }

    @Test
    fun `l enregistrement remplace l existant et applique theme et langue`() {
        val lignes = slot<List<PreferenceSeance>>()
        every { preferenceRepository.saveAll(capture(lignes)) } answers { lignes.captured }

        val reponse = service.modifier("abdou@test.fr", ModifierPreferencesRequest(
            theme = Theme.SOMBRE,
            langue = Langue.EN,
            seances = listOf(DefautSeanceDto(TypeSeance.ENDURANCE, 11.0, 60))
        ))

        // L'ordre compte : la suppression doit précéder l'insertion, sinon la
        // contrainte d'unicité (utilisateur, type) saute.
        verify(ordering = io.mockk.Ordering.ORDERED) {
            preferenceRepository.deleteByUtilisateurId(1L)
            preferenceRepository.flush()
            preferenceRepository.saveAll(any<List<PreferenceSeance>>())
        }
        assertThat(abdou.theme).isEqualTo(Theme.SOMBRE)
        assertThat(abdou.langue).isEqualTo(Langue.EN)
        assertThat(reponse.seances.first { it.type == TypeSeance.ENDURANCE }.distanceKm)
            .isEqualTo(11.0)
        // Les types absents de la requête retombent sur l'usine, sans disparaître
        assertThat(reponse.seances).hasSize(TypeSeance.entries.size)
    }

    @Test
    fun `refuse une distance par defaut au-dela du maximum configure`() {
        assertThatThrownBy {
            service.modifier("abdou@test.fr", ModifierPreferencesRequest(
                theme = Theme.SYSTEME, langue = Langue.FR,
                seances = listOf(DefautSeanceDto(TypeSeance.SORTIE_LONGUE, 250.0, 600))
            ))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("200.0")

        // Rien ne doit avoir été touché : le refus intervient AVANT l'écriture
        verify(exactly = 0) { preferenceRepository.deleteByUtilisateurId(any()) }
        verify(exactly = 0) { preferenceRepository.saveAll(any<List<PreferenceSeance>>()) }
    }

    @Test
    fun `un doublon de type est ecarte avant insertion`() {
        val lignes = slot<List<PreferenceSeance>>()
        every { preferenceRepository.saveAll(capture(lignes)) } answers { lignes.captured }

        service.modifier("abdou@test.fr", ModifierPreferencesRequest(
            theme = Theme.SYSTEME, langue = Langue.FR,
            seances = listOf(
                DefautSeanceDto(TypeSeance.ENDURANCE, 10.0, 55),
                DefautSeanceDto(TypeSeance.ENDURANCE, 14.0, 80)
            )
        ))

        // Sans distinctBy, la contrainte d'unicité rejetterait l'insertion
        assertThat(lignes.captured).hasSize(1)
        assertThat(lignes.captured.first().distanceKm).isEqualTo(10.0)
    }
}
