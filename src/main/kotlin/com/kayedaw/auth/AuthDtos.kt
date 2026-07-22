package com.kayedaw.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * L'annotation @Email par défaut accepte « a@b » : ni point, ni extension.
 * Ce motif exige un domaine de premier niveau d'au moins deux lettres, ce qui
 * écarte les fautes de frappe les plus courantes sans verser dans la
 * sur-validation (une adresse valide reste très permissive par la RFC).
 */
const val MOTIF_EMAIL = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"

data class InscriptionRequest(
    @field:Email(regexp = MOTIF_EMAIL, message = "email invalide") @field:NotBlank val email: String,
    @field:NotBlank @field:Size(min = 5, message = "le mot de passe doit faire au moins 5 caractères")
    val motDePasse: String,
    /**
     * ⚠️ `@Size` indispensable, et pas seulement « propre ».
     *
     * Sans borne, un nom de 300 caractères passait la validation, atteignait la
     * base et faisait déborder la colonne : le client recevait un **500**, une
     * panne serveur, là où sa saisie était simplement trop longue. La limite
     * reprend celle de la colonne (100).
     */
    @field:NotBlank(message = "le nom est obligatoire")
    @field:Size(max = 100, message = "le nom ne peut pas dépasser 100 caractères")
    val nom: String,

    @field:NotBlank(message = "la ville est obligatoire")
    @field:Size(max = 100)
    val villeParDefaut: String,

    /** Optionnel : « France » si absent. La quasi-totalité des comptes y sont,
        et un champ de plus à l'inscription est un champ de plus à abandonner. */
    @field:Size(max = 100)
    val pays: String? = null
)

data class ConnexionRequest(
    @field:Email(regexp = MOTIF_EMAIL, message = "email invalide") @field:NotBlank val email: String,
    @field:NotBlank val motDePasse: String
)

data class AuthResponse(
    val token: String,
    val typeToken: String = "Bearer",     // valeur par défaut : pas de surcharge de constructeur
    val expireDansMs: Long,
    val email: String,
    val nom: String,
    val role: String,
    /** Renvoyée à la connexion : le front pré-remplit ses formulaires avec. */
    val villeParDefaut: String,

    /**
     * ⚠️ Le pays accompagne la ville, et son absence ici était un BUG.
     *
     * Le front pré-remplit le formulaire de séance avec le lieu du compte. La
     * ville arrivait, le pays non : faute de mieux, le client retombait sur son
     * repli « France ». Un coureur sénégalais ouvrait donc « Nouvelle séance »
     * sur Dakar / **France** — introuvable, donc aucune suggestion et aucune
     * météo, alors que son profil était juste.
     *
     * Les deux vont ensemble : une ville ne se géocode pas sans son pays.
     */
    val pays: String
)
