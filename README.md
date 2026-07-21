# KayeDaw API — projet vitrine Kotlin / Spring Boot / Java 21

API REST de suivi de séances de course à pied : **CRUD complet**, **authentification
JWT**, **autorisation par rôle**. Conçu comme un **support de préparation à un
entretien technique** : chaque notion susceptible d'être demandée est illustrée
par du code réel et annotée sur place.

> 📖 **Document central : [`docs/QUESTIONS-REPONSES.md`](docs/QUESTIONS-REPONSES.md)**
> Il fait le lien entre chaque question d'entretien et le fichier qui la démontre.

---

## Démarrage

```bash
# Prérequis : JDK 21, Maven 3.9+
mvn test              # toute la pyramide de tests
mvn spring-boot:run   # http://localhost:8080
```

Base H2 en mémoire · console : http://localhost:8080/h2-console
(`jdbc:h2:mem:kayedaw`, user `sa`, sans mot de passe)

**Tests d'API** : importer `postman/` dans Postman (26 requêtes, jeton capturé
automatiquement) — voir `postman/README.md`.

---

## Ce que le projet démontre

### Kotlin
- **Null safety** : `?.`, `?:`, `runCatching{}.getOrNull()` — jamais de `!!` abusif
- **Fonctions d'extension** : `common/Extensions.kt` (avec l'explication du compilé)
- **`sealed interface` + `when` exhaustif** : `common/Resultat.kt` → `SeanceController`
- **Data classes** pour les DTO, `class` pour les entités JPA (et pourquoi)
- **Scope functions** : `apply` pour configurer, `let` pour le null
- **Coroutines** : `StatistiquesService` parallélise deux agrégations (`async`/`await`)
- **`companion object`**, `const val`, `by lazy`, valeurs par défaut

### Kotlin + Spring (les pièges classiques)
- Plugins **all-open** et **no-arg** dans le `pom.xml`, avec justification
- **`@field:`** sur les annotations de validation
- **`@ConfigurationProperties`** typé plutôt que des `@Value` éparpillés
- **`jackson-module-kotlin`** pour désérialiser les data classes

### Spring Boot
- Injection **par constructeur** partout
- Pièges de **`@Transactional`** documentés (auto-invocation, rollback, proxy)
- **`@RestControllerAdvice`** : 400 / 401 / 403 / 404 / 409 / 422
- **Problème N+1** : `@EntityGraph` et projection SQL agrégée
- **`open-in-view: false`** assumé et expliqué

### Sécurité
- JWT signé, filtre `OncePerRequestFilter`, API **stateless**
- **BCrypt** pour les mots de passe
- **Autorisation à deux niveaux** : règles d'URL + `@PreAuthorize`
- **Contrôle de propriété** : un utilisateur n'accède qu'à ses séances (ADMIN excepté)

### Client HTTP sortant (WebClient + coroutines)
- **`WebClient`** non bloquant, appelé en `suspend` via `awaitSingleOrNull()`
- **Appels parallèles** : `coroutineScope` + `async` (géocodage puis météo ‖ qualité de l'air)
- **Résilience** : timeouts, retry à backoff exponentiel sur erreurs transitoires uniquement, repli, budget global
- **Tests** : MockWebServer (vrai serveur HTTP local), pas de mock du client

### Java 21
- **Threads virtuels** activés, endpoint de diagnostic `Thread.isVirtual`
- **`ReentrantLock`** au lieu de `synchronized` (problème du *pinning*)
- Les **limites** de Loom documentées (pool HikariCP, CPU-bound, pas de pooling)

### Tests — la pyramide complète
| Niveau | Fichiers | Spring ? |
|--------|----------|----------|
| Kotlin pur | `ExtensionsTest`, `ResultatTest` | non (ms) |
| Service + mocks MockK | `SeanceServiceTest`, `JwtServiceTest`, `StatistiquesServiceTest` | non |
| Persistance | `SeanceRepositoryTest` (`@DataJpaTest`) | partiel |
| Web | `SeanceControllerTest`, `AdminSecuriteTest` (`@WebMvcTest`) | partiel |
| Bout en bout | `ParcoursCompletE2ETest` (`@SpringBootTest`) | oui |

---

## API

### Public
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | `/api/auth/inscription` | Crée un compte, retourne un JWT |
| POST | `/api/auth/connexion` | Authentifie, retourne un JWT |

### Authentifié (JWT)
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | `/api/seances` | Crée une séance (422 si règle métier violée) |
| GET | `/api/seances` | Liste paginée de **ses** séances |
| GET | `/api/seances/{id}` | Détail (403 si elle ne vous appartient pas) |
| GET | `/api/seances/type/{type}` | Filtre par type |
| GET | `/api/seances/statistiques?debut=&fin=` | Agrégats calculés en parallèle |
| PUT | `/api/seances/{id}` | Modifie |
| DELETE | `/api/seances/{id}` | Supprime |
| GET | `/api/meteo/conditions?ville=&date=` | Appel sortant : météo + qualité de l'air |
| GET | `/api/systeme/execution` | Diagnostic threads virtuels |

### Réservé ADMIN
| Méthode | Route |
|---------|-------|
| GET | `/api/admin/utilisateurs` |
| GET | `/api/admin/metriques` |

### Exemple

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/inscription \
  -H "Content-Type: application/json" \
  -d '{"email":"abdou@test.fr","motDePasse":"motdepasse123","nom":"Abdou"}' \
  | grep -o '"token":"[^"]*' | cut -d'"' -f4)

