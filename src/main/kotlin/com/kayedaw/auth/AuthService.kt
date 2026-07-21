package com.kayedaw.auth

import com.kayedaw.common.EmailDejaUtiliseException
import com.kayedaw.security.JwtService
import com.kayedaw.user.Role
import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthService(
    private val utilisateurRepository: UtilisateurRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val authenticationManager: AuthenticationManager
) {

    @Transactional
    fun inscrire(request: InscriptionRequest): AuthResponse {
        if (utilisateurRepository.existsByEmail(request.email)) {
            throw EmailDejaUtiliseException(request.email)
        }

        val utilisateur = utilisateurRepository.save(
            Utilisateur(
                email = request.email.lowercase().trim(),
                motDePasse = passwordEncoder.encode(request.motDePasse),   // BCrypt
                nom = request.nom.trim(),
                villeParDefaut = request.villeParDefaut.trim(),
                role = Role.USER
            )
        )
        return reponseAuth(utilisateur)
    }

    fun connecter(request: ConnexionRequest): AuthResponse {
        /*
         * ⚠️ L'email est normalisé EXACTEMENT comme à l'inscription.
         *
         * Sans cela, un compte créé avec « Abdou@Gmail.com » est stocké en
         * minuscules et son propriétaire ne peut plus jamais se connecter en
         * saisissant son adresse telle qu'il l'écrit : 401 systématique, sans
         * la moindre indication de la cause.
         */
        val email = request.email.lowercase().trim()
        // Lève BadCredentialsException si les identifiants sont incorrects.
        // Le message d'erreur renvoyé au client ne précise PAS si c'est
        // l'email ou le mot de passe : on évite l'énumération de comptes.
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(email, request.motDePasse)
        )

        val utilisateur = utilisateurRepository.findByEmail(email)
            ?: throw IllegalStateException("utilisateur authentifié mais introuvable")

        return reponseAuth(utilisateur)
    }

    private fun reponseAuth(u: Utilisateur) = AuthResponse(
        token = jwtService.genererToken(u.email, u.role.name),
        expireDansMs = jwtService.dureeValiditeMs(),
        email = u.email,
        nom = u.nom,
        role = u.role.name,
        villeParDefaut = u.villeParDefaut
    )
}
