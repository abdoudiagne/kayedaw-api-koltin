package com.kayedaw.meteo

import com.kayedaw.config.AppProperties
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Instant

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ OAUTH2 client_credentials — jeton mis en cache et renouvelé seul        │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Le portail Météo-France délivre un jeton valable 1 h. Trois pièges classiques,
 * traités ici :
 *
 *  1. DEMANDER UN JETON À CHAQUE APPEL — c'est un aller-retour réseau inutile
 *     sur chaque requête, et le portail finit par appliquer du rate-limiting.
 *     → On garde le jeton en mémoire jusqu'à son expiration.
 *
 *  2. ATTENDRE L'EXPIRATION EXACTE — entre la vérification et l'usage, le jeton
 *     peut expirer en vol (horloges désynchronisées, requête lente) et l'appel
 *     part avec un jeton mort.
 *     → On renouvelle avec une MARGE (5 min par défaut).
 *
 *  3. LA RUÉE (« thundering herd ») — si dix coroutines constatent en même temps
 *     que le jeton est périmé, dix demandes partent simultanément.
 *     → Un `Mutex` sérialise le renouvellement, et on RE-VÉRIFIE une fois le
 *       verrou obtenu (double-checked locking) : les neuf autres trouvent alors
 *       le jeton déjà rafraîchi et n'appellent rien.
 *
 * `Mutex` de kotlinx.coroutines et non `synchronized` : il SUSPEND la coroutine
 * en attente au lieu de bloquer le thread porteur.
 */
@Component
class JetonMeteoFranceService(
    private val webClient: WebClient,
    private val proprietes: AppProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config get() = proprietes.meteoFrance

    /** État du cache : la valeur et son instant d'expiration effectif. */
    private data class JetonEnCache(val valeur: String, val expireLe: Instant)

    @Volatile
    private var cache: JetonEnCache? = null
    private val verrou = Mutex()

    /**
     * Retourne un jeton valide, en le renouvelant si nécessaire.
     * Retourne null si l'intégration est désactivée ou si le portail échoue :
     * l'appelant retombe alors sur Open-Meteo.
     */
    suspend fun jeton(): String? {
        if (!config.actif) {
            return null
        }

        cacheValide()?.let { return it }

        return verrou.withLock {
            // Re-vérification sous verrou : une autre coroutine a pu renouveler
            cacheValide() ?: renouveler()
        }
    }

    private fun cacheValide(): String? =
        cache?.takeIf { Instant.now().isBefore(it.expireLe) }?.valeur

    private suspend fun renouveler(): String? = try {
        val reponse = webClient.post()
            .uri(config.urlToken)
            .header("Authorization", "Basic ${config.applicationId}")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromValue(
                LinkedMultiValueMap<String, String>().apply { add("grant_type", "client_credentials") }))
            .retrieve()
            .awaitBody<ReponseJeton>()

        val expiration = Instant.now()
            .plusSeconds(reponse.expires_in)
            .minus(config.margeRenouvellement)

        cache = JetonEnCache(reponse.access_token, expiration)
        log.info("jeton Météo-France renouvelé, valide {} s", reponse.expires_in)
        reponse.access_token
    } catch (erreur: Exception) {
        // Jamais d'exception vers l'appelant : la météo est un confort
        log.warn("obtention du jeton Météo-France impossible ({})", erreur.message)
        cache = null
        null
    }
}

/** Réponse standard du point d'entrée OAuth2. */
data class ReponseJeton(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long = 3600
)
