# Questions d'entretien → où c'est démontré dans le code

Ce projet est conçu comme un **support de révision** : chaque notion susceptible
de tomber en entretien Kotlin / Spring Boot est illustrée par du code réel,
annoté sur place. Ce document fait le lien.

Convention : les commentaires du code portent la référence de la question
(ex. `QUESTION 2.5`), pour retrouver le contexte rapidement.

---

## 1. Kotlin — les fondamentaux

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 1.1 | Différence Java / Kotlin ? | Tout le projet — comparer avec la version Java du même projet |
| 1.2 | Comment Kotlin gère le null ? | `JwtService.kt` (`?:`, `runCatching`), `JwtAuthFilter.kt` (`?.let`), `Extensions.ouSiVide` |
| 1.3 | `val` vs `var` | Partout : DTO en `val`, entités en `var` (mutabilité JPA) |
| 1.4 | `data class` vs `record` Java | `SeanceDtos.kt` (DTO) vs `Utilisateur.kt` (entité) |
| 1.5 | Fonction d'extension, et sous le capot ? | `common/Extensions.kt` — explication du compilé en méthode statique |
| 1.6 | `sealed class` / `when` exhaustif | `common/Resultat.kt` + `SeanceController.creer()` |
| 1.7 | Scope functions (`let`, `apply`…) | `SecurityConfig` (`apply`), `SeanceService.creer` (`apply`), `JwtAuthFilter` (`let`) |
| 1.8 | Coroutine vs thread ? | `seance/StatistiquesService.kt` — `async`/`await`, `coroutineScope` |
| 1.9 | `companion object` | `SeanceResponse.de()` (factory), `JwtService.CLAIM_ROLE` (`const val`) |

**Réponse type 1.2 (null safety)**
Le type indique la nullabilité : `String` ne peut jamais être null, `String?` oui.
On accède avec `?.`, on fournit un défaut avec `?:`, et `!!` force en levant une
NPE — à éviter. Piège : les types venant de Java sont des **types plateforme**
(`String!`), pour lesquels le compilateur ne garantit rien.

**Réponse type 1.3 (piège fréquent)**
`val` empêche la **réassignation**, pas la mutation : `val liste = mutableListOf(1)`
accepte `liste.add(2)`. Pour une vraie immutabilité, il faut aussi un type
immuable (`List` plutôt que `MutableList`).

**Réponse type 1.6**
Hiérarchie fermée connue à la compilation → le `when` devient exhaustif et
**vérifié par le compilateur**. Vérification faite : supprimer une branche du
`when` dans `SeanceController.creer()` produit l'erreur
`'when' expression must be exhaustive, add necessary 'DateDansLeFutur' branch`.
Un cas métier oublié devient une **erreur de compilation**, pas un bug de prod.

---

## 2. Kotlin + Spring Boot — les questions qui font la différence

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 2.1 | Pourquoi le plugin `kotlin-spring` (all-open) ? | `pom.xml` + commentaire dans `SecurityConfig.kt` |
| 2.2 | Pourquoi le plugin `kotlin-jpa` (no-arg) ? | `pom.xml` + commentaire dans `Utilisateur.kt` |
| 2.3 | `class` ou `data class` pour une entité JPA ? | `user/Utilisateur.kt` — les 3 raisons détaillées |
| 2.4 | `@Value` ou `@ConfigurationProperties` ? | `config/AppProperties.kt` |
| 2.5 | Pourquoi `@field:NotNull` ? | `seance/SeanceDtos.kt` |
| 2.6 | Quel module Jackson est indispensable ? | `pom.xml` — `jackson-module-kotlin` |

**Réponse type 2.1**
Les classes Kotlin sont **`final` par défaut**, or Spring crée des proxies CGLIB
pour `@Transactional`, `@Configuration`, `@Cacheable`. Le plugin ouvre
automatiquement les classes annotées Spring. Sans lui : erreurs au démarrage, ou
pire, annotations silencieusement inopérantes.

**Réponse type 2.3**
Une `data class` génère `equals`/`hashCode` sur tous les champs. Sur une entité :
(1) les relations lazy sont touchées → chargements intempestifs ; (2) l'`id` est
null avant persistance, donc le `hashCode` change après le `save` et casse un
`HashSet` ; (3) `copy()` produit une entité détachée avec le même id.
Règle : `class` pour les entités, `data class` pour les DTO.

**Réponse type 2.5**
Une propriété du constructeur primaire a trois cibles possibles (paramètre,
champ, getter). Sans `field:`, l'annotation se pose sur le **paramètre** et Bean
Validation ne la voit pas : **la validation ne se déclenche jamais**.

