package com.kayedaw.meteo

import com.kayedaw.config.AppProperties
import com.kayedaw.config.WebClientConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ TESTER UN APPEL SORTANT — MockWebServer                                 │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * On ne mocke PAS WebClient : on lance un vrai serveur HTTP local (OkHttp
 * MockWebServer) et on teste la chaîne complète — sérialisation, en-têtes,
 * codes d'erreur, retry, timeouts. Bien plus fiable qu'un mock du client,
 * qui ne testerait que notre propre code.
 *
 * `runTest` permet d'appeler directement les fonctions `suspend`.
 */
class MeteoClientTest {

    private lateinit var serveur: MockWebServer
    private lateinit var client: MeteoClient

    private val coordonnees = Coordonnees("Lille", 50.63, 3.06)
    private val date: LocalDate = LocalDate.parse("2026-07-19")

    @BeforeEach
    fun demarrer() {
        serveur = MockWebServer().apply { start() }
        val base = serveur.url("/").toString().trimEnd('/')

        val proprietes = AppProperties(
            jwt = AppProperties.Jwt("c2VjcmV0", Duration.ofHours(1)),
            entrainement = AppProperties.Entrainement(80.0, 200.0),
            meteo = AppProperties.Meteo(
                urlGeocodage = "$base/geocodage",
                urlArchive = "$base/archive",
                urlQualiteAir = "$base/air",
                tentatives = 2,
                delaiEntreTentatives = Duration.ofMillis(20)   // rapide en test
            ),
            meteoFrance = AppProperties.MeteoFrance(),
        httpClient = AppProperties.HttpClient(
                connexion = Duration.ofMillis(500),
                lecture = Duration.ofMillis(800),
                reponse = Duration.ofMillis(800),
                maxConnexions = 10
            )
        )
        client = MeteoClient(WebClientConfig(proprietes).webClient(), proprietes)
    }

    @AfterEach
    fun arreter() = serveur.shutdown()

    private fun repondre(json: String, code: Int = 200) = serveur.enqueue(
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(json)
    )

    // ───────────────────── Cas nominaux ─────────────────────
    @Test
    fun `geocode une ville en coordonnees`() = runTest {
        repondre("""{"results":[{"name":"Lille","latitude":50.63,"longitude":3.06,"country":"France"}]}""")

        val resultat = client.coordonnees("Lille")

        assertThat(resultat).isNotNull
        assertThat(resultat!!.ville).isEqualTo("Lille")
        assertThat(resultat.latitude).isEqualTo(50.63)

        val requete = serveur.takeRequest()
        assertThat(requete.path).contains("/geocodage").contains("name=Lille")
    }

    @Test
    fun `retourne null quand la ville est inconnue`() = runTest {
        repondre("""{"results":null}""")

        assertThat(client.coordonnees("Zzzzville")).isNull()
    }

    @Test
    fun `recupere la meteo du jour`() = runTest {
        repondre("""{"daily":{"time":["2026-07-19"],"temperature_2m_max":[27.4],
            "temperature_2m_min":[15.1],"precipitation_sum":[0.0],"wind_speed_10m_max":[18.5]}}""")

        val meteo = client.meteoDuJour(coordonnees, date)

        assertThat(meteo?.daily?.temperature_2m_max?.first()).isEqualTo(27.4)
        assertThat(meteo?.daily?.wind_speed_10m_max?.first()).isEqualTo(18.5)
    }

    @Test
    fun `ignore les champs inconnus du fournisseur`() = runTest {
        // Le fournisseur ajoute un champ : la désérialisation ne doit pas casser
        repondre("""{"daily":{"time":["2026-07-19"],"temperature_2m_max":[21.0],
            "nouveau_champ_inattendu":"valeur","autre":{"imbrique":true}},"metadata":{"v":2}}""")

        val meteo = client.meteoDuJour(coordonnees, date)

        assertThat(meteo?.daily?.temperature_2m_max?.first()).isEqualTo(21.0)
    }

    // ───────────────────── Résilience ─────────────────────
    @Test
    fun `reessaye apres une erreur serveur puis reussit`() = runTest {
        repondre("", code = 503)                                   // 1re tentative : échec
        repondre("""{"daily":{"temperature_2m_max":[19.0]}}""")    // 2e tentative : succès

        val meteo = client.meteoDuJour(coordonnees, date)

        assertThat(meteo?.daily?.temperature_2m_max?.first()).isEqualTo(19.0)
        assertThat(serveur.requestCount).isEqualTo(2)              // le retry a bien eu lieu
    }

    @Test
    fun `ne reessaye pas sur une erreur 4xx`() = runTest {
        repondre("""{"erreur":"parametre invalide"}""", code = 400)

        val meteo = client.meteoDuJour(coordonnees, date)

        assertThat(meteo).isNull()                                  // repli
        assertThat(serveur.requestCount).isEqualTo(1)               // une seule tentative
    }

    @Test
    fun `retourne null quand le service reste en erreur`() = runTest {
        repeat(3) { repondre("", code = 500) }

        assertThat(client.meteoDuJour(coordonnees, date)).isNull()  // aucune exception propagée
    }

    @Test
    fun `retourne null en cas de timeout`() = runTest {
        // Le serveur ne répond jamais : le timeout de lecture doit s'appliquer
        serveur.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        serveur.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        serveur.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertThat(client.meteoDuJour(coordonnees, date)).isNull()
    }

    @Test
    fun `retourne null si la connexion est coupee`() = runTest {
        repeat(3) { serveur.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }

        assertThat(client.coordonnees("Lille")).isNull()
    }

    // ───────────────────── Classification des erreurs ─────────────────────
    @Test
    fun `classe correctement les erreurs transitoires`() {
        val erreur500 = org.springframework.web.reactive.function.client.WebClientResponseException
            .create(500, "erreur serveur", org.springframework.http.HttpHeaders.EMPTY, ByteArray(0), null)
        val erreur404 = org.springframework.web.reactive.function.client.WebClientResponseException
            .create(404, "absent", org.springframework.http.HttpHeaders.EMPTY, ByteArray(0), null)

        assertThat(client.estTransitoire(erreur500)).isTrue()
        assertThat(client.estTransitoire(erreur404)).isFalse()
        assertThat(client.estTransitoire(java.net.SocketTimeoutException("timeout"))).isTrue()
    }
}
