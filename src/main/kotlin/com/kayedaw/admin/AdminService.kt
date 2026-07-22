package com.kayedaw.admin

import com.kayedaw.preference.PreferenceSeanceRepository
import com.kayedaw.seance.SeanceRepository
import com.kayedaw.seance.SeanceResponse
import com.kayedaw.common.DocumentPdf
import java.time.LocalDateTime
import com.kayedaw.user.Role
import com.kayedaw.user.UtilisateurRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ChangerRoleRequest(val role: Role)

data class SupprimerEnMasseRequest(val ids: List<Long>)

data class ModifierUtilisateurRequest(
    val nom: String, val villeParDefaut: String, val pays: String? = null)

data class ReinitialiserMotDePasseRequest(val nouveauMotDePasse: String)

data class BloquerRequest(val actif: Boolean)

data class RefusSuppression(val id: Long, val motif: String, val detail: String)

/**
 * Compte rendu d'une suppression en masse : ce qui est parti, et pourquoi le
 * reste ne l'est pas. Un simple 204 obligerait le client à relire la liste pour
 * deviner ce qui a réellement eu lieu.
 */
data class RapportSuppression(
    val supprimes: List<Long>,
    val refuses: List<RefusSuppression>
)

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
    private val seanceRepository: SeanceRepository,
    private val preferenceRepository: PreferenceSeanceRepository,
    private val passwordEncoder: PasswordEncoder
) {

    fun lister(recherche: String, pageable: Pageable): Page<UtilisateurResume> {
        val page = if (recherche.isBlank()) {
            utilisateurRepository.findAll(pageable)
        } else {
            // Le même terme est cherché dans les deux colonnes
            utilisateurRepository.findByNomContainingIgnoreCaseOrEmailContainingIgnoreCase(
                recherche.trim(), recherche.trim(), pageable)
        }
        return page.map {
            UtilisateurResume(
                it.id!!, it.email, it.nom, it.role, it.actif, it.villeParDefaut, it.pays)
        }
    }

    /**
     * Export PDF de l'annuaire des comptes.
     *
     * ⚠️ AUCUN mot de passe, ni haché ni autre. Un export circule par courriel
     * et se retrouve dans un dossier de téléchargement : il ne doit porter que
     * ce qui s'affiche déjà à l'écran d'administration.
     *
     * Sans pagination — c'est l'annuaire complet qu'on exporte, pas la page
     * consultée. Le tri suit l'identifiant, seul ordre stable quand le nom peut
     * changer.
     */
    @Transactional(readOnly = true)
    fun exporterEnPdf(): ByteArray {
        val comptes = utilisateurRepository.findAll().sortedBy { it.id }

        val lignes = comptes.map { u ->
            listOf(
                u.id?.toString() ?: "—",
                u.email,
                u.nom,
                if (u.role == Role.ADMIN) "Administrateur" else "Membre",
                if (u.actif) "Actif" else "Bloqué",
                u.villeParDefaut,
                u.pays
            )
        }

        return DocumentPdf.tableau(
            titre = "Comptes utilisateurs",
            sousTitre = "${comptes.size} compte(s) · ${DocumentPdf.horodatage(LocalDateTime.now())}",
            colonnes = listOf(
                DocumentPdf.Colonne("Id", 0.5f),
                DocumentPdf.Colonne("Email", 2.5f),
                DocumentPdf.Colonne("Nom", 2f),
                DocumentPdf.Colonne("Rôle", 1.2f),
                DocumentPdf.Colonne("État", 0.9f),
                DocumentPdf.Colonne("Ville", 1.5f),
                DocumentPdf.Colonne("Pays", 1.5f)
            ),
            lignes = lignes
        )
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

    /**
     * Édition administrative du nom et de la ville de référence.
     *
     * ⚠️ L'EMAIL n'est volontairement PAS modifiable ici : c'est l'identifiant
     * de connexion et la clé unique. Le changer par un écran d'administration
     * déconnecterait l'intéressé sans qu'il comprenne pourquoi, et demanderait
     * une vérification de la nouvelle adresse — un parcours à part entière.
     */
    @Transactional
    fun modifier(id: Long, request: ModifierUtilisateurRequest): ResultatAdmin {
        val cible = utilisateurRepository.findById(id).orElse(null)
            ?: return ResultatAdmin.Introuvable

        if (request.nom.isBlank() || request.villeParDefaut.isBlank()) {
            return ResultatAdmin.Refuse("CHAMP_VIDE", "le nom et la ville sont obligatoires")
        }

        cible.nom = request.nom.trim()                    // dirty checking
        cible.villeParDefaut = request.villeParDefaut.trim()
        // Absent = inchangé : un client qui ignore le champ ne l'écrase pas
        request.pays?.trim()?.takeIf { it.isNotBlank() }?.let { cible.pays = it }
        return ResultatAdmin.Applique
    }

    /**
     * Réinitialisation du mot de passe par un administrateur.
     *
     * ⚠️ Contrairement au changement par l'intéressé (ProfilService), l'ancien
     * mot de passe n'est PAS exigé — un administrateur ne le connaît pas, c'est
     * tout l'objet de la fonction. Le pouvoir est donc réel : il est borné par
     * le rôle ADMIN et par la traçabilité, pas par une vérification de secret.
     */
    @Transactional
    fun reinitialiserMotDePasse(id: Long, nouveau: String): ResultatAdmin {
        val cible = utilisateurRepository.findById(id).orElse(null)
            ?: return ResultatAdmin.Introuvable

        if (nouveau.length < 5) {
            return ResultatAdmin.Refuse(
                "MOT_DE_PASSE_TROP_COURT", "le mot de passe doit faire au moins 5 caractères")
        }

        cible.motDePasse = passwordEncoder.encode(nouveau)
        return ResultatAdmin.Applique
    }

    /**
     * Blocage / déblocage. Mêmes garde-fous que la suppression, pour la même
     * raison : se bloquer soi-même ou bloquer le dernier administrateur rend
     * l'application inadministrable — et le blocage étant réversible seulement
     * PAR un administrateur, il n'y aurait plus personne pour le défaire.
     */
    @Transactional
    fun bloquer(id: Long, actif: Boolean, emailDemandeur: String): ResultatAdmin {
        val cible = utilisateurRepository.findById(id).orElse(null)
            ?: return ResultatAdmin.Introuvable

        if (!actif && cible.email == emailDemandeur) {
            return ResultatAdmin.Refuse(
                "AUTO_BLOCAGE", "vous ne pouvez pas bloquer votre propre compte")
        }

        if (!actif && cible.role == Role.ADMIN &&
            utilisateurRepository.countByRoleAndActifTrue(Role.ADMIN) <= 1) {
            return ResultatAdmin.Refuse(
                "DERNIER_ADMINISTRATEUR", "impossible de bloquer le dernier administrateur actif")
        }

        cible.actif = actif
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

        // Les dépendances d'abord : la contrainte de clé étrangère l'exige.
        // ⚠️ Toute nouvelle table référençant l'utilisateur doit être ajoutée
        // ICI — les préférences de séance ont rejoint la liste.
        seanceRepository.deleteByUtilisateurId(id)
        preferenceRepository.deleteByUtilisateurId(id)
        utilisateurRepository.delete(cible)
        return ResultatAdmin.Applique
    }

    /**
     * ┌───────────────────────────────────────────────────────────────────────┐
     * │ SUPPRESSION EN MASSE — pourquoi une boucle et non une requête unique  │
     * └───────────────────────────────────────────────────────────────────────┘
     *
     * Un `deleteAllById` serait plus rapide et FAUX : les deux garde-fous
     * (auto-suppression, dernier administrateur) sont des règles PAR COMPTE.
     * En réutilisant `supprimer()`, elles restent écrites à un seul endroit —
     * ajouter demain une troisième règle la fait porter aux deux chemins.
     *
     * ⚠️ Le `flush()` est load-bearing. `countByRole` traduit une requête SQL :
     * sans vidage du contexte de persistance, elle ne VOIT PAS les suppressions
     * déjà décidées dans la même transaction. Sur un lot contenant les trois
     * administrateurs, chaque appel lisait encore « 3 » et les trois passaient —
     * l'application se retrouvait sans aucun administrateur.
     *
     * Le résultat est donc PARTIEL par nature : on supprime ce qui est
     * permis et l'on rend compte du reste, plutôt que de tout refuser en bloc
     * parce qu'un identifiant du lot pose problème.
     */
    @Transactional
    fun supprimerEnMasse(ids: List<Long>, emailDemandeur: String): RapportSuppression {
        val supprimes = mutableListOf<Long>()
        val refuses = mutableListOf<RefusSuppression>()

        // distinct() : un même identifiant envoyé deux fois donnerait un
        // « Introuvable » au second passage, ce qui serait un faux refus.
        ids.distinct().forEach { id ->
            when (val resultat = supprimer(id, emailDemandeur)) {
                is ResultatAdmin.Applique -> {
                    utilisateurRepository.flush()
                    supprimes += id
                }
                is ResultatAdmin.Introuvable ->
                    refuses += RefusSuppression(id, "INTROUVABLE", "ce compte n'existe plus")
                is ResultatAdmin.Refuse ->
                    refuses += RefusSuppression(id, resultat.motif, resultat.detail)
            }
        }
        return RapportSuppression(supprimes, refuses)
    }

    fun seancesDe(id: Long, pageable: Pageable): Page<SeanceResponse> =
        seanceRepository.findByUtilisateurId(id, pageable).map(SeanceResponse::de)
}
