# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
mvn spring-boot:run                              # :8080, H2 console on /h2-console
mvn test                                         # 74 tests across 12 files
mvn test -Dtest=SeanceServiceTest                # one class
mvn test -Dtest='SeanceServiceTest#nom du test'  # one test (names are backticked French sentences)
```

Prerequisites: JDK 21, Maven 3.9+. H2 is in-memory (`jdbc:h2:mem:kayedaw`, user `sa`, no password), so **all data is lost on restart** — including accounts you create by hand.

The Angular frontend lives at **`../kayedaw-web-angular`** and proxies `/api` here. Both repos are published under `github.com/abdoudiagne` (`kayedaw-api-koltin`, `kayedaw-web-angular`), branch `main`.

Demo accounts, re-seeded on every start by `config/DatasInitiales.kt`:

| Compte | Mot de passe | Rôle |
|--------|--------------|------|
| `admin@kayedaw.fr` | `12345` | ADMIN |
| `user@kayedaw.fr` | `12345` | USER |

The seed is `@Profile("!prod")` — the password is in the repo on purpose. Public registration always forces `Role.USER` (`AuthService`), so an ADMIN can only come from that seed.

## Language convention

**All code is in French** — identifiers, methods, DTO fields, route segments, comments, and test names (`fun \`inscription puis connexion retournent un jeton exploitable\`()`). `seance`, `utilisateur`, `allure`, `plafond`, `repli`. Match this when adding code; do not "translate" existing names. Field names cross the wire to the Angular client, so renaming one is a breaking API change.

## Purpose of the codebase

This is an **interview-preparation showcase**, not a product. Nearly every file carries long pedagogical block comments (`┌─ QUESTION 3.6 — Le problème N+1 ─┐`) explaining *why* a mechanism was chosen and which trap it avoids. **Those comments are the deliverable** — preserve and extend them rather than trimming them for concision. `docs/QUESTIONS-REPONSES.md` is the central index mapping each interview question to the file that demonstrates it; README.md summarises the same ground.

## .gitignore — the leading slash is load-bearing

The secrets file is `config/application.yml` at the repo root. The ignore rule **must** stay `/config/`: written as `config/`, git matches any directory named `config` at any depth and silently excludes `src/main/kotlin/com/kayedaw/config/` — the whole configuration source package. That happened; the published repo did not compile. If you touch `.gitignore`, verify with `git ls-files src/main/kotlin/com/kayedaw/config/`.

## Architecture

Spring Boot 3.3, Kotlin 1.9, Java 21. Package-by-feature under `com.kayedaw`: `auth`, `user`, `seance`, `meteo`, `admin`, plus `config`, `common`, `security`.

Constructor injection everywhere. No `@Value` anywhere — all configuration goes through `config/AppProperties.kt`, a `@ConfigurationProperties("kayedaw")` **data class** (constructor binding ⇒ immutable `val`s, and the app refuses to start if a required property is missing). Add configuration there, not as a scattered `@Value`.

`kotlin-maven-plugin` runs the **all-open** (`spring`) and **no-arg** (`jpa`) compiler plugins — that is what lets Spring proxy `@Configuration`/`@Service` classes and lets JPA instantiate entities, without writing `open` or a no-arg constructor by hand. `-Xjsr305=strict` makes Java platform types nullable-checked.

### Business results: sealed interface, not exceptions

`common/Resultat.kt` defines `sealed interface ResultatCreationSeance` (`Creee` / `PlafondHebdoDepasse` / `DateTropLointaine`). `SeanceService.creer` returns it, and `SeanceController` maps each case with an exhaustive `when` to 201 or 422 + `RefusMetierResponse`.

The split is deliberate and is the point of the design: **normal business outcomes** are modelled in the return type (visible in the signature, compiler-checked for exhaustiveness); **abnormal** ones (`SeanceIntrouvableException`, `AccesRefuseException`, `EmailDejaUtiliseException`) are exceptions translated by `common/GestionErreurs.kt` (`@RestControllerAdvice`) to 400/401/403/404/409. Adding a case to the sealed interface breaks compilation at every `when` — that is the feature. Do not "simplify" a refusal into an exception.

### Séance scheduling model

A `Seance` carries a full `dateHeure` (`LocalDateTime`), **not** a date: two sessions can share a day and weather resolves per hour. Sessions may be planned up to **14 days** ahead (`kayedaw.entrainement.planification-max-jours`, default in `AppProperties.Entrainement`; the Angular side mirrors it as `HORIZON_PLANIFICATION_JOURS` in `shared/validators/seance.validators.ts` — **keep the two in sync**). Past dates are always allowed (logging a session already run).

