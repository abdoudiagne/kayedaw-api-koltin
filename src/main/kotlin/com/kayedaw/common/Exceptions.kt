package com.kayedaw.common

/**
 * Exceptions métier — utilisées pour les cas VRAIMENT exceptionnels
 * (ressource absente, accès interdit), par opposition aux cas métier
 * normaux modélisés par la sealed interface ResultatCreationSeance.
 *
 * Les deux approches coexistent volontairement dans ce projet : c'est un bon
 * sujet de discussion en entretien (« quand utiliser l'un ou l'autre ? »).
 */
class SeanceIntrouvableException(id: Long) : RuntimeException("séance introuvable : $id")

class EmailDejaUtiliseException(email: String) : RuntimeException("email déjà utilisé : $email")

class AccesRefuseException(message: String = "accès refusé à cette ressource") : RuntimeException(message)
