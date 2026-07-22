package com.kayedaw.security

import com.kayedaw.user.Utilisateur
import com.kayedaw.user.UtilisateurRepository
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * QUESTION 6.4 — Authentification vs autorisation.
 * Ce service répond à « QUI êtes-vous ? » : il charge l'utilisateur et ses
 * autorités. L'autorisation (« que pouvez-vous faire ? ») est ailleurs :
 * règles d'URL dans SecurityConfig, @PreAuthorize, et vérification de
 * propriété dans SeanceService.
 *
 * Convention Spring : le préfixe ROLE_ est exigé par hasRole("ADMIN").
 */
@Service
class UtilisateurDetailsService(
    private val repository: UtilisateurRepository
) : UserDetailsService {

    override fun loadUserByUsername(email: String): UserDetails =
        repository.findByEmail(email)
            ?.let(::versUserDetails)                       // let + référence de fonction
            ?: throw UsernameNotFoundException("utilisateur introuvable : $email")

    /**
     * `disabled` porte le blocage administratif.
     *
     * On ne l'implémente PAS en levant une exception ici : le contrat de
     * UserDetails prévoit déjà cet état, et DaoAuthenticationProvider le
     * vérifie avant même de comparer le mot de passe — un compte bloqué ne
     * peut donc pas non plus servir à deviner un mot de passe valide.
     */
    private fun versUserDetails(u: Utilisateur): UserDetails = User.withUsername(u.email)
        .password(u.motDePasse)
        .authorities(listOf(SimpleGrantedAuthority("ROLE_${u.role.name}")))
        .disabled(!u.actif)
        .build()
}
