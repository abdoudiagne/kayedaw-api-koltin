package com.kayedaw.user

import jakarta.persistence.*

enum class Role { USER, ADMIN }

/**
 * Thème d'affichage choisi par l'utilisateur.
 *
 * SYSTEME reste le défaut : le front suivait jusqu'ici `prefers-color-scheme`
 * sans possibilité de le contredire, ce qui convient à la majorité. On ajoute
 * une DÉROGATION explicite, on ne remplace pas la préférence système.
 */
enum class Theme { SYSTEME, CLAIR, SOMBRE }

/** Langue d'interface. Le contenu métier (villes, commentaires) n'est pas traduit. */
enum class Langue { FR, EN }

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

    @Column(nullable = false, length = 100)
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

    /**
     * Pays de résidence, « France » par défaut.
     *
     * ⚠️ Valeur par défaut EN BASE (`columnDefinition`) et pas seulement en
     * Kotlin : `ddl-auto: update` ajoute une colonne NOT NULL à une table déjà
     * peuplée, et sans valeur par défaut côté SQL la migration échoue sur les
     * lignes existantes. Le défaut Kotlin ne couvre que les nouvelles entités.
     *
     * Le pays accompagne la ville pour le géocodage météo : « Lille » existe
     * aussi en Belgique et aux États-Unis.
     */
    @Column(nullable = false, length = 100, columnDefinition = "varchar(100) default 'France'")
    var pays: String = "France",

    @Enumerated(EnumType.STRING)     // STRING et non ORDINAL : robuste au réordonnancement
    @Column(nullable = false)
    var role: Role = Role.USER,

    /**
     * Préférences d'AFFICHAGE, portées par l'utilisateur et non par le
     * navigateur : elles doivent suivre le compte d'un appareil à l'autre.
     *
     * Colonnes scalaires ici plutôt qu'une entité dédiée : ce sont deux valeurs
     * uniques par compte, une table séparée n'apporterait qu'une jointure.
     * Les valeurs par défaut PAR TYPE DE SÉANCE, elles, sont multiples : elles
     * vivent dans PreferenceSeance (paquet `preference`).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var theme: Theme = Theme.SYSTEME,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var langue: Langue = Langue.FR,

    /**
     * Compte ACTIF ou bloqué par un administrateur.
     *
     * Bloquer plutôt que supprimer : la suppression emporte les séances et
     * l'historique, elle est irréversible. Le blocage suspend l'accès en
     * gardant les données — c'est ce qu'on veut pour un abus présumé, le temps
     * de vérifier, ou pour un départ temporaire.
     */
    @Column(nullable = false)
    var actif: Boolean = true,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
)
