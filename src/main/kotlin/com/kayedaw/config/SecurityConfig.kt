package com.kayedaw.config

import com.kayedaw.security.JwtAuthFilter
import com.kayedaw.security.UtilisateurDetailsService
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 6.3 — Stockage des mots de passe                               │
 * │ QUESTION 6.1 — Pourquoi CSRF désactivé et sessions STATELESS ?          │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * BCrypt : hachage lent avec sel automatique. Jamais de chiffrement (réversible),
 * jamais de MD5/SHA-1 (trop rapides, donc vulnérables au bruteforce GPU).
 *
 * CSRF protège contre l'envoi automatique d'un COOKIE de session par le
 * navigateur. Avec un JWT porté dans un en-tête, ce vecteur n'existe pas :
 * CSRF devient inutile. STATELESS = aucune session serveur, donc scalabilité
 * horizontale immédiate.
 *
 * Rappel Kotlin : cette classe est annotée @Configuration, donc Spring doit
 * la proxifier → le plugin all-open (kotlin-spring) l'ouvre automatiquement.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity        // active @PreAuthorize (voir AdminController)
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val utilisateurDetailsService: UtilisateurDetailsService
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationProvider(): DaoAuthenticationProvider =
        DaoAuthenticationProvider().apply {                 // apply : configuration fluide
            setUserDetailsService(utilisateurDetailsService)
            setPasswordEncoder(passwordEncoder())
        }

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                /**
                 * Le re-dispatch ERROR doit être autorisé, sinon il est réévalué.
                 *
                 * AccessDeniedHandlerImpl répond par `sendError(403)`, ce qui fait
                 * repasser la requête dans la chaîne en dispatch ERROR vers /error.
                 * En STATELESS, ce second passage n'a plus de SecurityContext : il
                 * est donc ANONYME, `anyRequest().authenticated()` le refuse, et la
                 * réponse finale devient celle du point d'entrée (401) au lieu du
                 * 403 voulu. Le vrai statut est écrasé par le traitement de l'erreur.
                 */
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()

                it.requestMatchers("/api/auth/**").permitAll()

                /*
                 * Recherche de villes accessible sans authentification.
                 *
                 * Elle sert au formulaire d'INSCRIPTION, donc forcément avant
                 * toute connexion : la laisser protégée rendait l'autocomplétion
                 * silencieusement inopérante à l'endroit même où l'utilisateur
                 * en a le plus besoin — le champ échouait sans le moindre signe.
                 *
                 * Aucune donnée personnelle n'y transite, c'est un simple relais
                 * de géocodage. En production on y ajouterait une limitation de
                 * débit, un relais ouvert restant une ressource abusable.
                 */
                it.requestMatchers(HttpMethod.GET, "/api/meteo/villes").permitAll()
                /*
                 * Le référentiel des pays est lu par l'écran d'INSCRIPTION,
                 * donc avant toute session. Le protéger rendrait le champ vide
                 * exactement là où il sert — et il ne révèle rien : c'est la
                 * liste ISO 3166, publique par nature.
                 */
                it.requestMatchers(HttpMethod.GET, "/api/meteo/pays").permitAll()
                it.requestMatchers("/h2-console/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // Défense en profondeur : rôle exigé ici ET via @PreAuthorize
                it.requestMatchers("/api/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            /**
             * 401 vs 403 — la distinction compte, et elle n'est PAS automatique.
             *
             * Sans point d'entrée explicite, Spring Security répond 403 à tout,
             * y compris à une requête SANS aucune authentification. Or :
             *   401 Unauthorized = « je ne sais pas qui vous êtes » (jeton absent
             *       ou expiré) → le client doit se reconnecter ;
             *   403 Forbidden   = « je sais qui vous êtes, mais c'est non »
             *       (rôle insuffisant) → se reconnecter n'y changera rien.
             *
             * Le front s'appuie sur cette distinction : son intercepteur purge la
             * session sur 401 et se contente d'un message sur 403.
             */
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .headers { it.frameOptions { fo -> fo.disable() } }   // console H2 (dev uniquement)
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
