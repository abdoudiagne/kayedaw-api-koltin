package com.kayedaw.seance

import com.kayedaw.common.*
import com.kayedaw.config.AppProperties
import com.kayedaw.meteo.EnrichissementMeteoService
import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class SeanceService(
    private val seanceRepository: SeanceRepository,
    private val utilisateurRepository: UtilisateurRepository,
    private val proprietes: AppProperties,         // config typée, pas de @Value épars
    private val enrichissement: EnrichissementMeteoService
) {

    // ─────────────────────────────────────────────────────────────────────
    // CREATE — retourne une sealed interface plutôt que de lever une exception
    // ─────────────────────────────────────────────────────────────────────
    /**
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ QUESTION 3.3 — @Transactional : les 3 pièges                      │
     * └───────────────────────────────────────────────────────────────────┘
     *
     * 1. AUTO-INVOCATION : `this.autreMethode()` court-circuite le proxy Spring,
     *    la transaction n'est PAS appliquée. Il faut passer par un autre bean.
     * 2. ROLLBACK : par défaut uniquement sur les exceptions NON VÉRIFIÉES
     *    (RuntimeException). En Kotlin toutes les exceptions sont non vérifiées,
     *    ce piège est donc moins visible qu'en Java — mais `rollbackFor` reste utile.
     * 3. PROXIFICATION : la méthode doit être publique et la classe ouverte
     *    (d'où le plugin kotlin-spring / all-open).
     */
    @Transactional
    fun creer(request: CreerSeanceRequest, emailUtilisateur: String): ResultatCreationSeance {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        val dateHeure = requireNotNull(request.dateHeure)
        val type = requireNotNull(request.type)

        /*
         * Règle 1 : on planifie jusqu'à l'horizon configuré, pas au-delà.
         * Le passé reste libre (on saisit une séance déjà courue), mais une
         * date trop lointaine n'a pas de sens : ni prévision météo fiable,
         * ni plan d'entraînement crédible.
         */
        val horizon = proprietes.entrainement.planificationMaxJours
        if (dateHeure.isAfter(LocalDateTime.now().plusDays(horizon))) {
            return ResultatCreationSeance.DateTropLointaine(horizonJours = horizon)
        }

        /*
         * Règle 2 : plafond hebdomadaire.
         * Les séances PLANIFIÉES comptent ici volontairement — le plafond sert
         * à prévenir le surentraînement, autant le signaler dès la planification
         * plutôt qu'une fois la semaine déjà surchargée.
         */
        val semaine = dateHeure.semaineComplete()          // fonction d'extension
        val volumeSemaine = seanceRepository
            .findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(
                utilisateur.id!!, semaine.start, semaine.endInclusive)
            .sumOf { it.distanceKm }

        val volumeProjete = (volumeSemaine + request.distanceKm).arrondi2()
        if (volumeProjete > proprietes.entrainement.plafondHebdoKm) {
            return ResultatCreationSeance.PlafondHebdoDepasse(
                volumeCalculeKm = volumeProjete,
                plafondKm = proprietes.entrainement.plafondHebdoKm
            )
        }

        // `apply` : configure et retourne l'objet — scope function idiomatique
        val seance = Seance(
            type = type,
            distanceKm = request.distanceKm,
            dureeMinutes = request.dureeMinutes,
            dateHeure = dateHeure,
            utilisateur = utilisateur
        ).apply {
            commentaire = request.commentaire?.trim()      // safe call

            /*
             * APPEL SORTANT (WebClient + coroutines) — best-effort.
             *
             * `runBlocking` fait le pont entre le monde servlet et les coroutines.
             * Avec Java 21, il s'exécute sur un thread virtuel : le blocage
             * démonte le thread de son porteur, qui repart servir d'autres requêtes.
             *
             * Si le service météo est lent ou en panne, `conditions()` retourne
             * null (timeout + repli) et la séance est créée sans enrichissement.
             * Une dépendance externe ne doit JAMAIS faire échouer le cas d'usage
             * principal.
             */
            request.ville?.takeIf { it.isNotBlank() }?.let { villeDemandee ->
                runBlocking { enrichissement.conditions(villeDemandee, dateHeure) }?.also { c ->
                    ville = c.ville
                    temperatureMaxC = c.temperatureMaxC
                    temperatureMinC = c.temperatureMinC
                    temperatureALHeureC = c.temperatureALHeureC
                    sourceMeteo = c.source.name
                    stationMeteo = c.station
                    precipitationMm = c.precipitationMm
                    ventKmH = c.ventMaxKmH
                    pm25 = c.pm25
                    alertesMeteo = c.alertes().joinToString("|").ifBlank { null }
                }
            }
        }

        val sauvegardee = seanceRepository.save(seance)
        return ResultatCreationSeance.Creee(seanceId = sauvegardee.id!!)
    }

    // ─────────────────────────────────────────────────────────────────────
    // READ
    // ─────────────────────────────────────────────────────────────────────
    fun parId(id: Long, emailUtilisateur: String): SeanceResponse {
        val seance = seanceRepository.findById(id)
            .orElseThrow { SeanceIntrouvableException(id) }
        verifierProprietaire(seance, emailUtilisateur)
        return SeanceResponse.de(seance)
    }

    /** Utilise @EntityGraph : une seule requête, pas de N+1. */
    fun lister(emailUtilisateur: String, pageable: Pageable): Page<SeanceResponse> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        return seanceRepository
            .findWithUtilisateurByUtilisateurId(utilisateur.id!!, pageable)
            .map(SeanceResponse::de)                      // référence de méthode
    }

    /** Liste filtrée : tous les critères sont facultatifs. */
    fun rechercher(
        emailUtilisateur: String,
        debut: LocalDate?,
        fin: LocalDate?,
        type: TypeSeance?,
        recherche: String?,
        pageable: Pageable
    ): Page<SeanceResponse> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        return seanceRepository.rechercher(
            userId = utilisateur.id!!,
            debut = debut?.atStartOfDay(),
            fin = fin?.plusDays(1)?.atStartOfDay()?.minusNanos(1),
            type = type,
            recherche = recherche?.trim()?.takeIf { it.isNotBlank() },
            pageable = pageable
        ).map(SeanceResponse::de)
    }

    fun parType(type: TypeSeance, emailUtilisateur: String, pageable: Pageable): Page<SeanceResponse> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        return seanceRepository.findByUtilisateurIdAndType(utilisateur.id!!, type, pageable)
            .map(SeanceResponse::de)
    }

    /**
     * Séances RÉALISÉES de la période : les séances planifiées sont exclues,
     * sinon la distance totale et l'allure moyenne compteraient des sorties
     * qui n'ont pas encore eu lieu.
     */
    fun seancesDeLaPeriode(emailUtilisateur: String, debut: LocalDate, fin: LocalDate): List<Seance> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        val maintenant = LocalDateTime.now()
        return seanceRepository.findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(
            utilisateur.id!!, debut.atStartOfDay(), fin.plusDays(1).atStartOfDay().minusNanos(1))
            .filterNot { it.estPlanifiee(maintenant) }
    }

    /** Historique complet des séances RÉALISÉES : base de calcul des records. */
    fun toutesLesSeancesRealisees(emailUtilisateur: String): List<Seance> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        val maintenant = LocalDateTime.now()
        return seanceRepository.findByUtilisateurIdOrderByDateHeureAsc(utilisateur.id!!)
            .filterNot { it.estPlanifiee(maintenant) }
    }

    fun volumeParType(emailUtilisateur: String, debut: LocalDate, fin: LocalDate): Map<TypeSeance, Double> {
        val utilisateur = utilisateurParEmail(emailUtilisateur)
        // Projection SQL : l'agrégation est faite par la base, pas en mémoire
        return seanceRepository.volumeParType(
            utilisateur.id!!, debut.atStartOfDay(), fin.plusDays(1).atStartOfDay().minusNanos(1),
            LocalDateTime.now())
            .associate { it.getType() to it.getDistance().arrondi2() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UPDATE
    // ─────────────────────────────────────────────────────────────────────
    @Transactional
    fun modifier(id: Long, request: ModifierSeanceRequest, emailUtilisateur: String): SeanceResponse {
        val seance = seanceRepository.findById(id)
            .orElseThrow { SeanceIntrouvableException(id) }
        verifierProprietaire(seance, emailUtilisateur)

        // Dirty checking : l'entité est managée, les modifications sont
        // persistées au commit sans appel explicite à save().
        seance.apply {
            type = requireNotNull(request.type)
            distanceKm = request.distanceKm
            dureeMinutes = request.dureeMinutes
            dateHeure = requireNotNull(request.dateHeure)
            commentaire = request.commentaire?.trim()
        }
        return SeanceResponse.de(seance)
    }

    // ─────────────────────────────────────────────────────────────────────
    // DELETE
    // ─────────────────────────────────────────────────────────────────────
    @Transactional
    fun supprimer(id: Long, emailUtilisateur: String) {
        val seance = seanceRepository.findById(id)
            .orElseThrow { SeanceIntrouvableException(id) }
        verifierProprietaire(seance, emailUtilisateur)
        seanceRepository.delete(seance)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers privés
    // ─────────────────────────────────────────────────────────────────────
    private fun utilisateurParEmail(email: String): Utilisateur =
        utilisateurRepository.findByEmail(email)
            ?: throw IllegalStateException("utilisateur authentifié introuvable : $email")

    /**
     * QUESTION 6.4 — Autorisation : on ne fait jamais confiance au client.
     * L'email vient du SecurityContext (jeton validé), jamais du corps de requête.
     * Un ADMIN peut tout consulter ; un USER seulement ses propres séances.
     */
    private fun verifierProprietaire(seance: Seance, email: String) {
        val demandeur = utilisateurParEmail(email)
        if (demandeur.role != Role.ADMIN && seance.utilisateur.id != demandeur.id) {
            throw AccesRefuseException("cette séance ne vous appartient pas")
        }
    }
}
