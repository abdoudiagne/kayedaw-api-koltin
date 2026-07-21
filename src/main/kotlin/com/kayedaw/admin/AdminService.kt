package com.kayedaw.admin

import com.kayedaw.seance.SeanceRepository
import com.kayedaw.seance.SeanceResponse
import com.kayedaw.user.Role
import com.kayedaw.user.UtilisateurRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ChangerRoleRequest(val role: Role)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ Résultat d'une opération d'administration                               │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Même choix que pour ResultatCreationSeance : les refus sont des cas métier
 * NORMAUX, pas des anomalies. Une sealed interface les rend visibles dans la
 * signature et force le contrôleur à tous les traiter.
 */
sealed interface ResultatAdmin {
    data object Applique : ResultatAdmin
    data object Introuvable : ResultatAdmin
    data class Refuse(val motif: String, val detail: String) : ResultatAdmin
}

/** Traduction unique vers HTTP : le contrôleur ne réinvente rien. */
fun ResultatAdmin.versReponse(): ResponseEntity<Any> = when (this) {
    is ResultatAdmin.Applique -> ResponseEntity.noContent().build()
    is ResultatAdmin.Introuvable -> ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    is ResultatAdmin.Refuse -> ResponseEntity.unprocessableEntity()
        .body(mapOf("motif" to motif, "detail" to detail))
}

@Service
@Transactional(readOnly = true)
class AdminService(
    private val utilisateurRepository: UtilisateurRepository,
    private val seanceRepository: SeanceRepository
) {

    fun lister(recherche: String, pageable: Pageable): Page<UtilisateurResume> {
        val page = if (recherche.isBlank()) {
            utilisateurRepository.findAll(pageable)
        } else {
            // Le même terme est cherché dans les deux colonnes
            utilisateurRepository.findByNomContainingIgnoreCaseOrEmailContainingIgnoreCase(
                recherche.trim(), recherche.trim(), pageable)
        }
        return page.map { UtilisateurResume(it.id!!, it.email, it.nom, it.role) }
    }

    @Transactional
    fun changerRole(id: Long, nouveauRole: Role, emailDemandeur: String): ResultatAdmin {
        val cible = utilisateurRepository.findById(id).orElse(null)
            ?: return ResultatAdmin.Introuvable

        if (cible.email == emailDemandeur) {
            return ResultatAdmin.Refuse(
                "AUTO_MODIFICATION",
                "vous ne pouvez pas modifier votre propre rôle")
        }

        // Dernier administrateur : le rétrograder rendrait l'application
        // inadministrable sans intervention directe en base.
        if (cible.role == Role.ADMIN && nouveauRole != Role.ADMIN &&
            utilisateurRepository.countByRole(Role.ADMIN) <= 1) {
            return ResultatAdmin.Refuse(
                "DERNIER_ADMINISTRATEUR",
                "impossible de rétrograder le dernier administrateur")
        }

        cible.role = nouveauRole          // dirty checking
        return ResultatAdmin.Applique
    }

    @Transactional
    fun supprimer(id: Long, emailDemandeur: String): ResultatAdmin {
        val cible = utilisateurRepository.findById(id).orElse(null)
            ?: return ResultatAdmin.Introuvable

        if (cible.email == emailDemandeur) {
            return ResultatAdmin.Refuse(
                "AUTO_SUPPRESSION",
                "vous ne pouvez pas supprimer votre propre compte")
        }

        if (cible.role == Role.ADMIN && utilisateurRepository.countByRole(Role.ADMIN) <= 1) {
            return ResultatAdmin.Refuse(
                "DERNIER_ADMINISTRATEUR",
                "impossible de supprimer le dernier administrateur")
        }

        // Les séances d'abord : la contrainte de clé étrangère l'exige
        seanceRepository.deleteByUtilisateurId(id)
        utilisateurRepository.delete(cible)
        return ResultatAdmin.Applique
    }

    fun seancesDe(id: Long, pageable: Pageable): Page<SeanceResponse> =
        seanceRepository.findByUtilisateurId(id, pageable).map(SeanceResponse::de)
}
