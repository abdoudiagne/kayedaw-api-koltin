package com.kayedaw.meteo

import java.text.Normalizer
import java.util.Locale

/** Un pays tel que proposé à l'utilisateur : code ISO et libellé français. */
data class PaysDto(val code: String, val nom: String)

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ RÉFÉRENTIEL DES PAYS — fourni par le JDK, pas écrit à la main           │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * `Locale.getISOCountries()` donne les 249 codes ISO 3166, et
 * `getDisplayCountry(FRENCH)` leur libellé français. Aucune table à maintenir,
 * aucune faute d'orthographe possible, et les noms suivent les mises à jour de
 * CLDR livrées avec le JDK.
 *
 * ⚠️ POURQUOI LE CODE PLUTÔT QUE LE NOM.
 *
 * Le géocodage filtrait d'abord sur le NOM du pays renvoyé par Open-Meteo,
 * comparé sans accents ni casse. Cela fonctionne — c'est vérifié pour la France
 * et le Sénégal — mais reste fragile : rien ne garantit qu'Open-Meteo nomme
 * « États-Unis » exactement comme le JDK. Le code ISO, lui, est le paramètre
 * que l'API attend (`countryCode`) : exact, insensible à la langue, sans
 * rapprochement de chaînes.
 *
 * Le nom reste ce qui est STOCKÉ et AFFICHÉ (« Sénégal » se lit, « SN » non) ;
 * le code est retrouvé ici au moment d'interroger le géocodeur.
 */
object Pays {

    /** Trié sur le libellé, et selon les règles du français (é après e). */
    val TOUS: List<PaysDto> = Locale.getISOCountries()
        .map { PaysDto(it, Locale.of("", it).getDisplayCountry(Locale.FRENCH)) }
        .filter { it.nom.isNotBlank() && it.nom != it.code }
        .sortedWith(compareBy(java.text.Collator.getInstance(Locale.FRENCH)) { it.nom })

    private val PAR_NOM: Map<String, String> =
        TOUS.associate { normaliser(it.nom) to it.code }

    /**
     * Nom de pays -> code ISO. Renvoie null si le nom est inconnu : on préfère
     * ne pas géocoder plutôt que de retomber silencieusement sur la France et
     * de rendre la météo d'un autre continent.
     */
    fun code(nom: String?): String? = PAR_NOM[normaliser(nom)]

    /** Compare sans accents ni casse : « Senegal » doit trouver « Sénégal ». */
    private fun normaliser(valeur: String?): String =
        Normalizer.normalize(valeur.orEmpty(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .trim()
}
