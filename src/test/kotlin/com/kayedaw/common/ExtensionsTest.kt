package com.kayedaw.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

/**
 * Tests des fonctions d'extension — Kotlin pur, aucune dépendance Spring.
 * Exécution en millisecondes : c'est la base de la pyramide de tests.
 */
class ExtensionsTest {

    @Test
    fun `arrondi2 arrondit a deux decimales`() {
        assertThat((50.0 / 9.0).arrondi2()).isEqualTo(5.56)
        assertThat(8.991.arrondi2()).isEqualTo(8.99)
        assertThat(10.0.arrondi2()).isEqualTo(10.0)
    }

    @Test
    fun `semaine retourne du lundi au dimanche`() {
        val mercredi = LocalDate.parse("2026-07-22")
        val semaine = mercredi.semaine()

        assertThat(semaine.start).isEqualTo(LocalDate.parse("2026-07-20"))       // lundi
        assertThat(semaine.endInclusive).isEqualTo(LocalDate.parse("2026-07-26")) // dimanche
    }

    @Test
    fun `un lundi reste son propre debut de semaine`() {
        val lundi = LocalDate.parse("2026-07-20")
        assertThat(lundi.lundiDeLaSemaine()).isEqualTo(lundi)
    }

    // Test paramétré : plusieurs jeux de données, un seul test
    @ParameterizedTest
    @CsvSource(
        "abdou@test.fr, true",
        "a@b.co, true",
        "pas-un-email, false",
        "sans@point, false",
        "'', false"
    )
    fun `valide correctement les emails`(valeur: String, attendu: Boolean) {
        assertThat(valeur.estEmailValide()).isEqualTo(attendu)
    }

    @Test
    fun `ouSiVide gere null et chaine vide`() {
        val nul: String? = null
        assertThat(nul.ouSiVide("defaut")).isEqualTo("defaut")
        assertThat("   ".ouSiVide("defaut")).isEqualTo("defaut")
        assertThat("valeur".ouSiVide("defaut")).isEqualTo("valeur")
    }
}
