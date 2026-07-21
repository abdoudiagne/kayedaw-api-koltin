package com.kayedaw.common

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.boot.jackson.JsonComponent

/**
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │ NORMALISATION DES CHAÎNES À L'ENTRÉE                                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * Toute chaîne reçue en JSON est nettoyée de ses espaces de bord, AVANT que
 * Bean Validation ne s'exécute.
 *
 * Pourquoi ici et pas dans chaque service : un copier-coller d'email ramène
 * très souvent une espace finale. Sans ce module, « user@kayedaw.fr » suivi
 * d'une espace échouait sur @Email et renvoyait un 400 sec, incompréhensible
 * pour l'utilisateur qui voit une adresse parfaitement valide à l'écran.
 *
 * On ne convertit PAS une chaîne vide en null : @NotBlank doit continuer de
 * produire son message métier plutôt qu'un « champ manquant ».
 */
@JsonComponent
class NettoyageChaines : SimpleModule() {

    init {
        addDeserializer(String::class.java, object : JsonDeserializer<String>() {
            override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String =
                p.valueAsString.trim()
        })
    }
}
