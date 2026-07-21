package com.kayedaw.security

import com.kayedaw.config.AppProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class JwtServiceTest {

    private val secret = "ZmFrZS1zZWNyZXQtZGUtdGVzdC1hLXJlbXBsYWNlci0zMi1vY3RldHMtbWluaW11bQ=="

    private fun serviceAvec(validite: Duration) = JwtService(
        AppProperties(
            jwt = AppProperties.Jwt(secret, validite),
            entrainement = AppProperties.Entrainement(80.0, 200.0),
            meteo = AppProperties.Meteo("http://x/geo", "http://x/arch", "http://x/air", "http://x/prev", 2, Duration.ofMillis(10)),
            meteoFrance = AppProperties.MeteoFrance(),
        httpClient = AppProperties.HttpClient(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 10)
        )
    )

    private val service = serviceAvec(Duration.ofHours(1))

    @Test
    fun `genere un token contenant l email et le role`() {
        val token = service.genererToken("abdou@test.fr", "USER")

        assertThat(service.extraireEmail(token)).isEqualTo("abdou@test.fr")
        assertThat(service.extraireRole(token)).isEqualTo("USER")
        assertThat(service.estValide(token, "abdou@test.fr")).isTrue()
    }

    @Test
    fun `rejette un token appartenant a un autre utilisateur`() {
        val token = service.genererToken("abdou@test.fr", "USER")
        assertThat(service.estValide(token, "autre@test.fr")).isFalse()
    }

    @Test
    fun `rejette un token expire`() {
        val expire = serviceAvec(Duration.ofSeconds(-10))
        val token = expire.genererToken("abdou@test.fr", "USER")

        assertThat(expire.estValide(token, "abdou@test.fr")).isFalse()
    }

    @Test
    fun `rejette un token signe avec une autre cle`() {
        val autre = JwtService(
            AppProperties(
                AppProperties.Jwt("YXV0cmUtc2VjcmV0LWRlLXRlc3QtcXVpLWZhaXQtMzItb2N0ZXRzLW1pbmltdW0=", Duration.ofHours(1)),
                AppProperties.Entrainement(80.0, 200.0),
                AppProperties.Meteo("http://x/geo", "http://x/arch", "http://x/air", "http://x/prev", 2, Duration.ofMillis(10)),
                AppProperties.MeteoFrance(),
                AppProperties.HttpClient(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 10)
            )
        )
        val token = autre.genererToken("abdou@test.fr", "USER")

        assertThat(service.extraireEmail(token)).isNull()
        assertThat(service.estValide(token, "abdou@test.fr")).isFalse()
    }

    @Test
    fun `un token malforme retourne null sans lever d exception`() {
        // runCatching{}.getOrNull() : jamais de 500 sur un jeton invalide
        assertThat(service.extraireEmail("pas.un.jwt")).isNull()
        assertThat(service.extraireEmail("")).isNull()
    }
}
