package com.kayedaw.seance

import com.kayedaw.common.CompteurRequetes
import com.kayedaw.common.ResultatCreationSeance
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.time.LocalDate

@RestController
@RequestMapping("/api/seances")
class SeanceController(
    private val service: SeanceService,
    private val statistiquesService: StatistiquesService,
    private val compteur: CompteurRequetes
) {

    /**
     * ┌───────────────────────────────────────────────────────────────────┐
     * │ QUESTION 1.6 (suite) — Le `when` exhaustif sur une sealed interface│
     * └───────────────────────────────────────────────────────────────────┘
     *
     * Le service retourne un ResultatCreationSeance. Le controller traduit
     * chaque cas en statut HTTP. Pas de `else` : le compilateur VÉRIFIE que
     * tous les cas sont traités. Si on ajoute demain un cas `QuotaAtteint`,
     * ce code ne compile plus tant qu'on ne l'a pas géré.
     *
     * C'est plus sûr qu'un try/catch : un cas métier oublié devient une
     * erreur de COMPILATION, pas un bug de production.
     */
    @PostMapping
    fun creer(
        @Valid @RequestBody request: CreerSeanceRequest,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): ResponseEntity<Any> {
        compteur.incrementer("POST /api/seances")

        return when (val resultat = service.creer(request, utilisateur.username)) {

            is ResultatCreationSeance.Creee -> {
                val seance = service.parId(resultat.seanceId, utilisateur.username)
                ResponseEntity
                    .created(URI.create("/api/seances/${resultat.seanceId}"))
                    .body(seance)                                            // 201
            }

            is ResultatCreationSeance.PlafondHebdoDepasse ->
                ResponseEntity.unprocessableEntity().body(                   // 422
                    RefusMetierResponse(
                        motif = "PLAFOND_HEBDOMADAIRE",
                        detail = "dépassement de %.1f km".format(resultat.depassementKm),
                        volumeCalculeKm = resultat.volumeCalculeKm,
                        plafondKm = resultat.plafondKm
                    )
                )

            is ResultatCreationSeance.DateTropLointaine ->
                ResponseEntity.unprocessableEntity().body(                   // 422
                    RefusMetierResponse(
                        motif = "DATE_TROP_LOINTAINE",
                        detail = "on ne planifie pas au-delà de ${resultat.horizonJours} jours"
                    )
                )
        }
    }

    @GetMapping("/{id}")
    fun parId(
        @PathVariable id: Long,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): SeanceResponse = service.parId(id, utilisateur.username)

    @GetMapping
    fun lister(
        @AuthenticationPrincipal utilisateur: UserDetails,
        @PageableDefault(size = 20, sort = ["dateHeure"]) pageable: Pageable,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) debut: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fin: LocalDate?,
        @RequestParam(required = false) type: TypeSeance?,
        @RequestParam(required = false) recherche: String?
    ): Page<SeanceResponse> {
        compteur.incrementer("GET /api/seances")
        return service.rechercher(utilisateur.username, debut, fin, type, recherche, pageable)
    }

    @GetMapping("/type/{type}")
    fun parType(
        @PathVariable type: TypeSeance,
        @AuthenticationPrincipal utilisateur: UserDetails,
        @PageableDefault(size = 20) pageable: Pageable
    ): Page<SeanceResponse> = service.parType(type, utilisateur.username, pageable)

    /** Statistiques calculées en parallèle via coroutines. */
    @GetMapping("/statistiques")
    fun statistiques(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) debut: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fin: LocalDate,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): StatistiquesResponse = statistiquesService.calculer(utilisateur.username, debut, fin)

    @GetMapping("/records")
    fun records(@AuthenticationPrincipal utilisateur: UserDetails): RecordsResponse {
        compteur.incrementer("GET /api/seances/records")
        return statistiquesService.records(utilisateur.username)
    }

    @PutMapping("/{id}")
    fun modifier(
        @PathVariable id: Long,
        @Valid @RequestBody request: ModifierSeanceRequest,
        @AuthenticationPrincipal utilisateur: UserDetails
    ): SeanceResponse = service.modifier(id, request, utilisateur.username)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun supprimer(
        @PathVariable id: Long,
        @AuthenticationPrincipal utilisateur: UserDetails
    ) = service.supprimer(id, utilisateur.username)
}