The rules are deliberately **asymmetric** — do not "fix" the inconsistency:

- A planned séance **counts toward** the weekly 80 km cap (`SeanceService.creer`) — over-planning should be flagged at planning time.
- A planned séance is **excluded from statistics** — `seancesDeLaPeriode` and `toutesLesSeancesRealisees` filter on `estPlanifiee()`, and `SeanceRepository.volumeParType` takes a `:maintenant` bound. Stats must reflect what was actually run.

Weekly windows use `LocalDateTime.semaineComplete()` (`common/Extensions.kt`), Monday 00:00 → Sunday 23:59:59.999. Comparing `LocalDate`s dropped Sunday-afternoon sessions from the weekly total.

### Persistence

`Seance.utilisateur` is `FetchType.LAZY` and there is a composite index on `(utilisateur_id, date_heure)`. `open-in-view: false` is set on purpose — it stops lazy-loading problems from hiding behind the view render.

- N+1 is solved by `@EntityGraph(attributePaths = ["utilisateur"])` on `findWithUtilisateurByUtilisateurId`.
- Aggregations are **SQL projections** (`volumeParType` → `VolumeParTypeProjection`), not in-memory folds over entities.
- `modifier()` relies on **dirty checking** — the entity is managed inside `@Transactional`, so there is no `save()` call. Don't add one.
- ⚠️ In `SeanceRepository.rechercher`, the parentheses around the `:recherche is null or … or …` block are **mandatory**. Without them the `or` swallows the preceding conditions and the query returns *other users'* séances.

`ddl-auto: update` in dev, `create-drop` under the `test` profile. There are no migrations; introducing Flyway is listed as a pre-production task, not done.

### Security

JWT, stateless, `csrf().disable()` — a token in a header is not sent automatically by the browser, so the CSRF vector doesn't exist. BCrypt for passwords. Key is `Keys.hmacShaKeyFor(Base64.decode(kayedaw.jwt.secret))`, so **the secret must be valid Base64** of ≥32 bytes.

Three things in `config/SecurityConfig.kt` that look removable and are not:

- **`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`** — `AccessDeniedHandlerImpl` calls `sendError(403)`, which re-dispatches through the chain toward `/error`. In STATELESS that second pass has no `SecurityContext`, is anonymous, gets rejected by `anyRequest().authenticated()`, and the client receives **401 instead of the intended 403**.
- **`authenticationEntryPoint(HttpStatusEntryPoint(UNAUTHORIZED))`** — without it Spring answers 403 to everything, including unauthenticated calls. The Angular `erreur.interceptor.ts` depends on the distinction: 401 purges the session and redirects, 403 only shows a message.
- **`GET /api/meteo/villes` is public** — it feeds the autocomplete on the *registration* form, i.e. before any login. Protecting it made the field fail silently. No personal data transits; rate limiting is the production follow-up.

Authorization is layered on purpose: URL rules (`/api/admin/** hasRole("ADMIN")`) **and** `@PreAuthorize` on the admin methods. Ownership is enforced in the service (`verifierProprietaire`), and the identity always comes from the `SecurityContext` (`UserDetails.username`), **never** from the request body. ADMIN bypasses the ownership check.

`h2-console` is permitted and `frameOptions` disabled — dev only, both are listed as things to remove before production.

## Outgoing HTTP: WebClient + coroutines

`WebClient` (reactive) called from `suspend` functions via `awaitSingleOrNull()` / `awaitBody()`, bridged back to the servlet world by a single `runBlocking` in `SeanceService.creer`. On Java 21 that call runs on a virtual thread, so blocking unmounts the carrier. Virtual threads are enabled (`spring.threads.virtual.enabled`); `common/CompteurRequetes.kt` uses `ReentrantLock` rather than `synchronized` to avoid pinning, and `/api/systeme/execution` reports `Thread.isVirtual`.

No timeout is left at its default — `config/WebClientConfig.kt` reads `kayedaw.http-client` (connect/read/response/pool). Retries use `Retry.backoff` filtered by `estTransitoire` (5xx, timeouts, connection failures) — **4xx is never retried**. Every outgoing call degrades to `null` rather than throwing: weather must never break session creation.

### Weather: three sources, one contract

`EnrichissementMeteoService` geocodes first (required, it yields the coordinates), then fans out `async` calls under `coroutineScope` (structured concurrency: one failure or a cancelled caller cancels the siblings). The chosen source is reported to the client as `sourceMeteo`:

| Case | Source | Nature |
|------|--------|--------|
| Past séance, station has data | `OBSERVATION_METEO_FRANCE` | real station readings + `temperatureALHeureC` at the session hour |
| Past séance, DPClim unavailable | `ARCHIVE_OPEN_METEO` | reanalysis, daily aggregates |
| Today or planned | `PREVISION_OPEN_METEO` | forecast |

