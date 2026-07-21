package com.kayedaw.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Démontre l'intérêt des sealed interfaces : le `when` ci-dessous est
 * EXHAUSTIF sans `else`. Si un cas est ajouté à ResultatCreationSeance,
 * ce test NE COMPILE PLUS — l'oubli devient impossible.
 */
class ResultatTest {

    private fun versStatutHttp(r: ResultatCreationSeance): Int = when (r) {
        is ResultatCreationSeance.Creee -> 201
        is ResultatCreationSeance.PlafondHebdoDepasse -> 422
        is ResultatCreationSeance.DateTropLointaine -> 422
    }

    @Test
    fun `chaque cas metier est traduit en statut HTTP`() {
        assertThat(versStatutHttp(ResultatCreationSeance.Creee(1L))).isEqualTo(201)
        assertThat(versStatutHttp(ResultatCreationSeance.PlafondHebdoDepasse(85.0, 80.0))).isEqualTo(422)
        assertThat(versStatutHttp(ResultatCreationSeance.DateTropLointaine(14))).isEqualTo(422)
    }

    @Test
    fun `le depassement est calcule par la propriete derivee`() {
        val refus = ResultatCreationSeance.PlafondHebdoDepasse(volumeCalculeKm = 92.5, plafondKm = 80.0)
        assertThat(refus.depassementKm).isEqualTo(12.5)
    }

    @Test
    fun `le refus porte l horizon de planification`() {
        val refus = ResultatCreationSeance.DateTropLointaine(horizonJours = 14)
        assertThat(refus.horizonJours).isEqualTo(14)
        assertThat(refus).isEqualTo(ResultatCreationSeance.DateTropLointaine(14))
    }

    @Test
    fun `data class fournit equals et copy`() {
        val a = ResultatCreationSeance.PlafondHebdoDepasse(85.0, 80.0)
        assertThat(a).isEqualTo(ResultatCreationSeance.PlafondHebdoDepasse(85.0, 80.0))
        assertThat(a.copy(volumeCalculeKm = 90.0).depassementKm).isEqualTo(10.0)
    }
}
