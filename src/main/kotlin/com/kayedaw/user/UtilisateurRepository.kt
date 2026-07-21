package com.kayedaw.user

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface UtilisateurRepository : JpaRepository<Utilisateur, Long> {
    // Query methods : Spring Data dérive la requête du nom de la méthode.
    // Le retour `Utilisateur?` (nullable) est plus idiomatique en Kotlin
    // qu'un Optional : on exploite directement ?. et ?: à l'appel.
    fun findByEmail(email: String): Utilisateur?
    fun existsByEmail(email: String): Boolean

    /**
     * Recherche insensible à la casse sur le nom OU l'email.
     * `Containing` génère un LIKE %...% : suffisant ici, mais à surveiller sur
     * une grosse table — un LIKE non ancré ne peut pas utiliser d'index B-tree.
     */
    fun findByNomContainingIgnoreCaseOrEmailContainingIgnoreCase(
        nom: String, email: String, pageable: Pageable
    ): Page<Utilisateur>

    fun countByRole(role: Role): Long
}
