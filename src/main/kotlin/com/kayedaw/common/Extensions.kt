package com.kayedaw.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ QUESTION 1.5 — Qu'est-ce qu'une fonction d'extension ?                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Elle ajoute une méthode à une classe qu'on ne possède pas, sans héritage.
 *
 * SOUS LE CAPOT : ce n'est PAS une modification de la classe. Le compilateur
 * génère une méthode STATIQUE prenant le receveur en premier paramètre :
 *
 *     public static double arrondi2(double $this)
 *
 * Conséquence importante à citer en entretien : la résolution est STATIQUE,
 * il n'y a donc pas de polymorphisme. Si une classe possède déjà une méthode
 * du même nom, c'est la méthode membre qui gagne.
 */

/** Arrondit à 2 décimales (montants, distances, allures). */
fun Double.arrondi2(): Double = Math.round(this * 100.0) / 100.0

/** Retourne le lundi de la semaine de cette date. */
fun LocalDate.lundiDeLaSemaine(): LocalDate = this.with(DayOfWeek.MONDAY)

/** Retourne l'intervalle [lundi, dimanche] contenant cette date. */
fun LocalDate.semaine(): ClosedRange<LocalDate> {
    val lundi = lundiDeLaSemaine()
    return lundi..lundi.plusDays(6)
}

/**
 * Semaine calendaire contenant cet instant, bornée à la MINUTE.
 *
 * Depuis que les séances portent une heure, comparer des `LocalDate` ne suffit
 * plus : il faut du lundi 00:00 au dimanche 23:59:59.999 inclus, sinon les
 * séances du dimanche après-midi sortent du calcul hebdomadaire.
 */
fun LocalDateTime.semaineComplete(): ClosedRange<LocalDateTime> {
    val lundi = toLocalDate().lundiDeLaSemaine().atStartOfDay()
    return lundi..lundi.plusDays(7).minusNanos(1)
}

/** Validation lisible, utilisée dans les objets-valeur. */
fun String.estEmailValide(): Boolean = matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

/**
 * Extension sur un type nullable : possible car le receveur peut être null.
 * Démontre la null safety appliquée aux extensions.
 */
fun String?.ouSiVide(defaut: String): String = if (this.isNullOrBlank()) defaut else this
