# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
mvn spring-boot:run                              # :8080, H2 console on /h2-console
mvn test                                         # 103 tests across 16 files
mvn test -Dtest=SeanceServiceTest                # one class
mvn test -Dtest='SeanceServiceTest#nom du test'  # one test (names are backticked French sentences)
```

Prerequisites: JDK 21, Maven 3.9+. H2 is in-memory (`jdbc:h2:mem:kayedaw`, user `sa`, no password), so **all data is lost on restart** — including accounts you create by hand.

The Angular frontend lives at **`../kayedaw-web-angular`** and proxies `/api` here. Both repos are published under `github.com/abdoudiagne` (`kayedaw-api-kotlin`, `kayedaw-web-angular`), branch `main`.

Ce dépôt s'est longtemps appelé `kayedaw-api-**koltin**` — une faute de frappe, corrigée partout : dossier local, documentation, nom GitHub et URL du remote. GitHub conserve une redirection depuis l'ancien nom, donc un clone ancien continue de fonctionner.

⚠️ L'ordre comptait, et il est trop tard pour se tromper : renommer d'abord sur GitHub, mettre l'URL locale à jour ensuite. L'inverse casse les `push`.

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

Spring Boot 3.3, Kotlin 1.9, Java 21. Package-by-feature under `com.kayedaw`: `auth`, `user`, `seance`, `meteo`, `admin`, `preference`, plus `config`, `common`, `security`.

Constructor injection everywhere. No `@Value` anywhere — all configuration goes through `config/AppProperties.kt`, a `@ConfigurationProperties("kayedaw")` **data class** (constructor binding ⇒ immutable `val`s, and the app refuses to start if a required property is missing). Add configuration there, not as a scattered `@Value`.

`kotlin-maven-plugin` runs the **all-open** (`spring`) and **no-arg** (`jpa`) compiler plugins — that is what lets Spring proxy `@Configuration`/`@Service` classes and lets JPA instantiate entities, without writing `open` or a no-arg constructor by hand. `-Xjsr305=strict` makes Java platform types nullable-checked.

### Business results: sealed interface, not exceptions

`common/Resultat.kt` defines `sealed interface ResultatCreationSeance` (`Creee` / `PlafondHebdoDepasse` / `DateTropLointaine`). `SeanceService.creer` returns it, and `SeanceController` maps each case with an exhaustive `when` to 201 or 422 + `RefusMetierResponse`.

The split is deliberate and is the point of the design: **normal business outcomes** are modelled in the return type (visible in the signature, compiler-checked for exhaustiveness); **abnormal** ones (`SeanceIntrouvableException`, `AccesRefuseException`, `EmailDejaUtiliseException`) are exceptions translated by `common/GestionErreurs.kt` (`@RestControllerAdvice`) to 400/401/403/404/409. Adding a case to the sealed interface breaks compilation at every `when` — that is the feature. Do not "simplify" a refusal into an exception.

### Administration des comptes

`AdminController` expose : liste paginée et **triable** (le `sort` de Spring Data arrive tel quel), changement de rôle, édition (nom, ville, pays), réinitialisation du mot de passe, blocage, suppression unitaire et en masse.

Les garde-fous sont **par compte** et tous côté serveur : jamais soi-même, jamais le dernier administrateur. Le blocage ajoute une nuance — il compte les administrateurs **ACTIFS** (`countByRoleAndActifTrue`), un admin déjà bloqué n'administrant plus rien.

⚠️ **Le blocage n'est pas qu'un drapeau.** `UtilisateurDetailsService` pose `disabled(!actif)` : `DaoAuthenticationProvider` refuse alors AVANT de comparer le mot de passe, donc un compte bloqué ne sert pas non plus à en deviner un. Et `JwtAuthFilter` vérifie `isEnabled` à chaque requête — sans cela, un jeton déjà émis resterait valable jusqu'à une heure et le blocage ne serait qu'un affichage. `DisabledException` est mappée en **403**, pas 401 : l'identité est correcte, c'est l'accès qui est suspendu.

L'**email n'est pas modifiable** par un administrateur : identifiant de connexion et clé unique, le changer déconnecterait l'intéressé sans explication et demanderait une vérification d'adresse.

### Suppression en masse (admin)

`DELETE /api/admin/utilisateurs` avec `{ ids: [...] }` renvoie **200 et un rapport**, jamais 204 : le résultat est partiel par nature, certains comptes du lot pouvant être refusés pendant que les autres partent.

`supprimerEnMasse` **réutilise `supprimer()`** en boucle plutôt qu'un `deleteAllById` : les deux garde-fous (auto-suppression, dernier administrateur) sont des règles **par compte**, et les garder à un seul endroit fait qu'une troisième règle porterait automatiquement sur les deux chemins.

⚠️ Le `flush()` après chaque suppression est **load-bearing**. `countByRole` est une requête SQL : sans vidage du contexte de persistance elle ne voit pas les suppressions déjà décidées dans la même transaction. Sur un lot contenant les trois administrateurs, elle lisait encore « 3 » à chaque passage, les trois refus n'avaient jamais lieu et l'application se retrouvait **sans aucun administrateur** — irrattrapable sans intervention en base. `SuppressionEnMasseTest` couvre exactement ce cas.

### Ce que la suppression d'un compte efface

Trois entités au schéma : `Utilisateur`, `Seance`, `PreferenceSeance`. Les deux dernières portent `utilisateur_id` et sont effacées **avant** le compte. Les statistiques n'y figurent pas : elles sont **calculées** depuis les séances, aucune ligne n'est stockée.

`SuppressionCompleteTest` le prouve sur la base réelle, et son second test est un **canari de schéma** : il interroge `INFORMATION_SCHEMA` et compare les tables portant `utilisateur_id` à celles que `supprimer` traite. Ajouter une table liée sans compléter le nettoyage fait échouer ce test — à l'endroit exact où la règle se lit.

### Export PDF — `common/DocumentPdf.kt`

Deux points d'entrée : `GET /api/seances/export.pdf` (son carnet) et `GET /api/admin/utilisateurs/export.pdf` (l'annuaire, ADMIN). Générés côté SERVEUR parce que l'autorisation et les données y sont déjà, et que le fichier ne doit dépendre ni du navigateur ni de sa configuration d'impression.

**PDFBox** (Apache 2.0) plutôt qu'iText (AGPL) : le prix est une API de bas niveau, sans tableau ni saut de ligne, d'où l'utilitaire.

⚠️ Pièges déjà payés, à ne pas défaire :
- le **saut de page se teste AVANT d'écrire** : PDFBox ignore la marge basse et laisse déborder la dernière ligne sans rien dire ;
- **l'en-tête est répété** sur chaque page, sinon la page 2 n'est qu'une grille de nombres ;
- ⚠️ **les polices Standard 14 LÈVENT** sur un caractère hors WinAnsi, à l'écriture — donc au milieu d'un document à moitié construit. Une ville en cyrillique ferait échouer l'export entier : `assainir()` remplace en amont ;
- **aucun mot de passe dans l'annuaire**, même haché : un export circule par courriel.

L'export ignore volontairement pagination et filtres — c'est un carnet, pas un écran.

### Référentiel des pays — `GET /api/meteo/pays`

`meteo/Pays.kt` construit les 249 pays depuis `Locale.getISOCountries()` + `getDisplayCountry(Locale.FRENCH)`, triés par un `Collator` français (sinon « Éthiopie » se retrouve après « Zimbabwe »). La liste est **servie par l'API** et non écrite en dur côté Angular : le géocodage dépend de ce même référentiel, et deux listes divergentes rendraient sélectionnables des pays pour lesquels aucune ville ne serait trouvable.

⚠️ **Deux référentiels, deux mécanismes opposés — ne pas les traiter pareil.** Les PAYS viennent du JDK (CLDR), sont calculés une fois au démarrage dans un `object` singleton, et **ne sont pas stockés** : fermés et peu nombreux, ils tiennent en mémoire. Les VILLES viennent d'Open-Meteo à chaque frappe, ne sont écrites nulle part, et n'existent pas hors ligne : ouvertes et innombrables, elles s'interrogent. Seul le NOM retenu par l'utilisateur est persisté, jamais les coordonnées — le géocodeur est consulté à la saisie et à l'enregistrement, **jamais à l'affichage**. Le README backend développe le point.

⚠️ Corollaire du choix « stocker le nom, pas le code » : si une version ultérieure du JDK **renommait** un pays, les comptes existants garderaient l'ancien libellé, absent de la liste proposée — le sélecteur afficherait son texte d'invitation sur une valeur pourtant valide.

### Le pays est porté par la SÉANCE, pas par le compte

⚠️ Changement de contrat récent, et le plus facile à défaire par mégarde : `Seance` porte sa propre colonne `pays`, et `CreerSeanceRequest` un champ `pays` optionnel.

```kotlin
val paysDemande = request.pays?.trim()?.takeIf { it.isNotBlank() } ?: utilisateur.pays
```

- **Le compte n'est plus qu'un défaut.** Le géocodage se faisait sur `utilisateur.pays` : un coureur français en déplacement à Dakar voyait sa séance rattachée à un homonyme français, ou à rien — sans météo et sans message, puisque la météo ne fait jamais échouer une création.
- **Optionnel dans la requête**, donc un client plus ancien qui ne l'envoie pas se comporte exactement comme avant, et le cas courant — courir chez soi — n'exige aucune saisie.
- **Stocké** sur la séance, comme la météo : relire le pays sur le profil prêterait à un compte déménagé des séances qu'il n'a jamais courues là.
- `SeanceServiceTest` couvre les trois cas : pays de la requête, repli sur le compte, et pays vide ou fait d'espaces qui ne doit pas écraser le repli.

⚠️ `modifier()` ne touche ni au lieu ni à la météo — c'est ce qui rend les deux champs obligatoires **à la création** côté Angular, l'écran de modification ne les proposant pas.

### La météo est STOCKÉE, pas recalculée

`Seance` porte ses propres colonnes météo (`ville`, `temperatureMinC`, `temperatureMaxC`, `temperatureALHeureC`, `ventKmH`, `sourceMeteo`, `stationMeteo`, `alertesMeteo`…), renseignées **une fois** à la création. Une observation passée ne change plus, et rien ne justifierait de réinterroger un tiers à chaque affichage : une séance de l'an dernier resterait sans mesure le jour où l'API est indisponible ou change de contrat.

⚠️ Conséquence côté client, à ne pas casser : `modifier()` ne touche **pas** à la météo et le formulaire Angular n'offre pas la ville en modification. C'est pourquoi la ville y est obligatoire **à la création** — une séance créée sans lieu reste définitivement sans météo.

### Champ `pays` du COMPTE — à ne pas confondre avec celui de la séance

Celui-ci est la résidence habituelle, et il ne sert plus qu'à **pré-remplir** le formulaire de séance et à borner l'autocomplétion des villes. « France » par défaut. ⚠️ Le défaut est déclaré **en base** (`columnDefinition`) et pas seulement en Kotlin : `ddl-auto: update` ajoute une colonne `NOT NULL` à une table déjà peuplée, et sans valeur par défaut SQL la migration échoue sur les lignes existantes. Dans les requêtes de modification, le champ est **optionnel** — absent, il reste inchangé, sinon un client ignorant du champ l'écraserait à chaque enregistrement.

### Règles de mot de passe

**Minimum 5 caractères**, partout (inscription, profil, réinitialisation par un administrateur). Aucune liste de mots de passe courants : elle a existé puis été retirée à la demande.

⚠️ `@Size(max = 100)` sur `nom` n'est pas cosmétique : sans borne, un nom de 300 caractères passait la validation, atteignait la base et faisait déborder la colonne — le client recevait un **500** là où sa saisie était simplement trop longue.

### Séance scheduling model

A `Seance` carries a full `dateHeure` (`LocalDateTime`), **not** a date: two sessions can share a day and weather resolves per hour. Sessions may be planned up to **30 days** ahead (`kayedaw.entrainement.planification-max-jours`, default in `AppProperties.Entrainement`; the Angular side mirrors it as `HORIZON_PLANIFICATION_JOURS` in `shared/validators/seance.validators.ts` — **keep the two in sync**). Past dates are always allowed (logging a session already run). ⚠️ The horizon now **outruns Open-Meteo's forecast** : l'API ne rend de valeurs que jusqu'à **J+14** — J+15 est accepté mais vide, J+16 refusé. Une séance planifiée au-delà est acceptée et stockée, mais revient sans météo. Ce n'est pas un défaut, et le formulaire Angular prévient à la saisie (`PORTEE_PREVISION_JOURS = 14`, corrigé depuis 15 : le dernier jour accepté n'est pas le dernier jour couvert).

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
- **Le géocodage suit le PAYS DU COMPTE**, il n'est plus épinglé sur la France. Verrouillé sur `countryCode=FR`, aucune ville étrangère n'était trouvable : la séance partait sans ville *ni* météo, en silence. Le pays reste indispensable — sans lui « Paris » peut répondre Paris, Texas, et « Saint-Louis » le Missouri plutôt que le Sénégal.
- **Le rapprochement se fait sur le CODE ISO** (`Pays.code(nom)` → `FR`, `SN`), le nom normalisé n'étant qu'un repli : le géocodeur ne répond pas toujours dans la langue de la requête.
- ⚠️ **`MINIMUM_RESULTATS = 2` contourne un bug d'Open-Meteo** : avec `count=1`, l'API renvoie parfois un résultat situé dans un autre pays. On en demande plusieurs, puis on filtre nous-mêmes.
- ⚠️ **`RESULTATS_GEOCODAGE = 100`, le maximum accepté — ne pas « raisonner » cette valeur.** Le géocodeur classe MONDIALEMENT avant de tronquer, puis applique le filtre de pays : une commune modeste est évincée par ses homonymes plus peuplés bien avant que son pays n'entre en jeu. Mesuré sur « bambi » au Sénégal : `count=20` → 0 résultat sénégalais, `count=50` → 0, `count=100` → **Bambilor**. Une marge proportionnelle à la limite d'affichage (`limite * 3`) bornait ce qu'on AFFICHE, pas la profondeur où se trouve la bonne réponse. Les grandes villes restent en tête, rien n'est perdu à chercher large. `MeteoClientTest` fige la valeur dans l'URL sortante.
- **`countryCode` est REVENU**, dérivé du pays demandé là où il était figé sur `FR`. Il allège la réponse et le géocodeur l'honore. ⚠️ Il ne dispense NI du `count` élevé — à `count=20`, « bambi » + `countryCode=SN` rend zéro résultat — NI du filtrage local, inutilisable quand on ne connaît que le NOM du pays sans code ISO.
- **`admin1` → `Coordonnees.region`**, distinct de `departement`. Le second porte le NUMÉRO de département français dont DPClim a besoin pour ses stations : y glisser « Kaolack » ferait échouer la recherche sans message clair. La région ne sert qu'à SITUER une suggestion à l'écran — hors de France la liste n'affichait que des noms nus, que rien ne rattachait au pays choisi, et qu'on pouvait prendre pour du bruit venu d'ailleurs.

**Limite du géocodeur, à ne pas confondre avec un défaut de notre code** : il cherche par RESSEMBLANCE et non par préfixe, en pénalisant l'écart de longueur. « bamb » rend Banba, Bamba et Mbamb — tous de cinq lettres — jamais Bambilor. À `count=100` et sans filtre de pays, Bambilor est absent des cent résultats ; Nominatim, essayé en comparaison, fait pire. Aucun réglage ne rattrape ce cas : le client affiche « continuez à saisir » plutôt qu'une liste vide.
- **Le fuseau vient de la réponse du géocodeur** (`coordonnees.fuseau`). Codé en dur sur `Europe/Paris`, il décalait de deux heures la température « à l'heure de la séance » à Dakar — sans que rien ne le signale.

**DPClim is order-then-poll**, not a plain REST call: `/liste-stations/horaire` → `/commande-station/horaire` (202 + command id) → `/commande/fichier` (**201** ready / **204** not yet, retried `tentativesFichier` times). The CSV is `;`-separated with **comma decimals**, hourly rows keyed `YYYYMMDDHH`, and mostly empty cells — `AnalyseurCsvDpclim` parses it tolerantly, picks the hour closest to the session, and converts wind from m/s to km/h. Station lists are cached per département in a `ConcurrentHashMap`.

**Auth**: OAuth2 `client_credentials`. `JetonMeteoFranceService` caches the 1 h token, refreshes 5 min early, and serialises refreshes behind a `Mutex`. The secret is `kayedaw.meteo-france.application-id` (base64 of `client_id:client_secret`), supplied by `METEOFRANCE_APPLICATION_ID` or by the gitignored `config/application.yml` — Spring Boot reads `./config/` with priority over the classpath, and unlike `src/main/resources` it is **not packaged into the JAR**. Absent both, `MeteoFrance.actif` is false, the integration disables itself and Open-Meteo takes over. The test profile provides no secret, so **DPClim is inert in tests** — which is exactly how the fallback path stays covered.

`Modèle_AROME_swagger.json` and `Données_Climatologiques_swagger.json` are vendor references. AROME is **unused**: it only exposes WMS/WCS raster forecast (GRIB2/GeoTIFF) over a few days, so it cannot answer "what was the weather during my session".

## Tests

**103 tests, 16 files**, deliberately shaped as a pyramid — keep new tests at the cheapest level that can catch the defect.

| Level | Files | Spring? |
|-------|-------|---------|
| Pure Kotlin | `ExtensionsTest`, `ResultatTest`, `AnalyseurCsvDpclimTest` | no (ms) |
| Service + MockK | `SeanceServiceTest`, `JwtServiceTest`, `StatistiquesServiceTest`, `EnrichissementMeteoServiceTest`, `PreferenceServiceTest`, `SuppressionEnMasseTest` (+ `EditionAdministrativeTest`, même fichier) | no |
| Outgoing HTTP | `MeteoClientTest` (MockWebServer) | no |
| Persistence | `SeanceRepositoryTest` (`@DataJpaTest`) | partial |
| Web | `SeanceControllerTest`, `AdminSecuriteTest` (`@WebMvcTest` + `@MockkBean`) | partial |
| Schéma réel | `SuppressionCompleteTest` (`@SpringBootTest`, interroge `INFORMATION_SCHEMA`) | yes |
| End to end | `ParcoursCompletE2ETest` (`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`) | yes |

- **MockK / springmockk**, not Mockito — `@MockkBean`, not `@MockBean`. Mockito is only present transitively.
- **`MeteoClientTest` uses MockWebServer** — a real local HTTP server, so timeouts, retries and status handling are exercised for real. Do not replace it with a mocked `WebClient`; that would test the mock.
- E2E tests register a **`UUID`-suffixed account** per scenario so they never collide with the demo seed or each other.
- `application-test.yml` (`@ActiveProfiles("test")`) uses a separate H2 schema with `create-drop` and a fake Base64 JWT secret.

## Contract with the Angular client

`../kayedaw-web-angular/src/app/core/models/seance.model.ts` mirrors these DTOs field for field: `Page<T>` is Spring Data's page, and the TypeScript `MotifRefus` union reproduces `ResultatCreationSeance`. **Renaming a DTO field, changing a `motif` string, or altering an HTTP status breaks the frontend silently** — grep the sibling repo before touching `SeanceDtos.kt`, `RefusMetierResponse`, or `GestionErreurs`. The 422 motifs currently are `PLAFOND_HEBDOMADAIRE` and `DATE_TROP_LOINTAINE`.

Champs ajoutés récemment au contrat, à conserver des deux côtés :

- `pays` sur `CreerSeanceRequest` (optionnel) et sur `SeanceResponse` (nullable — les séances antérieures à la colonne n'en portent pas) ;
- ⚠️ **`pays` sur `AuthResponse`**, et son absence était un BUG. Le client pré-remplit le formulaire de séance avec le lieu du compte : la ville arrivait, le pays non, et il retombait sur son repli « France ». Un compte sénégalais ouvrait donc « Nouvelle séance » sur **Dakar / France** — introuvable au géocodage, donc sans suggestions ni météo, alors que le profil était juste. **Les deux champs sont indissociables : une ville ne se géocode pas sans son pays.** `ParcoursCompletE2ETest` le fige des deux côtés de l'authentification, inscription et connexion n'empruntant pas le même chemin.


## Préférences utilisateur

`com.kayedaw.preference` — `GET`/`PUT /api/profil/preferences`.

- `PreferenceSeance` : une ligne par couple (utilisateur, type), contrainte d'unicité **en base** et non seulement en code. Le paquet est séparé à dessein : l'entité a besoin de `TypeSeance` (paquet `seance`), qui dépend déjà de `Utilisateur` (paquet `user`) — la loger dans `user` créerait un cycle.
- `Theme` et `Langue` sont deux colonnes scalaires sur `Utilisateur` : une valeur unique par compte, une table séparée n'apporterait qu'une jointure.
- La réponse contient **toujours les cinq types** : les valeurs jamais réglées viennent de `PreferenceService.DEFAUTS`, identiques pour tous les types (5 km / 60 min, soit 12 min/km — une allure plausible partout, donc le formulaire front ne s'ouvre jamais sur une erreur d'allure irréaliste). Une ligne absente signifie « jamais personnalisé », ce qui permet de faire évoluer les repères d'usine sans réécrire les comptes existants.
- `Theme` et `Langue` sont des enums de `com.kayedaw.user`, persistés en `EnumType.STRING` comme `Role` : robustes au réordonnancement.
- L'écriture **remplace** (delete + flush + insert) au lieu de fusionner. Le `flush()` explicite est load-bearing : sans lui la suppression et l'insertion partent dans le désordre et la contrainte d'unicité saute.
- ⚠️ `AdminService.supprimer` doit effacer les préférences **avant** l'utilisateur, comme les séances. Toute nouvelle table référençant `utilisateur` s'ajoute là.
- La distance maximale vient de la configuration, qu'une annotation Bean Validation ne peut pas lire : le contrôle est fait dans le service et lève `IllegalArgumentException`, mappée en **400** par `GestionErreurs` (le handler a été ajouté pour cela — sans lui, une saisie invalide remontait en 500).

