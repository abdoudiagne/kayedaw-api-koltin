package com.kayedaw.config

import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION — Comment obtenir un compte ADMIN ?                            │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * L'inscription publique force `Role.USER` (voir AuthService) : c'est
 * volontaire, sinon n'importe qui se déclarerait administrateur. Le premier
 * administrateur doit donc être créé hors du parcours d'inscription.
 *
 * Trois approches, de la plus artisanale à la plus sérieuse :
 *   1. un CommandLineRunner de démarrage — retenu ici, suffisant pour la démo ;
 *   2. un script de migration versionné (Flyway / Liquibase) — la vraie
 *      réponse en production : rejouable, tracé, revu en code review ;
 *   3. une promotion manuelle en base par un exploitant.
 *
 * ⚠️ POURQUOI @Profile("!prod") : ce runner écrit un mot de passe CONNU DE
 * TOUS puisqu'il est en clair dans le dépôt. Acceptable sur une base H2 en
 * mémoire qui repart vide à chaque redémarrage ; inacceptable en production.
 * Le profil l'exclut mécaniquement plutôt que de compter sur la vigilance.
 *
 * Le mot de passe n'est jamais stocké tel quel : `PasswordEncoder` le hache
 * en BCrypt, exactement comme pour une inscription normale.
 */
@Configuration
@Profile("!prod")
class DatasInitiales {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun creerComptesDeDemonstration(
        utilisateurRepository: UtilisateurRepository,
        passwordEncoder: PasswordEncoder
    ) = CommandLineRunner {

        COMPTES.forEach { compte ->
            // Idempotent : au cas où la base serait persistante, on ne duplique pas
            if (utilisateurRepository.existsByEmail(compte.email)) {
                return@forEach
            }

            utilisateurRepository.save(
                Utilisateur(
                    email = compte.email,
                    motDePasse = passwordEncoder.encode(compte.motDePasse),
                    nom = compte.nom,
                    villeParDefaut = VILLE_PAR_DEFAUT,
                    role = compte.role
                )
            )

            log.warn(
                "Compte {} de démonstration créé : {} / {}",
                compte.role, compte.email, compte.motDePasse
            )
        }
    }

    /** Un compte de démonstration par rôle : de quoi tester les deux parcours. */
    private data class CompteDemo(
        val email: String,
        val motDePasse: String,
        val nom: String,
        val role: Role
    )

    companion object {
        const val EMAIL_ADMIN = "admin@kayedaw.fr"
        const val EMAIL_UTILISATEUR = "user@kayedaw.fr"
        const val MOT_DE_PASSE_DEMO = "12345"

        /** Ville de référence des comptes de démonstration. */
        const val VILLE_PAR_DEFAUT = "Lille"

        private val COMPTES = listOf(
            CompteDemo(EMAIL_ADMIN, MOT_DE_PASSE_DEMO, "Administrateur ", Role.ADMIN),
            CompteDemo(EMAIL_UTILISATEUR, MOT_DE_PASSE_DEMO, "Abdou Kane", Role.USER)
        )
    }
}
