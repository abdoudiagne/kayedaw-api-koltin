package com.kayedaw.admin

import com.kayedaw.common.CompteurRequetes
import com.kayedaw.seance.SeanceResponse
import com.kayedaw.user.Role
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

data class UtilisateurResume(val id: Long, val email: String, val nom: String, val role: Role)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 6.4 — Autorisation par rôle                                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Deux niveaux de protection, volontairement redondants (défense en profondeur) :
 *   1. SecurityConfig : requestMatchers sur /api/admin (et sous-routes)
 *      → .hasRole("ADMIN")
 *   2. @PreAuthorize sur la méthode (activé par @EnableMethodSecurity)
 *
 * @PreAuthorize permet aussi des règles fines : @PreAuthorize("#email == authentication.name")
 * pour n'autoriser que sur ses propres données.
 *
 * NB : en Kotlin les commentaires bloc S'IMBRIQUENT. Écrire un chemin Ant
 * comme /api/admin suivi d'une double étoile ouvre un commentaire imbriqué
 * et casse la compilation — d'où la reformulation ci-dessus.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(
    private val service: AdminService,
    private val compteur: CompteurRequetes
) {

    /**
     * Liste PAGINÉE et filtrable. L'ancienne version remontait tous les comptes
     * d'un seul coup : acceptable en démo, intenable dès quelques milliers.
     */
    @GetMapping("/utilisateurs")
    fun utilisateurs(
        @RequestParam(required = false, defaultValue = "") recherche: String,
        pageable: Pageable
    ): Page<UtilisateurResume> = service.lister(recherche, pageable)

    /**
     * Changement de rôle. Deux garde-fous, tous deux indispensables :
     *   - on ne se rétrograde pas soi-même (on se couperait l'accès en direct) ;
     *   - on ne retire pas le DERNIER administrateur, sinon plus personne ne
     *     peut administrer l'application et il faut repasser par la base.
     */
    @PatchMapping("/utilisateurs/{id}/role")
    fun changerRole(
        @PathVariable id: Long,
        @RequestBody request: ChangerRoleRequest,
        @AuthenticationPrincipal demandeur: UserDetails
    ): ResponseEntity<Any> {
        compteur.incrementer("PATCH /api/admin/utilisateurs/role")
        return service.changerRole(id, request.role, demandeur.username).versReponse()
    }

    /** Suppression d'un compte ET de ses séances. Irréversible. */
    @DeleteMapping("/utilisateurs/{id}")
    fun supprimer(
        @PathVariable id: Long,
        @AuthenticationPrincipal demandeur: UserDetails
    ): ResponseEntity<Any> {
        compteur.incrementer("DELETE /api/admin/utilisateurs")
        return service.supprimer(id, demandeur.username).versReponse()
    }

    /** Séances d'un utilisateur donné : le back l'autorisait déjà, rien ne l'exposait. */
    @GetMapping("/utilisateurs/{id}/seances")
    fun seancesDe(@PathVariable id: Long, pageable: Pageable): Page<SeanceResponse> =
        service.seancesDe(id, pageable)

    @GetMapping("/metriques")
    fun metriques(): Map<String, Any> = mapOf(
        "totalRequetes" to compteur.total(),
        "parRoute" to compteur.detail()
    )
}
