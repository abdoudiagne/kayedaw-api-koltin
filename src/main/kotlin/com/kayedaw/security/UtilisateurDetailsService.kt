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

    private fun versUserDetails(u: Utilisateur): UserDetails = User(
        u.email,
        u.motDePasse,
        listOf(SimpleGrantedAuthority("ROLE_${u.role.name}"))
    )
}
