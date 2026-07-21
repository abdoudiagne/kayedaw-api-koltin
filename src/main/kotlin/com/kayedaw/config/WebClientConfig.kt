package com.kayedaw.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ CLIENT HTTP SORTANT — WebClient (non bloquant)                          │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * POURQUOI WEBCLIENT ET PAS RESTTEMPLATE ?
 * RestTemplate est en maintenance depuis Spring 5 : pas de nouvelles
 * fonctionnalités. WebClient est non bloquant et, associé aux coroutines
 * Kotlin, s'écrit de façon parfaitement séquentielle (`awaitBody()`) tout en
 * libérant le thread pendant l'attente réseau.
 *
 * RÈGLE D'OR À CITER EN ENTRETIEN : un client HTTP SANS TIMEOUT est un
 * incident de production en attente. Si le service distant ralentit, les
 * connexions s'accumulent et l'application entière se bloque (effet domino).
 * On configure donc systématiquement : timeout de connexion, de lecture,
 * de réponse globale, et une taille de pool bornée.
 */
@Configuration
class WebClientConfig(private val proprietes: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun webClient(): WebClient {
        val timeouts = proprietes.httpClient

        // Pool de connexions borné : évite l'épuisement des ressources
        val poolConnexions = ConnectionProvider.builder("kayedaw-pool")
            .maxConnections(proprietes.httpClient.maxConnexions)
            .pendingAcquireTimeout(Duration.ofSeconds(5))
            .maxIdleTime(Duration.ofSeconds(30))
            .build()

        val httpClient = HttpClient.create(poolConnexions)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeouts.connexion.toMillis().toInt())
            .responseTimeout(timeouts.reponse)                     // timeout global de la réponse
            .doOnConnected { connexion ->
                connexion.addHandlerLast(
                    ReadTimeoutHandler(timeouts.lecture.toMillis(), TimeUnit.MILLISECONDS))
            }

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            // Garde-fou : une réponse anormalement volumineuse ne doit pas saturer la mémoire
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .filter(journalisation())
            .build()
    }

    /**
     * ExchangeFilterFunction = équivalent sortant d'un filtre servlet.
     * Utile pour tracer, propager un correlation-id, ou injecter un jeton.
     */
    private fun journalisation() = ExchangeFilterFunction.ofRequestProcessor { requete ->
        log.debug("appel sortant : {} {}", requete.method(), requete.url())
        Mono.just(requete)
    }
}