---

## 3. Spring Boot général

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 3.1 | Injection par constructeur, pourquoi ? | Tous les services et controllers |
| 3.2 | `@Component` / `@Service` / `@Repository` | `@Repository` ajoute la traduction d'exceptions |
| 3.3 | Les pièges de `@Transactional` | `seance/SeanceService.kt` — auto-invocation, rollback, proxification |
| 3.4 | Dirty checking JPA | `SeanceService.modifier()` — pas d'appel à `save()` |
| 3.5 | Gestion des erreurs REST | `common/GestionErreurs.kt` |
| 3.6 | Le problème N+1 | `SeanceRepository` — `@EntityGraph` et projection SQL |
| 3.7 | `open-in-view` | `application.yml` — désactivé, avec justification |

**Réponse type 3.3**
Trois pièges : (1) l'**auto-invocation** (`this.methode()`) court-circuite le
proxy, la transaction n'est pas appliquée ; (2) le rollback n'a lieu par défaut
que sur les exceptions non vérifiées ; (3) la méthode doit être publique et la
classe proxifiable (d'où all-open en Kotlin).

**Réponse type 3.6**
N+1 = 1 requête pour la liste puis 1 requête par élément pour charger la relation
lazy. Solutions : `JOIN FETCH`, `@EntityGraph`, ou une **projection DTO**. Les
trois approches sont visibles dans `SeanceRepository`. Détection : log SQL ou APM
(Datadog).

---

## 4. Tests

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 4.1 | Comment structurer ses tests ? | Voir la pyramide ci-dessous |
| 4.2 | MockK plutôt que Mockito ? | `SeanceServiceTest.kt` |
| 4.3 | Tester du code sécurisé | `SeanceControllerTest`, `AdminSecuriteTest` (`@WithMockUser`) |
| 4.4 | Tester des coroutines | `StatistiquesServiceTest` (`runTest`) |
| 4.5 | Qu'est-ce que le TDD ? | Noms de tests en backticks, un comportement par test |

**La pyramide, du plus rapide au plus lent**

| Niveau | Fichiers | Démarre Spring ? |
|--------|----------|------------------|
| Kotlin pur | `ExtensionsTest`, `ResultatTest` | non — millisecondes |
| Service + mocks | `SeanceServiceTest`, `JwtServiceTest`, `StatistiquesServiceTest` | non |
| Tranche persistance | `SeanceRepositoryTest` (`@DataJpaTest`) | partiellement |
| Tranche web | `SeanceControllerTest`, `AdminSecuriteTest` (`@WebMvcTest`) | partiellement |
| Bout en bout | `ParcoursCompletE2ETest` (`@SpringBootTest`) | oui — le plus lent |

**Réponse type 4.2**
Mockito gère mal les classes `final` (le défaut en Kotlin), les `object` et les
fonctions d'extension. MockK est pensé pour Kotlin : `every { } returns`,
`coEvery` pour les coroutines, `verify(exactly = 0) { }`. Avec Spring,
**springmockk** fournit `@MockkBean`.

---

## 5. Architecture

| # | Question | Réponse ancrée dans ce projet |
|---|----------|-------------------------------|
| 5.1 | Quelle architecture ? | Ici : **en couches** (Controller → Service → Repository). Suffisant pour un CRUD ; ne pas sur-architecturer est un choix assumé. |
| 5.2 | Quand passer en hexagonal ? | Quand la logique métier est riche ou dépend de plusieurs systèmes externes — voir le projet Store Report |
| 5.3 | Exceptions ou type de retour pour les cas métier ? | Les deux cohabitent ici : `sealed interface` pour les cas **normaux** (plafond, date future), exceptions pour l'**anormal** (introuvable, interdit) |
| 5.4 | Design patterns utilisés | Repository, DTO, Factory Method (`SeanceResponse.de`), Chain of Responsibility (`JwtAuthFilter`), Strategy implicite (interfaces injectées), Singleton (beans Spring) |

**Réponse type 5.3 — un bon sujet de discussion**
Une exception signale l'**anormal**. « Le plafond hebdomadaire est atteint » est
un cas métier parfaitement prévu : le modéliser dans le **type de retour** le rend
visible dans la signature, donc impossible à ignorer. À l'inverse, « séance
introuvable » ou « accès refusé » restent des exceptions.

---

## 6. Sécurité

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 6.1 | Fonctionnement du JWT | `security/JwtService.kt`, `JwtAuthFilter.kt` |
| 6.2 | Limites du JWT | Commentaire en tête de `JwtService.kt` |
| 6.3 | Stockage des mots de passe | `SecurityConfig` (BCrypt), `AuthService.inscrire()` |
| 6.4 | Authentification vs autorisation | `UtilisateurDetailsService` vs `AdminController` + `SeanceService.verifierProprietaire` |
| 6.5 | Pourquoi CSRF désactivé ? | `SecurityConfig.kt` |

**Réponse type 6.2**
Le jeton est **signé, pas chiffré** : son contenu est lisible → aucune donnée
sensible dedans. Il n'est pas révocable avant expiration → durées courtes +
refresh token. Le stockage côté client est un sujet en soi (localStorage
vulnérable au XSS, cookie httpOnly préférable).

**Réponse type 6.4 — la règle d'or**
On ne fait **jamais** confiance à un identifiant utilisateur envoyé par le client.
Le controller lit l'utilisateur via `@AuthenticationPrincipal` (issu du jeton
validé), et le service vérifie la propriété de la ressource. Sans cela, il
suffirait de changer un id dans l'URL pour lire les données d'autrui.

