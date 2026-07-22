package com.kayedaw.preference

import com.kayedaw.common.CompteurRequetes
import com.kayedaw.config.AppProperties
import com.kayedaw.seance.TypeSeance
import com.kayedaw.user.Langue
import com.kayedaw.user.Theme
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ PRÉFÉRENCES UTILISATEUR                                                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Sous /api/profil/preferences : ce sont des données DU COMPTE, au même titre
 * que le nom ou la ville de référence. Comme partout, l'identité vient du
 * SecurityContext et jamais du corps de la requête.
 *
 * La réponse contient TOUJOURS les cinq types, y compris ceux que
 * l'utilisateur n'a jamais réglés : le front affiche un tableau complet sans
 * avoir à connaître les valeurs d'usine, et un type ajouté côté serveur
 * apparaît automatiquement.
 */
@RestController
@RequestMapping("/api/profil/preferences")
class PreferenceController(
    private val service: PreferenceService,
    private val compteur: CompteurRequetes
) {

    @GetMapping
    fun preferences(@AuthenticationPrincipal utilisateur: UserDetails): PreferencesResponse {
        compteur.incrementer("GET /api/profil/preferences")
        return service.preferences(utilisateur.username)
    }

    @PutMapping
    fun modifier(
        @Valid @RequestBody request: ModifierPreferencesRequest,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): PreferencesResponse {
        compteur.incrementer("PUT /api/profil/preferences")
        return service.modifier(utilisateur.username, request)
    }
}

// ─────────────────────────────── DTO ────────────────────────────────────────

data class DefautSeanceDto(
    val type: TypeSeance,

    /**
     * Bornes alignées sur celles d'une séance réelle : une valeur par défaut
     * qui ne passerait pas la création n'aurait aucun sens. Le maximum reprend
     * `distance-max-seance-km`, vérifié en plus côté service — une annotation
     * ne peut pas lire la configuration.
     */
    @field:DecimalMin(value = "0.1", message = "la distance doit être positive")
    val distanceKm: Double,

    @field:Min(value = 1, message = "la durée doit valoir au moins 1 minute")
    @field:Max(value = 1440, message = "la durée ne peut pas dépasser une journée")
    val dureeMinutes: Int
)

data class ModifierPreferencesRequest(
    val theme: Theme,
    val langue: Langue,
    val seances: List<@Valid DefautSeanceDto>
)

data class PreferencesResponse(
    val theme: Theme,
    val langue: Langue,
    val seances: List<DefautSeanceDto>
)

// ────────────────────────────── SERVICE ─────────────────────────────────────

@Service
@Transactional(readOnly = true)
class PreferenceService(
    private val utilisateurRepository: UtilisateurRepository,
    private val preferenceRepository: PreferenceSeanceRepository,
    private val proprietes: AppProperties
) {

    companion object {
        /**
         * Valeurs d'USINE, servies tant que l'utilisateur n'a rien réglé.
         *
         * Elles ne sont volontairement PAS écrites en base à l'inscription :
         * une ligne absente signifie « jamais personnalisé », ce qui permet de
         * faire évoluer ces repères sans réécrire les comptes existants.
         * Chacune donne une allure plausible (entre 2 et 20 min/km), sinon le
         * formulaire s'ouvrirait sur une erreur d'allure irréaliste.
         */
        const val DISTANCE_USINE_KM = 5.0
        const val DUREE_USINE_MINUTES = 60

        /**
         * Le MÊME repère pour tous les types, à dessein : 5 km en 60 min donne
         * une allure de 12 min/km, plausible pour n'importe quel type — y
         * compris la marche — donc le formulaire ne s'ouvre jamais sur une
         * erreur d'allure irréaliste. C'est un point de départ neutre, pas une
         * recommandation d'entraînement : à chacun de régler ses valeurs.
         */
        val DEFAUTS: Map<TypeSeance, Pair<Double, Int>> =
            TypeSeance.entries.associateWith { DISTANCE_USINE_KM to DUREE_USINE_MINUTES }
    }

    fun preferences(email: String): PreferencesResponse {
        val utilisateur = parEmail(email)
        return construire(utilisateur, preferenceRepository.findByUtilisateurId(utilisateur.id!!))
    }

    /**
     * Remplacement COMPLET plutôt que fusion ligne à ligne.
     *
     * L'écran envoie toujours les cinq types : supprimer puis réécrire est plus
     * simple à raisonner qu'un différentiel, et évite qu'un type retiré de
     * l'enum laisse une ligne orpheline. Le volume est de cinq lignes par
     * compte — l'argument de performance ne s'applique pas ici.
     */
    @Transactional
    fun modifier(email: String, request: ModifierPreferencesRequest): PreferencesResponse {
        val utilisateur = parEmail(email)

        val maximum = proprietes.entrainement.distanceMaxSeanceKm
        request.seances.firstOrNull { it.distanceKm > maximum }?.let {
            throw IllegalArgumentException(
                "la distance par défaut de ${it.type} dépasse le maximum de $maximum km")
        }

        utilisateur.theme = request.theme
        utilisateur.langue = request.langue

        preferenceRepository.deleteByUtilisateurId(utilisateur.id!!)
        // flush implicite à la fin de la transaction : on force l'ordre pour
        // que la suppression précède l'insertion, sinon la contrainte
        // d'unicité (utilisateur, type) saute.
        preferenceRepository.flush()

        // Les doublons de type sont écartés : l'écran n'en produit pas, mais
        // un appel direct à l'API le pourrait, et la contrainte l'exigerait.
        val lignes = request.seances.distinctBy { it.type }.map {
            PreferenceSeance(
                utilisateur = utilisateur,
                type = it.type,
                distanceKm = it.distanceKm,
                dureeMinutes = it.dureeMinutes
            )
        }
        preferenceRepository.saveAll(lignes)

        return construire(utilisateur, lignes)
    }

    private fun construire(
        utilisateur: Utilisateur,
        lignes: List<PreferenceSeance>
    ): PreferencesResponse {
        val parType = lignes.associateBy { it.type }

        return PreferencesResponse(
            theme = utilisateur.theme,
            langue = utilisateur.langue,
            // On itère sur l'ENUM et non sur les lignes : l'ordre de la réponse
            // est celui de l'enum, stable, et aucun type ne peut manquer.
            seances = TypeSeance.entries.map { type ->
                val enregistree = parType[type]
                val usine = DEFAUTS[type] ?: (DISTANCE_USINE_KM to DUREE_USINE_MINUTES)
                DefautSeanceDto(
                    type = type,
                    distanceKm = enregistree?.distanceKm ?: usine.first,
                    dureeMinutes = enregistree?.dureeMinutes ?: usine.second
                )
            }
        )
    }

    private fun parEmail(email: String): Utilisateur =
        utilisateurRepository.findByEmail(email)
            ?: throw IllegalStateException("utilisateur authentifié introuvable : $email")
}
