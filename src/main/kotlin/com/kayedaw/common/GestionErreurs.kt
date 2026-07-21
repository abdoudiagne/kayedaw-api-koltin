package com.kayedaw.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

data class ErreurResponse(
    val statut: Int,
    val erreur: String,
    val message: String,
    val horodatage: Instant = Instant.now()
)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 3.5 — Gestion des erreurs dans une API REST                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Un @RestControllerAdvice centralise la traduction exception → code HTTP.
 * Bénéfices : plus de try/catch dans les controllers, contrat d'erreur
 * uniforme, et un seul endroit à modifier.
 *
 * Sémantique des codes retenue :
 *   400 validation d'entrée   |  401 non authentifié
 *   403 authentifié mais interdit  |  404 ressource absente
 *   409 conflit (doublon)     |  422 entité valide mais règle métier violée
 *
 * Les cas métier « normaux » (plafond dépassé, date future) ne passent PAS
 * par ici : ils sont modélisés par la sealed interface ResultatCreationSeance
 * et traduits dans le controller.
 */
@RestControllerAdvice
class GestionErreurs {

    @ExceptionHandler(SeanceIntrouvableException::class)
    fun introuvable(e: SeanceIntrouvableException) = reponse(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(EmailDejaUtiliseException::class)
    fun conflit(e: EmailDejaUtiliseException) = reponse(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(AccesRefuseException::class, AccessDeniedException::class)
    fun accesRefuse(e: Exception) = reponse(HttpStatus.FORBIDDEN, e.message ?: "accès refusé")

    @ExceptionHandler(BadCredentialsException::class)
    fun mauvaisIdentifiants(e: BadCredentialsException) =
        reponse(HttpStatus.UNAUTHORIZED, "email ou mot de passe incorrect")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ErreurResponse> {
        // joinToString : plus concis qu'un Collectors.joining
        val details = e.bindingResult.fieldErrors.joinToString(", ") {
            "${it.field} : ${it.defaultMessage}"
        }
        return reponse(HttpStatus.BAD_REQUEST, details)
    }

    private fun reponse(statut: HttpStatus, message: String?): ResponseEntity<ErreurResponse> =
        ResponseEntity.status(statut).body(
            ErreurResponse(statut.value(), statut.reasonPhrase, message.ouSiVide("erreur"))
        )
}