---

## 7. Java 21

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 7.1 | Threads virtuels : principe | `config/VirtualThreadConfig.kt`, `application.yml` |
| 7.2 | Vérifier qu'ils sont actifs | `common/SystemeController.kt` (`Thread.isVirtual`) |
| 7.3 | Les limites | Commentaire de `VirtualThreadConfig.kt` |
| 7.4 | Pourquoi `ReentrantLock` et pas `synchronized` ? | `common/CompteurRequetes.kt` |

**Réponse type 7.3 — ce qui distingue**
1. Le pool HikariCP reste la **vraie limite** : 10 000 threads virtuels pour 10
   connexions ne servent à rien.
2. `synchronized` provoque du **pinning** : le thread virtuel reste collé à son
   porteur → utiliser `ReentrantLock`.
3. **Inutile pour du CPU-bound** : les threads virtuels servent aux I/O.
4. **Pas de pooling** : un thread virtuel par tâche, on ne les recycle pas.

**Réponse type 7.1 vs coroutines (question croisée probable)**
Les threads virtuels traitent le code **bloquant** sans le réécrire : c'est une
alternative bien plus simple que le réactif (WebFlux). Les coroutines gardent
l'avantage sur l'annulation, les timeouts et les flux (`Flow`). Sur ce projet,
`StatistiquesService` utilise les coroutines pour paralléliser deux agrégations,
tout en tournant sur des threads virtuels.

---

## 10. Client HTTP sortant (WebClient + coroutines)

| # | Question | Où c'est démontré |
|---|----------|-------------------|
| 10.1 | Quel client HTTP utilisez-vous ? | `config/WebClientConfig.kt` |
| 10.2 | WebClient sans WebFlux, est-ce possible ? | `pom.xml` — commentaire sur `spring-boot-starter-webflux` |
| 10.3 | Comment appeler une API en coroutines ? | `meteo/MeteoClient.kt` — `awaitSingleOrNull()` |
| 10.4 | Comment paralléliser plusieurs appels ? | `meteo/EnrichissementMeteoService.kt` — `coroutineScope` + `async` |
| 10.5 | Comment rendre un appel sortant résilient ? | `MeteoClient` — timeouts, retry, repli, isolation |
| 10.6 | Comment testez-vous un appel sortant ? | `meteo/MeteoClientTest.kt` — MockWebServer |

**Réponse type 10.1 — le paysage actuel**
`RestTemplate` est en maintenance depuis Spring 5 : on ne le choisit plus pour du
neuf. Pour du synchrone, `RestClient` (Spring 6.1+) est le nouveau défaut.
`WebClient` reste le choix quand on veut du non-bloquant — et c'est ce que j'ai
retenu ici, car associé aux coroutines Kotlin il donne un code séquentiel à lire
tout en libérant le thread pendant l'attente réseau. Il existe aussi les **HTTP
Interfaces** (`@HttpExchange`), l'équivalent Spring natif de Feign.

**Réponse type 10.2 — piège fréquent**
Ajouter `spring-boot-starter-webflux` n'active **pas** le mode réactif. Tant que
`spring-boot-starter-web` est présent, Spring Boot reste sur la stack servlet ;
on récupère simplement `WebClient` comme client HTTP sortant. C'est l'usage
recommandé.

**Réponse type 10.3 — l'intérêt des coroutines**
`awaitSingleOrNull()` (kotlinx-coroutines-reactor) transforme un `Mono` en appel
suspendu. Le code se lit de haut en bas, comme du synchrone, au lieu d'une chaîne
`flatMap`/`zip`/`onErrorResume` difficile à déboguer. La coroutine est
**suspendue**, pas bloquée : aucun thread n'est immobilisé pendant l'attente.

