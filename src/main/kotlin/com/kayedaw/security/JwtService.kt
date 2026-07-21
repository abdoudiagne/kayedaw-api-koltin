package com.kayedaw.security

import com.kayedaw.config.AppProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKey

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 6.1/6.2 — JWT : fonctionnement et limites                      │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Le jeton est SIGNÉ, pas chiffré : son contenu est lisible par quiconque.
 * → ne jamais y mettre de donnée sensible.
 *
 * Limites à citer : révocation impossible avant expiration (d'où des durées
 * courtes + refresh token), et le stockage côté client est un sujet en soi
 * (localStorage vulnérable au XSS, cookie httpOnly préférable).
 */
@Service
class JwtService(private val proprietes: AppProperties) {

    companion object {
        /** `const val` : vraie constante compile-time, inlinée à l'usage. */
        const val CLAIM_ROLE = "role"
    }

    /**
     * `by lazy` : initialisation paresseuse et thread-safe par défaut.
     * La clé n'est décodée qu'au premier usage, puis mémorisée.
     */
    private val cle: SecretKey by lazy {
        Keys.hmacShaKeyFor(Base64.getDecoder().decode(proprietes.jwt.secret))
    }

    fun genererToken(email: String, role: String): String {
        val maintenant = Date()
        return Jwts.builder()
            .subject(email)
            .claim(CLAIM_ROLE, role)
            .issuedAt(maintenant)
            .expiration(Date(maintenant.time + proprietes.jwt.validite.toMillis()))
            .signWith(cle)
            .compact()
    }

    /** Retourne null si le jeton est invalide : safe call à l'appel. */
    fun extraireEmail(token: String): String? = claims(token)?.subject

    fun extraireRole(token: String): String? = claims(token)?.get(CLAIM_ROLE, String::class.java)

    fun estValide(token: String, email: String): Boolean {
        // `?: return false` : elvis avec return — idiome Kotlin très courant
        val claims = claims(token) ?: return false
        return claims.subject == email && claims.expiration.after(Date())
    }

    /**
     * `runCatching { }.getOrNull()` : convertit une exception en null.
     * Un jeton expiré, mal signé ou malformé ne doit JAMAIS produire une 500 —
     * c'est un cas nominal côté sécurité, traité comme « non authentifié ».
     */
    private fun claims(token: String): Claims? = runCatching {
        Jwts.parser().verifyWith(cle).build().parseSignedClaims(token).payload
    }.getOrNull()

    fun dureeValiditeMs(): Long = proprietes.jwt.validite.toMillis()
}
