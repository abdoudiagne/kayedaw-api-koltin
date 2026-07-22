package com.kayedaw.common

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ UN TABLEAU EN PDF, SANS BIBLIOTHÈQUE DE MISE EN PAGE                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * PDFBox ne connaît ni tableau ni saut de ligne : il dessine du texte à des
 * coordonnées, l'origine étant en BAS à gauche. Tout ce qui suit — pagination,
 * en-tête répété, troncature des cellules — est donc écrit ici, une fois, pour
 * que les contrôleurs n'aient qu'à fournir des lignes.
 *
 * C'est le prix d'une licence Apache 2.0 : iText propose des tableaux tout
 * faits mais sous AGPL, ce qui obligerait à publier le code appelant.
 *
 * ⚠️ POLICE ET ACCENTS. Les polices Standard 14 embarquent l'encodage
 * WinAnsi, qui couvre le français — mais PAS au-delà. Un caractère absent fait
 * lever `IllegalArgumentException` À L'ÉCRITURE, donc au milieu d'un document
 * à moitié construit : une ville chinoise ou cyrillique dans un nom de séance
 * suffirait. `assainir()` les remplace en amont plutôt que de laisser un
 * export échouer sur une donnée que l'utilisateur a le droit de saisir.
 */
object DocumentPdf {

    private const val MARGE = 40f
    private const val HAUTEUR_LIGNE = 16f
    private const val TAILLE_TEXTE = 9f
    private const val TAILLE_ENTETE = 10f

    private val NORMALE = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val GRASSE = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private val HORODATAGE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm")

    /** Une colonne : son intitulé et sa largeur relative. */
    data class Colonne(val titre: String, val poids: Float)

    /**
     * Construit le document et rend ses octets.
     *
     * `use` sur le PDDocument : sans fermeture, PDFBox garde des ressources
     * natives et le tas grimpe à chaque export.
     */
    fun tableau(
        titre: String,
        sousTitre: String,
        colonnes: List<Colonne>,
        lignes: List<List<String>>
    ): ByteArray = PDDocument().use { document ->
        val largeurUtile = PDRectangle.A4.height - 2 * MARGE      // paysage : on échange
        val poidsTotal = colonnes.sumOf { it.poids.toDouble() }.toFloat()
        val largeurs = colonnes.map { largeurUtile * (it.poids / poidsTotal) }

        var page = nouvellePage(document)
        var flux = PDPageContentStream(document, page)
        var y = PDRectangle.A4.width - MARGE

        y = ecrireTitre(flux, titre, sousTitre, y)
        y = ecrireEntete(flux, colonnes, largeurs, y)

        for (ligne in lignes) {
            /*
             * Saut de page AVANT d'écrire, jamais après : tester la place
             * restante une fois la ligne posée laisse la dernière déborder
             * sous la marge, là où PDFBox n'avertit de rien.
             */
            if (y < MARGE + HAUTEUR_LIGNE) {
                flux.close()
                page = nouvellePage(document)
                flux = PDPageContentStream(document, page)
                y = PDRectangle.A4.width - MARGE
                // L'en-tête est REPÉTÉ : une page 2 sans intitulés de colonnes
                // n'est qu'une grille de valeurs sans signification.
                y = ecrireEntete(flux, colonnes, largeurs, y)
            }
            y = ecrireLigne(flux, ligne, largeurs, y)
        }

        if (lignes.isEmpty()) {
            texte(flux, NORMALE, TAILLE_TEXTE, MARGE, y - HAUTEUR_LIGNE, "Aucune donnée à exporter.")
        }

        flux.close()

        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    /** Horodatage à afficher en sous-titre : un export sans date ne vaut rien. */
    fun horodatage(instant: LocalDateTime): String = "Export du ${instant.format(HORODATAGE)}"

    // ─────────────────────────── Dessin ───────────────────────────

    /**
     * A4 en PAYSAGE : un tableau de cinq à sept colonnes est illisible en
     * portrait, les cellules y étant tronquées à quelques caractères.
     */
    private fun nouvellePage(document: PDDocument) =
        PDPage(PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width))
            .also { document.addPage(it) }

    private fun ecrireTitre(
        flux: PDPageContentStream, titre: String, sousTitre: String, y: Float
    ): Float {
        texte(flux, GRASSE, 16f, MARGE, y - 12f, assainir(titre))
        texte(flux, NORMALE, 9f, MARGE, y - 28f, assainir(sousTitre))
        return y - 48f
    }

    private fun ecrireEntete(
        flux: PDPageContentStream, colonnes: List<Colonne>, largeurs: List<Float>, y: Float
    ): Float {
        var x = MARGE
        colonnes.forEachIndexed { i, colonne ->
            texte(flux, GRASSE, TAILLE_ENTETE, x, y, tronquer(colonne.titre, largeurs[i], GRASSE, TAILLE_ENTETE))
            x += largeurs[i]
        }
        // Filet sous l'en-tête : sépare les intitulés des données sans cadre
        flux.moveTo(MARGE, y - 5f)
        flux.lineTo(PDRectangle.A4.height - MARGE, y - 5f)
        flux.stroke()
        return y - HAUTEUR_LIGNE - 4f
    }

    private fun ecrireLigne(
        flux: PDPageContentStream, cellules: List<String>, largeurs: List<Float>, y: Float
    ): Float {
        var x = MARGE
        cellules.forEachIndexed { i, cellule ->
            val largeur = largeurs.getOrElse(i) { 60f }
            texte(flux, NORMALE, TAILLE_TEXTE, x, y, tronquer(cellule, largeur, NORMALE, TAILLE_TEXTE))
            x += largeur
        }
        return y - HAUTEUR_LIGNE
    }

    private fun texte(
        flux: PDPageContentStream, police: PDType1Font, taille: Float, x: Float, y: Float, valeur: String
    ) {
        flux.beginText()
        flux.setFont(police, taille)
        flux.newLineAtOffset(x, y)
        flux.showText(valeur)
        flux.endText()
    }

    /**
     * Coupe une cellule à la largeur de sa colonne.
     *
     * Sans cela, un commentaire de 500 caractères déborde sur les colonnes
     * suivantes et rend la ligne entière illisible — PDFBox n'ayant aucune
     * notion de boîte, rien ne l'arrête au bord.
     */
    private fun tronquer(valeur: String, largeur: Float, police: PDType1Font, taille: Float): String {
        val propre = assainir(valeur)
        val disponible = largeur - 6f            // gouttière entre colonnes
        if (largeurTexte(propre, police, taille) <= disponible) {
            return propre
        }
        var coupe = propre
        while (coupe.isNotEmpty() && largeurTexte("$coupe…", police, taille) > disponible) {
            coupe = coupe.dropLast(1)
        }
        return "$coupe…"
    }

    private fun largeurTexte(valeur: String, police: PDType1Font, taille: Float): Float =
        police.getStringWidth(valeur) / 1000f * taille

    /**
     * Remplace tout caractère hors WinAnsi.
     *
     * ⚠️ Ce n'est pas de la cosmétique : `showText` LÈVE sur un caractère non
     * encodable, et l'export entier échoue alors sur une seule ville étrangère.
     * Mieux vaut un point d'interrogation dans une cellule qu'un 500 sur un
     * document de quarante lignes.
     */
    private fun assainir(valeur: String): String = buildString {
        for (caractere in valeur) {
            append(if (encodable(caractere)) caractere else '?')
        }
    }

    private fun encodable(caractere: Char): Boolean = try {
        NORMALE.getStringWidth(caractere.toString())
        true
    } catch (_: Exception) {
        false
    }
}
