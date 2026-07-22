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
import java.time.format.DateTimeFormatter

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
                /*
                 * ┌───────────────────────────────────────────────────────────┐
                 * │ Le pays de la SÉANCE, à défaut celui du compte            │
                 * └───────────────────────────────────────────────────────────┘
                 *
                 * Un pays reste indispensable : c'est lui qui lève l'homonymie.
                 * « Dakar » désigne la capitale du Sénégal pour qui court au
                 * Sénégal, et non un homonyme choisi au hasard du classement du
                 * géocodeur.
                 *
                 * Mais il ne peut plus venir du seul COMPTE : on ne court pas
                 * toujours chez soi. Un coureur français en déplacement voyait
                 * sa séance de Dakar rattachée à un homonyme français, ou à
                 * rien. Le pays du compte n'est donc plus qu'un DÉFAUT — celui
                 * du cas courant, qui n'exige aucune saisie.
                 *
                 * Il est aussi STOCKÉ sur la séance : la météo l'est déjà, et
                 * afficher plus tard un pays lu sur le compte prêterait à un
                 * compte déménagé des séances qu'il n'a jamais courues là.
                 */
                val paysDemande = request.pays?.trim()?.takeIf { it.isNotBlank() }
                    ?: utilisateur.pays
                pays = paysDemande

                runBlocking {
                    enrichissement.conditions(villeDemandee, dateHeure, paysDemande)
                }?.also { c ->
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

    /**
     * Le carnet complet, mis en tableau pour l'export.
     *
     * ⚠️ Les séances PLANIFIÉES y figurent, contrairement aux statistiques qui
     * les excluent. Ce n'est pas une incohérence : un carnet rend compte de ce
     * qui est écrit dedans, y compris ce qui est prévu, là où une statistique
     * prétend décrire ce qui a été couru. La colonne « État » lève l'ambiguïté
     * plutôt que de masquer les lignes.
     */
    /**
     * Allure décimale -> « 5'30" ».
     *
     * ⚠️ L'arrondi peut donner SOIXANTE secondes : 5.999 min/km rendrait
     * « 5'60" », qui ne veut rien dire. Le report sur la minute est le même
     * garde-fou que celui du pipe Angular — les deux formatages doivent
     * s'accorder, l'utilisateur comparant l'écran et le PDF.
     */
    private fun formaterAllure(allure: Double): String {
        var minutes = allure.toInt()
        var secondes = Math.round((allure - minutes) * 60).toInt()
        if (secondes == 60) {
            minutes += 1
            secondes = 0
        }
        return "%d'%02d\"".format(minutes, secondes)
    }

    @Transactional(readOnly = true)
    fun exporterEnPdf(emailUtilisateur: String): ByteArray {
        val utilisateur = utilisateurRepository.findByEmail(emailUtilisateur)
            ?: throw AccesRefuseException("utilisateur inconnu")

        val seances = seanceRepository.findByUtilisateurIdOrderByDateHeureAsc(utilisateur.id!!)
        val formatDate = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        val lignes = seances.map { s ->
            listOf(
                s.dateHeure.format(formatDate),
                s.type.name.lowercase().replaceFirstChar { it.uppercase() },
                "%.1f".format(s.distanceKm),
                "${s.dureeMinutes} min",
                formaterAllure(s.allureMinParKm()),
                listOfNotNull(s.ville, s.pays).joinToString(", ").ifBlank { "—" },
                s.temperatureALHeureC?.let { "$it °C" } ?: s.temperatureMaxC?.let { "$it °C" } ?: "—",
                if (s.estPlanifiee()) "Planifiée" else "Réalisée",
                s.commentaire ?: ""
            )
        }

        return DocumentPdf.tableau(
            titre = "Carnet d'entraînement — ${utilisateur.nom}",
            sousTitre = "${seances.size} séance(s) · ${DocumentPdf.horodatage(LocalDateTime.now())}",
            colonnes = listOf(
                DocumentPdf.Colonne("Date", 1.5f),
                DocumentPdf.Colonne("Type", 1.2f),
                DocumentPdf.Colonne("Distance", 0.9f),
                DocumentPdf.Colonne("Durée", 0.9f),
                DocumentPdf.Colonne("Allure", 1f),
                DocumentPdf.Colonne("Lieu", 2f),
                DocumentPdf.Colonne("Température", 1.1f),
                DocumentPdf.Colonne("État", 1f),
                DocumentPdf.Colonne("Commentaire", 2.5f)
            ),
            lignes = lignes
        )
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
