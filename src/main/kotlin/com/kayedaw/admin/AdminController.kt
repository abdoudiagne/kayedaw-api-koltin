package com.kayedaw.admin

import com.kayedaw.common.CompteurRequetes
import com.kayedaw.seance.SeanceResponse
import com.kayedaw.user.Role
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.time.LocalDate
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*

/**
 * Projection d'un compte pour l'écran d'administration.
 *
 * `villeParDefaut` y figure parce que l'écran ÉDITE ce champ : sans lui, le
 * dialogue s'ouvrait sur une ville vide et l'administrateur devait la ressaisir
 * de mémoire — ou l'écrasait sans le vouloir. Un aller-retour de plus vers
 * l'API pour un seul champ ne se justifiait pas.
 */
data class UtilisateurResume(
    val id: Long,
    val email: String,
    val nom: String,
    val role: Role,
    val actif: Boolean,
    val villeParDefaut: String,
    val pays: String)

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

    /** Édition du nom et de la ville. L'email, identifiant de connexion, n'y est pas. */
    @PatchMapping("/utilisateurs/{id}")
    fun modifier(
        @PathVariable id: Long,
        @RequestBody request: ModifierUtilisateurRequest
    ): ResponseEntity<Any> {
        compteur.incrementer("PATCH /api/admin/utilisateurs")
        return service.modifier(id, request).versReponse()
    }

    /** Réinitialisation : l'ancien mot de passe n'est pas demandé, voir le service. */
    @PutMapping("/utilisateurs/{id}/mot-de-passe")
    fun reinitialiserMotDePasse(
        @PathVariable id: Long,
        @RequestBody request: ReinitialiserMotDePasseRequest
    ): ResponseEntity<Any> {
        compteur.incrementer("PUT /api/admin/utilisateurs/mot-de-passe")
        return service.reinitialiserMotDePasse(id, request.nouveauMotDePasse).versReponse()
    }

    /** Blocage / déblocage d'un compte. Réversible, contrairement à la suppression. */
    @PatchMapping("/utilisateurs/{id}/blocage")
    fun bloquer(
        @PathVariable id: Long,
        @RequestBody request: BloquerRequest,
        @AuthenticationPrincipal demandeur: UserDetails
    ): ResponseEntity<Any> {
        compteur.incrementer("PATCH /api/admin/utilisateurs/blocage")
        return service.bloquer(id, request.actif, demandeur.username).versReponse()
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

    /**
     * Suppression EN MASSE. Renvoie toujours 200 avec un compte rendu, jamais
     * 204 : le résultat est partiel par nature — certains comptes du lot
     * peuvent être refusés (soi-même, dernier administrateur) pendant que les
     * autres partent. Un code d'état unique ne saurait pas dire cela.
     */
    @DeleteMapping("/utilisateurs")
    fun supprimerEnMasse(
        @RequestBody request: SupprimerEnMasseRequest,
        @AuthenticationPrincipal demandeur: UserDetails
    ): RapportSuppression {
        compteur.incrementer("DELETE /api/admin/utilisateurs (masse)")
        return service.supprimerEnMasse(request.ids, demandeur.username)
    }

    /** Séances d'un utilisateur donné : le back l'autorisait déjà, rien ne l'exposait. */
    @GetMapping("/utilisateurs/{id}/seances")
    fun seancesDe(@PathVariable id: Long, pageable: Pageable): Page<SeanceResponse> =
        service.seancesDe(id, pageable)

    /**
     * Export PDF de l'annuaire. Protégé comme le reste de l'espace : règle
     * d'URL dans SecurityConfig ET `@PreAuthorize` sur la méthode.
     */
    @GetMapping("/utilisateurs/export.pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    @PreAuthorize("hasRole('ADMIN')")
    fun exporterUtilisateursPdf(): ResponseEntity<ByteArray> {
        val jour = LocalDate.now()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"utilisateurs-$jour.pdf\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(service.exporterEnPdf())
    }

    @GetMapping("/metriques")
    fun metriques(): Map<String, Any> = mapOf(
        "totalRequetes" to compteur.total(),
        "parRoute" to compteur.detail()
    )
}
