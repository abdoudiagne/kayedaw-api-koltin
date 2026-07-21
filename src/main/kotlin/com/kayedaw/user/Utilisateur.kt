package com.kayedaw.user

import jakarta.persistence.*

enum class Role { USER, ADMIN }

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 2.3 — Pourquoi `class` et non `data class` pour une entité ?   │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Une `data class` génère equals()/hashCode() sur TOUS les champs. Sur une
 * entité JPA, cela pose trois problèmes :
 *
 *   1. Les relations LAZY sont touchées par equals() → chargements intempestifs
 *      (voire LazyInitializationException hors session).
 *   2. L'`id` est null avant la persistance : le hashCode change après le save,
 *      ce qui casse le contrat de hashCode si l'entité est dans un HashSet.
 *   3. `copy()` produit une entité détachée avec le même id → confusion.
 *
 * Règle retenue : `class` pour les entités, `data class` pour les DTO.
 *
 * Rappel : le plugin kotlin-jpa (no-arg) génère le constructeur sans argument
 * exigé par Hibernate, absent par défaut en Kotlin.
 */
@Entity
@Table(name = "utilisateur")
class Utilisateur(

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(nullable = false)
    var motDePasse: String,          // haché (BCrypt), jamais en clair

    @Column(nullable = false)
    var nom: String,

    /**
     * Ville de référence du coureur, OBLIGATOIRE dès l'inscription.
     *
     * Elle sert à pré-remplir le formulaire de séance et surtout à proposer la
     * météo AVANT enregistrement : sans ville connue, impossible d'afficher une
     * prévision tant que l'utilisateur n'a pas saisi de lieu, ce qui vidait
     * l'intérêt de la planification.
     */
    @Column(nullable = false, length = 100)
    var villeParDefaut: String,

    @Enumerated(EnumType.STRING)     // STRING et non ORDINAL : robuste au réordonnancement
    @Column(nullable = false)
    var role: Role = Role.USER,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)
