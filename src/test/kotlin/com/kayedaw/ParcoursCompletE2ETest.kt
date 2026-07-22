package com.kayedaw

import com.kayedaw.auth.AuthResponse
import com.kayedaw.auth.ConnexionRequest
import com.kayedaw.auth.InscriptionRequest
import com.kayedaw.common.InfosExecution
import com.kayedaw.seance.CreerSeanceRequest
import com.kayedaw.seance.SeanceResponse
import com.kayedaw.seance.StatistiquesResponse
import com.kayedaw.seance.TypeSeance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Sommet de la pyramide : traverse toutes les couches
 * (HTTP → sécurité → controller → service → JPA → H2).
 * Peu nombreux car lents, mais indispensables sur les scénarios critiques.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ParcoursCompletE2ETest(@Autowired private val restTemplate: TestRestTemplate) {

    private val hier: LocalDateTime = LocalDateTime.now().minusDays(1)

    private fun inscrire(): AuthResponse {
        val r = restTemplate.postForEntity(
            "/api/auth/inscription",
            InscriptionRequest("u-${UUID.randomUUID()}@test.fr", "motdepasse123", "Abdou", "Lille"),
            AuthResponse::class.java)
        assertThat(r.statusCode).isEqualTo(HttpStatus.CREATED)
        return requireNotNull(r.body)
    }

    private fun entete(token: String) = HttpHeaders().apply { setBearerAuth(token) }

    @Test
    fun `inscription puis connexion retournent un jeton exploitable`() {
        val inscription = inscrire()
        assertThat(inscription.token).isNotBlank()
        assertThat(inscription.role).isEqualTo("USER")

        val connexion = restTemplate.postForEntity(
            "/api/auth/connexion",
            ConnexionRequest(inscription.email, "motdepasse123"),
            AuthResponse::class.java)

        assertThat(connexion.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(connexion.body?.token).isNotBlank()
    }

    /**
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │ LE LIEU DU COMPTE VOYAGE EN ENTIER — ville ET pays                  │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * `AuthResponse` ne portait que `villeParDefaut`. Le client Angular
     * pré-remplit le formulaire de séance avec ce lieu : faute de pays, il
     * retombait sur son repli « France », et un compte sénégalais ouvrait
     * « Nouvelle séance » sur Dakar / France — introuvable au géocodage, donc
     * aucune suggestion et aucune météo, alors que le profil était juste.
     *
     * Les deux champs sont indissociables : une ville ne se géocode pas sans
     * son pays. Ce test le fige des DEUX côtés de l'authentification, une
     * inscription et une connexion n'empruntant pas le même chemin.
     */
    @Test
    fun `l authentification renvoie la ville ET le pays du compte`() {
        val email = "u-${UUID.randomUUID()}@test.fr"
        val inscription = restTemplate.postForEntity(
            "/api/auth/inscription",
            InscriptionRequest(email, "motdepasse123", "Coureur", "Dakar", "Sénégal"),
            AuthResponse::class.java)

        assertThat(inscription.body?.villeParDefaut).isEqualTo("Dakar")
        assertThat(inscription.body?.pays).isEqualTo("Sénégal")

        val connexion = restTemplate.postForEntity(
            "/api/auth/connexion",
            ConnexionRequest(email, "motdepasse123"),
            AuthResponse::class.java)

        assertThat(connexion.body?.villeParDefaut).isEqualTo("Dakar")
        assertThat(connexion.body?.pays).isEqualTo("Sénégal")
    }

    @Test
    fun `refuse la connexion avec un mauvais mot de passe`() {
        val u = inscrire()
        val r = restTemplate.postForEntity(
            "/api/auth/connexion", ConnexionRequest(u.email, "mauvais"), String::class.java)

        assertThat(r.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `refuse un email deja utilise`() {
        val u = inscrire()
        val r = restTemplate.postForEntity(
            "/api/auth/inscription",
            InscriptionRequest(u.email, "motdepasse123", "Doublon", "Lille"), String::class.java)

        assertThat(r.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `refuse l acces aux seances sans jeton`() {
        assertThat(restTemplate.getForEntity("/api/seances", String::class.java).statusCode)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `cycle CRUD complet avec un jeton valide`() {
        val token = inscrire().token

        // CREATE
        val creation = restTemplate.exchange(
            "/api/seances", HttpMethod.POST,
            HttpEntity(CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier), entete(token)),
            SeanceResponse::class.java)
        assertThat(creation.statusCode).isEqualTo(HttpStatus.CREATED)
        val id = requireNotNull(creation.body).id
        assertThat(creation.body!!.allureMinParKm).isEqualTo(5.0)
        assertThat(creation.body!!.intensite).isEqualTo("modérée")

        // READ
        assertThat(restTemplate.exchange("/api/seances/$id", HttpMethod.GET,
            HttpEntity<Void>(entete(token)), SeanceResponse::class.java).statusCode)
            .isEqualTo(HttpStatus.OK)

        // UPDATE
        val modif = restTemplate.exchange(
            "/api/seances/$id", HttpMethod.PUT,
            HttpEntity(mapOf(
                "type" to "FRACTIONNE", "distanceKm" to 8.0, "dureeMinutes" to 32,
                "dateHeure" to hier.toString(), "commentaire" to "sur piste"), entete(token)),
            SeanceResponse::class.java)
        assertThat(modif.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(modif.body?.allureMinParKm).isEqualTo(4.0)
        assertThat(modif.body?.intensite).isEqualTo("élevée")

        // DELETE
        assertThat(restTemplate.exchange("/api/seances/$id", HttpMethod.DELETE,
            HttpEntity<Void>(entete(token)), Void::class.java).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        // vérifie la disparition
        assertThat(restTemplate.exchange("/api/seances/$id", HttpMethod.GET,
            HttpEntity<Void>(entete(token)), String::class.java).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `un utilisateur ne peut pas acceder a la seance d un autre`() {
        val tokenA = inscrire().token
        val tokenB = inscrire().token

        val creation = restTemplate.exchange(
            "/api/seances", HttpMethod.POST,
            HttpEntity(CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier), entete(tokenA)),
            SeanceResponse::class.java)
        val id = requireNotNull(creation.body).id

        assertThat(restTemplate.exchange("/api/seances/$id", HttpMethod.GET,
            HttpEntity<Void>(entete(tokenB)), String::class.java).statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `refuse une seance depassant le plafond hebdomadaire`() {
        val token = inscrire().token

        /*
         * On ancre les deux séances sur la semaine PRÉCÉDENTE.
         *
         * Avec le lundi de la semaine en cours, `lundi.plusDays(2)` tombait
         * dans le futur du lundi au mardi : l'API refusait alors pour
         * DATE_FUTURE — un 422 lui aussi, donc le statut passait et seul le
         * motif était faux. Le test échouait deux jours sur sept.
         */
        val lundi = LocalDateTime.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY)

        restTemplate.exchange("/api/seances", HttpMethod.POST,
            HttpEntity(CreerSeanceRequest(TypeSeance.SORTIE_LONGUE, 75.0, 400, lundi), entete(token)),
            SeanceResponse::class.java)

        val refus = restTemplate.exchange("/api/seances", HttpMethod.POST,
            HttpEntity(CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, lundi.plusDays(2)), entete(token)),
            String::class.java)

        assertThat(refus.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(refus.body).contains("PLAFOND_HEBDOMADAIRE")
    }

    @Test
    fun `refuse une seance planifiee au-dela de l horizon`() {
        val token = inscrire().token

        // L'horizon vaut 30 jours : on vise nettement au-delà pour ne pas
        // jouer le test sur la borne elle-même, à la seconde près.
        val refus = restTemplate.exchange("/api/seances", HttpMethod.POST,
            HttpEntity(CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, LocalDateTime.now().plusDays(45)),
                entete(token)), String::class.java)

        assertThat(refus.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(refus.body).contains("DATE_TROP_LOINTAINE")
    }

    @Test
    fun `les statistiques agregent les seances de la periode`() {
        val token = inscrire().token
        listOf(
            CreerSeanceRequest(TypeSeance.ENDURANCE, 10.0, 50, hier.minusDays(10)),
            CreerSeanceRequest(TypeSeance.FRACTIONNE, 10.0, 40, hier.minusDays(3))
        ).forEach {
            restTemplate.exchange("/api/seances", HttpMethod.POST,
                HttpEntity(it, entete(token)), SeanceResponse::class.java)
        }

        val stats = restTemplate.exchange(
            "/api/seances/statistiques?debut=${hier.toLocalDate().minusDays(30)}&fin=${LocalDate.now()}",
            HttpMethod.GET, HttpEntity<Void>(entete(token)), StatistiquesResponse::class.java)

        assertThat(stats.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(stats.body?.nombreSeances).isEqualTo(2)
        assertThat(stats.body?.distanceTotaleKm).isEqualTo(20.0)
        assertThat(stats.body?.allureMoyenneMinParKm).isEqualTo(4.5)
    }

    @Test
    fun `un utilisateur simple ne peut pas acceder a l espace admin`() {
        val token = inscrire().token

        assertThat(restTemplate.exchange("/api/admin/utilisateurs", HttpMethod.GET,
            HttpEntity<Void>(entete(token)), String::class.java).statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `la requete est traitee par un thread virtuel Java 21`() {
        val token = inscrire().token

        val infos = restTemplate.exchange("/api/systeme/execution", HttpMethod.GET,
            HttpEntity<Void>(entete(token)), InfosExecution::class.java)

        assertThat(infos.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(infos.body?.versionJava).startsWith("21")
        assertThat(infos.body?.threadVirtuel).isTrue()
    }
}
