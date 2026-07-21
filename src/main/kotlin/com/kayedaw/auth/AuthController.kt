package com.kayedaw.auth

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/inscription")
    @ResponseStatus(HttpStatus.CREATED)
    fun inscrire(@Valid @RequestBody request: InscriptionRequest): AuthResponse =
        authService.inscrire(request)

    @PostMapping("/connexion")
    fun connecter(@Valid @RequestBody request: ConnexionRequest): AuthResponse =
        authService.connecter(request)
}
