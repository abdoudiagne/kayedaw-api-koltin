package com.kayedaw.seance

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface SeanceRepository : JpaRepository<Seance, Long> {

    fun findByUtilisateurId(utilisateurId: Long, pageable: Pageable): Page<Seance>

    fun findByUtilisateurIdOrderByDateHeureAsc(utilisateurId: Long): List<Seance>

    /** Suppression en masse : utilisée quand un admin supprime un compte. */
    fun deleteByUtilisateurId(utilisateurId: Long): Long

    fun findByUtilisateurIdAndDateHeureBetweenOrderByDateHeureAsc(
        utilisateurId: Long, debut: LocalDateTime, fin: LocalDateTime
    ): List<Seance>

    fun findByUtilisateurIdAndType(utilisateurId: Long, type: TypeSeance, pageable: Pageable): Page<Seance>

    /**
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ QUESTION 3.6 — Le problème N+1 et sa résolution                   │
     * └───────────────────────────────────────────────────────────────────┘
     *
     * Sans @EntityGraph : 1 requête pour les séances, puis 1 requête par
     * séance pour charger l'utilisateur lazy → N+1 requêtes.
     *
     * @EntityGraph demande à Hibernate de faire un JOIN FETCH : une seule
     * requête. Alternative : une requête JPQL avec `join fetch` explicite,
     * ou une projection DTO (voir volumeParType ci-dessous).
     *
     * Détection en production : log SQL (show-sql), ou APM type Datadog.
     */
    @EntityGraph(attributePaths = ["utilisateur"])
    fun findWithUtilisateurByUtilisateurId(utilisateurId: Long, pageable: Pageable): Page<Seance>

    /**
     * Recherche combinée : chaque critère est OPTIONNEL.
     *
     * Le motif `(:param is null or <condition>)` évite d'écrire une requête par
     * combinaison de filtres. Alternative pour des cas plus complexes : les
     * Specifications JPA (Criteria API), plus souples mais nettement plus verbeuses.
     *
     * ⚠️ Les parenthèses autour du bloc `recherche` sont indispensables :
     * sans elles, le OR happerait toutes les conditions précédentes et le filtre
     * ramènerait les séances des AUTRES utilisateurs.
     */
    @Query("""
        select s from Seance s
        where s.utilisateur.id = :userId
          and (:debut is null or s.dateHeure >= :debut)
          and (:fin is null or s.dateHeure <= :fin)
          and (:type is null or s.type = :type)
          and (:recherche is null
               or lower(s.commentaire) like lower(concat('%', :recherche, '%'))
               or lower(s.ville) like lower(concat('%', :recherche, '%')))
    """)
    fun rechercher(
        @Param("userId") userId: Long,
        @Param("debut") debut: LocalDateTime?,
        @Param("fin") fin: LocalDateTime?,
        @Param("type") type: TypeSeance?,
        @Param("recherche") recherche: String?,
        pageable: Pageable
    ): Page<Seance>

    /**
     * Projection : on ne remonte QUE les colonnes agrégées, aucune entité
     * n'est chargée en mémoire. La base fait le travail d'agrégation.
     */
    @Query("""
        select s.type as type, sum(s.distanceKm) as distance, count(s) as nombre
        from Seance s
        where s.utilisateur.id = :userId and s.dateHeure between :debut and :fin
          and s.dateHeure <= :maintenant
        group by s.type
    """)
    fun volumeParType(
        @Param("userId") userId: Long,
        @Param("debut") debut: LocalDateTime,
        @Param("fin") fin: LocalDateTime,
        /** Exclut les séances PLANIFIÉES : les stats ne comptent que le réalisé. */
        @Param("maintenant") maintenant: LocalDateTime
    ): List<VolumeParTypeProjection>
}

/** Projection d'interface : Spring Data génère l'implémentation. */
interface VolumeParTypeProjection {
    fun getType(): TypeSeance
    fun getDistance(): Double
    fun getNombre(): Long
}