**Réponse type 10.5 — les quatre garde-fous (à connaître par cœur)**
1. **Timeouts** — connexion, lecture, réponse globale. Un client sans timeout est
   un incident de production en attente : si le partenaire ralentit, les
   connexions s'accumulent et l'application entière se bloque.
2. **Retry avec backoff exponentiel**, et **uniquement sur les erreurs
   transitoires** (5xx, réseau). Jamais sur un 4xx : réessayer une requête
   invalide ne fait que marteler le partenaire.
3. **Repli (fallback)** — l'enrichissement est un confort : si le service est en
   panne, on retourne null et la séance est créée quand même.
4. **Isolation** — une panne externe ne remonte jamais en 500 à nos utilisateurs.
   S'ajouterait en production un **circuit breaker** (Resilience4j) pour cesser
   d'appeler un service durablement en panne.

**Réponse type 10.4 — concurrence structurée**
`coroutineScope` garantit que si une tâche fille échoue ou si l'appelant est
annulé, **toutes les tâches sœurs sont annulées**. Vérifié à l'exécution : avec
deux appels de 300 ms lancés en parallèle, le total mesuré est de 450 ms au lieu
de 700 ms en séquentiel ; et quand une tâche lève une exception, la tâche sœur
est bien annulée. Aucune requête orpheline ne continue en arrière-plan — c'est
l'avantage décisif sur un `CompletableFuture` mal maîtrisé.

**Réponse type 10.6 — pourquoi MockWebServer plutôt qu'un mock**
On ne mocke pas `WebClient` : on lance un vrai serveur HTTP local. On teste ainsi
la chaîne complète (sérialisation, en-têtes, codes d'erreur, retry, timeouts).
Mocker le client ne testerait que notre propre code. Les tests couvrent le succès,
le 4xx non réessayé, le 5xx réessayé, l'épuisement des tentatives, le timeout et
la coupure de connexion.

**Question croisée probable : coroutines ou threads virtuels ?**
Les deux cohabitent ici. Les threads virtuels (Java 21) permettent au code
bloquant de tenir la charge sans réécriture — c'est ce qui rend le `runBlocking`
du controller acceptable. Les coroutines gardent l'avantage sur l'**annulation**,
les **timeouts** (`withTimeoutOrNull`) et les **flux** (`Flow`). Ici :
`runBlocking` sur thread virtuel à la frontière, coroutines pour orchestrer les
appels parallèles.

---

## 8. Questions sur le parcours — réponses préparées

**« Vous venez de Java, pourquoi Kotlin ? »**
> Après dix ans de Java et Spring Boot, ce qui me séduit dans Kotlin c'est la null
> safety au niveau du type et la concision — moins de bruit autour de la logique
> métier. L'interopérabilité étant totale, je suis opérationnel sur une base
> existante immédiatement.

**« Votre expérience Angular n'est pas la plus récente. »**
> J'ai travaillé sur Angular 8 et 9, notamment sur les projets DSW et PMS Citerne,
> et je suis à l'aise avec les concepts : composants, services, RxJS, formulaires
> réactifs. Je suis avant tout backend, ce qui correspond au poste, et je me remets
> à niveau sur les évolutions récentes (composants standalone, signals).

**« Parlez-moi d'un projet dont vous êtes fier. »**
> Store Report chez Auchan : une API REST de pilotage de l'activité commerciale en
> magasin, sur GCP. J'ai conçu l'API, sécurisé les accès par compte de service en
> OIDC avec les secrets dans Vault, et mis en place les tests de performance JMeter
> avec supervision Grafana. L'architecture était hexagonale : la logique métier
> restait testable sans infrastructure.

---

## 9. Questions à leur poser

- Quelle est la répartition réelle back / front au quotidien ?
- Comment l'équipe est-elle organisée (feature team, tech lead, rituels) ?
- Quelle est la stratégie de tests et le niveau de couverture actuel ?
- Comment se passe une mise en production : CI/CD, fréquence, rollback ?
- Quelle est la part de *run* par rapport au *build* ?
- Comment gérez-vous la dette technique ?

---

## Deux conseils de posture

Quand vous ne savez pas, **dites-le** et enchaînez sur ce que vous feriez pour
trouver. C'est infiniment mieux perçu qu'une réponse floue.

Ancrez systématiquement vos réponses dans vos **projets réels** :
« chez Auchan, sur Ocado, on avait exactement ce cas… » vaut dix définitions
récitées.
