package com.kayedaw.user

import com.kayedaw.common.CompteurRequetes
import com.kayedaw.seance.SeanceRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION — Pourquoi l'identité vient-elle du SecurityContext ?          │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * `@AuthenticationPrincipal` fournit l'utilisateur issu du JETON VALIDÉ.
 * On ne prend JAMAIS l'identifiant dans le corps de la requête : sinon
 * n'importe qui modifierait le profil d'un autre en changeant un champ JSON.
 * C'est la même règle que pour les séances (voir SeanceService).
 */
@RestController
@RequestMapping("/api/profil")
class ProfilController(
    private val service: ProfilService,
    private val compteur: CompteurRequetes
) {

    @GetMapping
    fun profil(@AuthenticationPrincipal utilisateur: UserDetails): ProfilResponse {
        compteur.incrementer("GET /api/profil")
        return service.profil(utilisateur.username)
    }

    @PutMapping
    fun modifier(
        @Valid @RequestBody request: ModifierProfilRequest,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): ProfilResponse {
        compteur.incrementer("PUT /api/profil")
        return service.modifierProfil(utilisateur.username, request)
    }

    /**
     * Changement de mot de passe : 204 si tout va bien, 422 si l'ancien mot de
     * passe est faux. On ne renvoie pas 401 — l'utilisateur EST authentifié,
     * c'est sa saisie qui est invalide.
     */
    @PutMapping("/mot-de-passe")
    fun changerMotDePasse(
        @Valid @RequestBody request: ChangerMotDePasseRequest,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): ResponseEntity<Any> {
        compteur.incrementer("PUT /api/profil/mot-de-passe")

        return if (service.changerMotDePasse(utilisateur.username, request)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.unprocessableEntity()
                .body(mapOf("motif" to "MOT_DE_PASSE_ACTUEL_INVALIDE",
                            "detail" to "le mot de passe actuel est incorrect"))
        }
    }
}

data class ModifierProfilRequest(
    @field:NotBlank(message = "le nom est obligatoire")
    @field:Size(max = 100)
    val nom: String,

    @field:NotBlank(message = "la ville est obligatoire")
    @field:Size(max = 100)
    val villeParDefaut: String,

    /**
     * Optionnel dans la REQUÊTE, jamais nul en base : un client qui ne l'envoie
     * pas conserve la valeur existante. Cela évite qu'un ancien client, ignorant
     * du champ, écrase le pays par une chaîne vide à chaque enregistrement.
     */
    @field:Size(max = 100)
    val pays: String? = null
)

data class ChangerMotDePasseRequest(
    @field:NotBlank val motDePasseActuel: String,
    @field:NotBlank @field:Size(min = 5, message = "le mot de passe doit faire au moins 5 caractères")
    val nouveauMotDePasse: String
)

/** Le mot de passe n'apparaît évidemment nulle part en sortie. */
data class ProfilResponse(
    val email: String,
    val nom: String,
    val role: String,
    val villeParDefaut: String,
    val pays: String,
    val nombreSeances: Long,
    val distanceTotaleKm: Double,
    val premiereSeance: LocalDateTime? = null
)

@Service
@Transactional(readOnly = true)
class ProfilService(
    private val utilisateurRepository: UtilisateurRepository,
    private val seanceRepository: SeanceRepository,
    private val passwordEncoder: PasswordEncoder
) {

    fun profil(email: String): ProfilResponse {
        val utilisateur = parEmail(email)
        val seances = seanceRepository.findByUtilisateurIdOrderByDateHeureAsc(utilisateur.id!!)

        return ProfilResponse(
            email = utilisateur.email,
            nom = utilisateur.nom,
            role = utilisateur.role.name,
            villeParDefaut = utilisateur.villeParDefaut,
            pays = utilisateur.pays,
            nombreSeances = seances.count { !it.estPlanifiee() }.toLong(),
            distanceTotaleKm = seances.filterNot { it.estPlanifiee() }.sumOf { it.distanceKm },
            premiereSeance = seances.firstOrNull()?.dateHeure
        )
    }

    @Transactional
    fun modifierProfil(email: String, request: ModifierProfilRequest): ProfilResponse {
        // Dirty checking : l'entité est managée, pas besoin de save()
        parEmail(email).apply {
            nom = request.nom.trim()
            villeParDefaut = request.villeParDefaut.trim()
            // Absent de la requête = inchangé, voir ModifierProfilRequest
            request.pays?.trim()?.takeIf { it.isNotBlank() }?.let { pays = it }
        }
        return profil(email)
    }

    /**
     * ⚠️ SÉCURITÉ — on exige le mot de passe ACTUEL.
     *
     * Sans cette vérification, un jeton volé (XSS, poste laissé ouvert) permet
     * de changer le mot de passe et de verrouiller le propriétaire hors de son
     * propre compte. C'est la différence entre un vol de session temporaire et
     * une prise de contrôle définitive.
     */
    @Transactional
    fun changerMotDePasse(email: String, request: ChangerMotDePasseRequest): Boolean {
        val utilisateur = parEmail(email)

        if (!passwordEncoder.matches(request.motDePasseActuel, utilisateur.motDePasse)) {
            return false
        }

        utilisateur.motDePasse = passwordEncoder.encode(request.nouveauMotDePasse)
        return true
    }

    private fun parEmail(email: String): Utilisateur =
        utilisateurRepository.findByEmail(email)
            ?: throw IllegalStateException("utilisateur authentifié introuvable : $email")
}
