package com.kayedaw.seance

import com.kayedaw.admin.AdminController
import com.kayedaw.admin.AdminService
import com.kayedaw.common.CompteurRequetes
import com.kayedaw.config.SecurityConfig
import com.kayedaw.security.JwtAuthFilter
import com.kayedaw.security.JwtService
import com.kayedaw.security.UtilisateurDetailsService
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Vérifie l'AUTORISATION par rôle (@PreAuthorize + règles d'URL).
 * Un USER authentifié doit être refusé (403), un ADMIN accepté (200).
 */
@WebMvcTest(AdminController::class)
@Import(SecurityConfig::class, JwtAuthFilter::class)
class AdminSecuriteTest(@Autowired private val mockMvc: MockMvc) {

    @MockkBean private lateinit var service: AdminService
    @MockkBean private lateinit var compteur: CompteurRequetes
    @MockkBean private lateinit var jwtService: JwtService
    @MockkBean private lateinit var utilisateurDetailsService: UtilisateurDetailsService

    @Test
    fun `refuse un anonyme`() {
        mockMvc.get("/api/admin/utilisateurs").andExpect { status { isUnauthorized() } }
    }

    @Test
    @WithMockUser(username = "abdou@test.fr", roles = ["USER"])
    fun `refuse un simple utilisateur`() {
        mockMvc.get("/api/admin/utilisateurs").andExpect { status { isForbidden() } }
    }

    @Test
    @WithMockUser(username = "admin@test.fr", roles = ["ADMIN"])
    fun `autorise un administrateur`() {
        every { service.lister(any(), any()) } returns PageImpl(emptyList())

        mockMvc.get("/api/admin/utilisateurs").andExpect { status { isOk() } }
    }
}
