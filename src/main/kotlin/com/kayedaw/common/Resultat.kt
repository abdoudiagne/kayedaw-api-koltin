package com.kayedaw.common

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 1.6 — À quoi sert une sealed class / sealed interface ?        │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Hiérarchie FERMÉE, entièrement connue à la compilation.
 *
 * Bénéfice décisif : le `when` devient EXHAUSTIF et vérifié par le compilateur.
 * Si on ajoute demain un cas `QuotaAtteint`, tout `when` qui ne le traite pas
 * NE COMPILE PLUS. Impossible d'oublier un cas — contrairement à une chaîne
 * de `if/else` ou à un `switch` sur une String.
 *
 * POURQUOI ICI plutôt que des exceptions ?
 * Une exception sert à signaler l'ANORMAL. Or « le plafond hebdomadaire est
 * atteint » est un cas métier parfaitement NORMAL et prévu. Le modéliser dans
 * le type de retour le rend visible dans la signature de la fonction : le
 * développeur suivant ne peut pas l'ignorer.
 *
 * `data object` (Kotlin 1.9+) : singleton avec un toString() lisible, pour les
 * cas qui ne portent aucune donnée.
 */
sealed interface ResultatCreationSeance {

    /** Cas nominal : la séance a été créée. */
    data class Creee(val seanceId: Long) : ResultatCreationSeance

    /** Règle métier : le volume hebdomadaire dépasserait le plafond. */
    data class PlafondHebdoDepasse(
        val volumeCalculeKm: Double,
        val plafondKm: Double
    ) : ResultatCreationSeance {
        val depassementKm: Double get() = volumeCalculeKm - plafondKm
    }

    /**
     * Règle métier : on planifie jusqu'à un certain horizon, pas au-delà.
     *
     * Le cas a remplacé `DateDansLeFutur` : une séance FUTURE est désormais
     * légitime (elle est « planifiée »), seule une date trop lointaine est
     * refusée. Le compilateur a signalé chaque `when` à mettre à jour — c'est
     * exactement le bénéfice attendu d'une sealed interface.
     */
    data class DateTropLointaine(val horizonJours: Long) : ResultatCreationSeance
}
