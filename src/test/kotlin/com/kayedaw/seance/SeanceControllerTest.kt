package com.kayedaw.seance

import com.fasterxml.jackson.databind.ObjectMapper
import com.kayedaw.common.AccesRefuseException
import com.kayedaw.common.CompteurRequetes
import com.kayedaw.common.ResultatCreationSeance
import com.kayedaw.common.SeanceIntrouvableException
import com.kayedaw.config.SecurityConfig
import com.kayedaw.security.JwtAuthFilter
import com.kayedaw.security.JwtService
import com.kayedaw.security.UtilisateurDetailsService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 4.1/4.3 — Tests de tranche et test du code sécurisé            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * @WebMvcTest ne démarre QUE la couche web : routing, sérialisation JSON,
 * validation, @RestControllerAdvice. Ni base, ni logique métier réelle.
 *
 * @WithMockUser simule un utilisateur authentifié. On teste aussi les cas
 * NÉGATIFS (401, 403) : c'est là que se cachent les failles.
 */
@WebMvcTest(SeanceController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class)
class SeanceControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper
) {

    @MockkBean private lateinit var service: SeanceService
    @MockkBean private lateinit var statistiquesService: StatistiquesService
    @MockkBean private lateinit var compteur: CompteurRequetes
    @MockkBean private lateinit var jwtService: JwtService
    @MockkBean private lateinit var utilisateurDetailsService: UtilisateurDetailsService

    private val reponse = SeanceResponse(
        1L, TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.parse("2026-07-22T18:30"),
        false, null, 5.0, 12.0, "modérée")

    @Test
    fun `refuse l acces sans authentification`() {
        mockMvc.get("/api/seances").andExpect { status { isUnauthorized() } }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `cree une seance et retourne 201`() {
        justRun { compteur.incrementer(any()) }
        every { service.creer(any(), "abdou@test.fr") } returns ResultatCreationSeance.Creee(1L)
        every { service.parId(1L, "abdou@test.fr") } returns reponse

        mockMvc.post("/api/seances") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.parse("2026-07-22T18:30")))
            with(csrf())
        }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/seances/1") }
            jsonPath("$.allureMinParKm") { value(5.0) }
            jsonPath("$.intensite") { value("modérée") }
        }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `retourne 422 quand le plafond hebdomadaire est depasse`() {
        justRun { compteur.incrementer(any()) }
        every { service.creer(any(), "abdou@test.fr") } returns
            ResultatCreationSeance.PlafondHebdoDepasse(volumeCalculeKm = 92.5, plafondKm = 80.0)

        mockMvc.post("/api/seances") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                CreerSeanceRequest(TypeSeance.SORTIE_LONGUE, 30.0, 180, LocalDateTime.parse("2026-07-22T18:30")))
            with(csrf())
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.motif") { value("PLAFOND_HEBDOMADAIRE") }
            jsonPath("$.volumeCalculeKm") { value(92.5) }
        }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `retourne 422 quand la date depasse l horizon de planification`() {
        justRun { compteur.incrementer(any()) }
        every { service.creer(any(), "abdou@test.fr") } returns ResultatCreationSeance.DateTropLointaine(14)

        mockMvc.post("/api/seances") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.now().plusDays(30)))
            with(csrf())
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.motif") { value("DATE_TROP_LOINTAINE") }
        }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `retourne 400 si la distance est negative`() {
        mockMvc.post("/api/seances") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf(
                "type" to "ENDURANCE", "distanceKm" to -5.0,
                "dureeMinutes" to 50, "date" to "2026-07-22"))
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.statut") { value(400) }
        }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `retourne 404 quand la seance est introuvable`() {
        every { service.parId(42L, "abdou@test.fr") } throws SeanceIntrouvableException(42L)

        mockMvc.get("/api/seances/42").andExpect {
            status { isNotFound() }
            jsonPath("$.statut") { value(404) }
        }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `retourne 403 quand la seance appartient a un autre`() {
        every { service.parId(10L, "abdou@test.fr") } throws AccesRefuseException()

        mockMvc.get("/api/seances/10").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr")
    fun `supprime une seance et retourne 204`() {
        justRun { service.supprimer(1L, "abdou@test.fr") }

        mockMvc.delete("/api/seances/1") { with(csrf()) }.andExpect { status { isNoContent() } }
    }
}
