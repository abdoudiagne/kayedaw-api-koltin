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
    @field:NotBlank @field:Size(min = 8, message = "le mot de passe doit faire au moins 8 caractères")
    val motDePasse: String,
    @field:NotBlank val nom: String,

    @field:NotBlank(message = "la ville est obligatoire")
    @field:Size(max = 100)
    val villeParDefaut: String
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
    val villeParDefaut: String
)
