package com.kayedaw.meteo

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Le CSV de Météo-France est un format à pièges : séparateur `;`, virgule
 * décimale, colonnes très nombreuses et souvent VIDES. On teste donc l'analyse
 * comme une fonction pure — aucun réseau, aucun contexte Spring.
 */
class AnalyseurCsvDpclimTest {

    /** Extrait réel, réduit aux colonnes utiles. */
    private val csv = """
        POSTE;DATE;RR1;T;FF
        59343001;2026072006;0,0;13,7;2,6
        59343001;2026072012;0,2;19,4;4,1
        59343001;2026072018;1,3;21,2;3,0
        59343001;2026072022;0,0;16,5;1,4
    """.trimIndent()

    @Test
    fun `agrege la journee et retient la valeur de l heure de la seance`() {
        val obs = AnalyseurCsvDpclim.analyser(csv, LocalDateTime.parse("2026-07-20T18:30"))

        assertThat(obs).isNotNull
        assertThat(obs!!.temperatureMaxC).isEqualTo(21.2)
        assertThat(obs.temperatureMinC).isEqualTo(13.7)
        // 18 h 30 -> la ligne de 18 h, pas la première du fichier
        assertThat(obs.temperatureALHeureC).isEqualTo(21.2)
        assertThat(obs.precipitationMm).isEqualTo(1.5)      // cumul de la journée
        // FF est en m/s dans le fichier : 4,1 m/s = 14,76 km/h
        assertThat(obs.ventMaxKmH).isCloseTo(14.76, within(0.01))
    }

    @Test
    fun `choisit l heure la plus proche quand l heure exacte est absente`() {
        val obs = AnalyseurCsvDpclim.analyser(csv, LocalDateTime.parse("2026-07-20T11:00"))

        assertThat(obs?.temperatureALHeureC).isEqualTo(19.4)   // ligne de 12 h
    }

    @Test
    fun `tolere les cellules vides`() {
        val partiel = "POSTE;DATE;RR1;T;FF\n59343001;2026072006;;;\n59343001;2026072012;;18,0;"
        val obs = AnalyseurCsvDpclim.analyser(partiel, LocalDateTime.parse("2026-07-20T12:00"))

        assertThat(obs?.temperatureMaxC).isEqualTo(18.0)
        assertThat(obs?.ventMaxKmH).isNull()
        assertThat(obs?.precipitationMm).isNull()
    }

    @Test
    fun `retourne null sur un fichier sans donnee`() {
        assertThat(AnalyseurCsvDpclim.analyser("POSTE;DATE;T", LocalDateTime.now())).isNull()
        assertThat(AnalyseurCsvDpclim.analyser("", LocalDateTime.now())).isNull()
    }

    @Test
    fun `deduit le departement du code postal`() {
        assertThat(departementDepuisCodePostal("59000")).isEqualTo("59")
        assertThat(departementDepuisCodePostal("75001")).isEqualTo("75")
        // Outre-mer : trois chiffres, sinon on viserait le département 97
        assertThat(departementDepuisCodePostal("97400")).isEqualTo("974")
        assertThat(departementDepuisCodePostal("20000")).isEqualTo("20")
        // Cas réel : le géocodage renvoie « 69061 CEDEX 06 » pour Lyon
        assertThat(departementDepuisCodePostal("69061 CEDEX 06")).isEqualTo("69")
        assertThat(departementDepuisCodePostal("13001 CEDEX 20")).isEqualTo("13")
        assertThat(departementDepuisCodePostal(null)).isNull()
        assertThat(departementDepuisCodePostal("abc")).isNull()
        assertThat(departementDepuisCodePostal("7")).isNull()
    }
}