Gotchas already paid for — **do not regress these**:

- **Today uses the forecast, not the archive.** Open-Meteo's archive stops at *yesterday* and rejects today with `start_date is out of allowed range`. The creation screen pre-fills the date to now, so routing today to the archive returned an entirely empty preview.
- **DPClim has its own 2.5 s budget inside the global 5 s one.** Without it, a slow order-then-poll chain consumed the whole budget and the séance came back with *no* weather **and no city**, discarding Open-Meteo results already in hand.
- **`departementDepuisCodePostal` keeps leading digits only.** The geocoder returns postcodes like `"69061 CEDEX 06"`; requiring an all-digit string silently broke Lyon, Marseille and every large city. `97`/`98` prefixes take 3 digits (DOM).
- **Three stations are tried, by proximity.** Not every station has data every day (Toulouse's nearest one 404s). `CANDIDATES_MAX = 3`, then fall back to Open-Meteo.
- **Geocoding is pinned to France** (`countryCode`), otherwise "Paris" can resolve to Paris, Texas.

**DPClim is order-then-poll**, not a plain REST call: `/liste-stations/horaire` → `/commande-station/horaire` (202 + command id) → `/commande/fichier` (**201** ready / **204** not yet, retried `tentativesFichier` times). The CSV is `;`-separated with **comma decimals**, hourly rows keyed `YYYYMMDDHH`, and mostly empty cells — `AnalyseurCsvDpclim` parses it tolerantly, picks the hour closest to the session, and converts wind from m/s to km/h. Station lists are cached per département in a `ConcurrentHashMap`.

**Auth**: OAuth2 `client_credentials`. `JetonMeteoFranceService` caches the 1 h token, refreshes 5 min early, and serialises refreshes behind a `Mutex`. The secret is `kayedaw.meteo-france.application-id` (base64 of `client_id:client_secret`), supplied by `METEOFRANCE_APPLICATION_ID` or by the gitignored `config/application.yml` — Spring Boot reads `./config/` with priority over the classpath, and unlike `src/main/resources` it is **not packaged into the JAR**. Absent both, `MeteoFrance.actif` is false, the integration disables itself and Open-Meteo takes over. The test profile provides no secret, so **DPClim is inert in tests** — which is exactly how the fallback path stays covered.

`Modèle_AROME_swagger.json` and `Données_Climatologiques_swagger.json` are vendor references. AROME is **unused**: it only exposes WMS/WCS raster forecast (GRIB2/GeoTIFF) over a few days, so it cannot answer "what was the weather during my session".

## Tests

74 tests, 12 files, deliberately shaped as a pyramid — keep new tests at the cheapest level that can catch the defect.

| Level | Files | Spring? |
|-------|-------|---------|
| Pure Kotlin | `ExtensionsTest`, `ResultatTest`, `AnalyseurCsvDpclimTest` | no (ms) |
| Service + MockK | `SeanceServiceTest`, `JwtServiceTest`, `StatistiquesServiceTest`, `EnrichissementMeteoServiceTest` | no |
| Outgoing HTTP | `MeteoClientTest` | no |
| Persistence | `SeanceRepositoryTest` (`@DataJpaTest`) | partial |
| Web | `SeanceControllerTest`, `AdminSecuriteTest` (`@WebMvcTest` + `@MockkBean`) | partial |
| End to end | `ParcoursCompletE2ETest` (`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`) | yes |

- **MockK / springmockk**, not Mockito — `@MockkBean`, not `@MockBean`. Mockito is only present transitively.
- **`MeteoClientTest` uses MockWebServer** — a real local HTTP server, so timeouts, retries and status handling are exercised for real. Do not replace it with a mocked `WebClient`; that would test the mock.
- E2E tests register a **`UUID`-suffixed account** per scenario so they never collide with the demo seed or each other.
- `application-test.yml` (`@ActiveProfiles("test")`) uses a separate H2 schema with `create-drop` and a fake Base64 JWT secret.

## Contract with the Angular client

`../kayedaw-web-angular/src/app/core/models/seance.model.ts` mirrors these DTOs field for field: `Page<T>` is Spring Data's page, and the TypeScript `MotifRefus` union reproduces `ResultatCreationSeance`. **Renaming a DTO field, changing a `motif` string, or altering an HTTP status breaks the frontend silently** — grep the sibling repo before touching `SeanceDtos.kt`, `RefusMetierResponse`, or `GestionErreurs`. The 422 motifs currently are `PLAFOND_HEBDOMADAIRE` and `DATE_TROP_LOINTAINE`.