curl -X POST http://localhost:8080/api/seances \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":"ENDURANCE","distanceKm":10.0,"dureeMinutes":50,"date":"2026-07-19"}'
```

---

## Règles métier implémentées

0. **Enrichissement météo** optionnel : si `ville` est fourni à la création, l'API
   interroge un service externe. En cas de panne, la séance est créée sans ces données.
1. **Plafond hebdomadaire** de 80 km (configurable) → HTTP 422
2. **Pas de séance dans le futur** → HTTP 422
3. **Propriété des données** : chacun ne voit que ses séances → HTTP 403

Les deux premières sont modélisées par une `sealed interface` plutôt que par des
exceptions : ce sont des cas métier **normaux**, visibles dans la signature de la
fonction. Les cas anormaux (introuvable, accès refusé) restent des exceptions.
C'est un bon sujet de discussion en entretien.

---

## Structure

```
com.kayedaw
├─ config/
│  ├─ AppProperties.kt          @ConfigurationProperties typé
│  ├─ SecurityConfig.kt         chaîne de filtres, BCrypt, règles d'accès
│  └─ VirtualThreadConfig.kt    Java 21 / Loom
├─ common/
│  ├─ Extensions.kt             fonctions d'extension
│  ├─ Resultat.kt               sealed interface (cas métier)
│  ├─ Exceptions.kt             exceptions (cas anormaux)
│  ├─ GestionErreurs.kt         @RestControllerAdvice
│  ├─ CompteurRequetes.kt       ReentrantLock vs synchronized
│  └─ SystemeController.kt      diagnostic Java 21
├─ meteo/                       appel sortant WebClient + coroutines
│  ├─ MeteoClient.kt            suspend + retry + repli
│  ├─ EnrichissementMeteoService.kt  orchestration parallèle
│  └─ MeteoDtos.kt              DTO tolérants aux champs inconnus
├─ security/                    JwtService, JwtAuthFilter, UserDetailsService
├─ auth/                        inscription / connexion
├─ user/                        entité Utilisateur, rôles
├─ seance/                      entité, DTO, repository, services, controller
└─ admin/                       endpoints réservés ADMIN (@PreAuthorize)
```

---

## Avant une mise en production

Ce projet est une démo pédagogique. Il faudrait :

- Sortir la clé JWT du fichier de config (Vault / Secret Manager) et ajouter un
  **refresh token** avec un access token de courte durée
- Remplacer H2 par PostgreSQL et gérer les migrations (Flyway) plutôt que
  `ddl-auto: update`
- Désactiver la console H2, restreindre CORS
- Ajouter du **rate limiting** sur `/api/auth/**` (anti-bruteforce)
- Ajouter un **circuit breaker** (Resilience4j) sur les appels sortants, pour
  cesser d'appeler un partenaire durablement en panne
- Compléter l'observabilité (traces, métriques métier, alerting)
