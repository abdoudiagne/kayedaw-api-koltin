package com.kayedaw.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ PATTERN — Chain of Responsibility                                       │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * La sécurité Spring est une chaîne de filtres. Ce filtre s'insère AVANT
 * UsernamePasswordAuthenticationFilter : il lit l'en-tête Authorization,
 * valide le jeton et alimente le SecurityContext.
 *
 * `OncePerRequestFilter` garantit une seule exécution par requête, même en
 * cas de forward interne.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val utilisateurDetailsService: UtilisateurDetailsService
) : OncePerRequestFilter() {

    companion object {
        private const val ENTETE = "Authorization"
        private const val PREFIXE = "Bearer "
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val entete = request.getHeader(ENTETE)

        // Pas de jeton : on laisse passer. Spring Security refusera plus loin
        // si la route est protégée. Ce filtre n'a pas à décider de l'accès.
        if (entete == null || !entete.startsWith(PREFIXE)) {
            filterChain.doFilter(request, response)
            return
        }

        val token = entete.removePrefix(PREFIXE)

        // Combinaison null safety + let : le bloc ne s'exécute que si tout est non-null
        jwtService.extraireEmail(token)
            ?.takeIf { SecurityContextHolder.getContext().authentication == null }
            ?.let { email ->
                /*
                 * ⚠️ Le jeton peut désigner un compte qui n'existe PLUS : supprimé
                 * par un administrateur alors que son porteur avait encore un jeton
                 * valide (validité 1 h). `loadUserByUsername` lève alors une
                 * UsernameNotFoundException qui, non capturée, remonte en 500.
                 *
                 * On l'avale : le contexte reste anonyme, la chaîne de sécurité
                 * refuse la requête, et le client reçoit un 401 — le statut correct
                 * pour « je ne sais plus qui vous êtes ».
                 */
                val details = runCatching { utilisateurDetailsService.loadUserByUsername(email) }
                    .getOrNull()

                if (details != null && jwtService.estValide(token, details.username)) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(details, null, details.authorities).apply {
                            this.details = WebAuthenticationDetailsSource().buildDetails(request)
                        }
                }
            }

        filterChain.doFilter(request, response)
    }
}
