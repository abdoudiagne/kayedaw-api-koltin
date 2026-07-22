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

**Tests d'API** : importer `postman/` dans Postman (29 requêtes, jeton capturé
automatiquement) — voir `postman/README.md`.

### Comptes de démonstration

Recréés à chaque démarrage (base H2 en mémoire) par `config/DatasInitiales.kt`,
annoté `@Profile("!prod")` :

| Compte | Mot de passe | Rôle |
|--------|--------------|------|
| `admin@kayedaw.fr` | `12345` | ADMIN |
| `user@kayedaw.fr` | `12345` | USER |

Les deux sont domiciliés à **Lille, France** : c'est ce lieu qui pré-remplit le
formulaire de séance et rend la météo visible dès le premier écran.

L'inscription publique force `Role.USER` : un ADMIN ne peut venir que de ce seed.

### Météo-France (optionnel)

Les séances passées sont enrichies avec les observations officielles Météo-France
(API DPClim) si le secret est fourni — sinon l'application bascule seule sur
Open-Meteo, sans configuration :

```bash
export METEOFRANCE_APPLICATION_ID="<base64 de client_id:client_secret>"
```

En local, `config/application.yml` (ignoré par git, non empaqueté dans le JAR)
fait le même travail : Spring Boot lit `./config/` en priorité sur le classpath.

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

**103 tests répartis sur 16 fichiers**, du test Kotlin pur au parcours de bout en bout.

| Niveau | Fichiers | Spring ? |
|--------|----------|----------|
| Kotlin pur | `ExtensionsTest`, `ResultatTest`, `AnalyseurCsvDpclimTest` | non (ms) |
| Règles d'administration | `SuppressionEnMasseTest`, `EditionAdministrativeTest` (MockK) | non |
| Intégrité des suppressions | `SuppressionCompleteTest` (`@SpringBootTest`, base réelle) | oui |
| Service + mocks MockK | `SeanceServiceTest`, `JwtServiceTest`, `StatistiquesServiceTest`, `EnrichissementMeteoServiceTest`, `PreferenceServiceTest` | non |
| Client HTTP sortant | `MeteoClientTest` (MockWebServer) | non |
| Persistance | `SeanceRepositoryTest` (`@DataJpaTest`) | partiel |
| Web | `SeanceControllerTest`, `AdminSecuriteTest` (`@WebMvcTest`) | partiel |
| Bout en bout | `ParcoursCompletE2ETest` (`@SpringBootTest`) | oui |

---

## API

### Public
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | `/api/auth/inscription` | Crée un compte, retourne un JWT |
| POST | `/api/auth/connexion` | Authentifie, retourne un JWT (**403** si le compte est bloqué) |

⚠️ La réponse d'authentification porte **la ville ET le pays** du compte. Le
client pré-remplit ses formulaires avec ce lieu : le pays manquait, il retombait
sur son repli « France », et un compte sénégalais ouvrait « Nouvelle séance » sur
Dakar / France — introuvable au géocodage, donc sans météo, alors que le profil
était juste. Une ville ne se géocode pas sans son pays ; les deux champs
voyagent ensemble.
| GET | `/api/meteo/pays` | Référentiel des 249 pays proposés à la saisie |
| GET | `/api/meteo/villes?q=&pays=` | Autocomplétion de villes — publique : elle sert à l'inscription |

`/api/meteo/pays` et `/api/meteo/villes` sont **publics à dessein** : ils
alimentent le formulaire d'inscription, donc avant toute session. Les protéger
faisait échouer les deux champs en silence. Aucune donnée personnelle n'y
transite ; la limitation de débit est un chantier de mise en production.

### Authentifié (JWT)
| Méthode | Route | Description |
|---------|-------|-------------|
| POST | `/api/seances` | Crée une séance (422 si règle métier violée) |
| GET | `/api/seances` | Liste paginée de **ses** séances |
| GET | `/api/seances/{id}` | Détail (403 si elle ne vous appartient pas) |
| GET | `/api/seances/type/{type}` | Filtre par type |
| GET | `/api/seances/statistiques?debut=&fin=` | Agrégats calculés en parallèle |
| GET | `/api/seances/records` | Records personnels |
| GET | `/api/seances/export.pdf` | **Export PDF** du carnet complet |
| PUT | `/api/seances/{id}` | Modifie |
| DELETE | `/api/seances/{id}` | Supprime |
| GET | `/api/profil` | Consulte son profil |
| PUT | `/api/profil` | Modifie son profil |
| PUT | `/api/profil/mot-de-passe` | Change son mot de passe |
| GET | `/api/meteo/conditions?ville=&pays=&dateHeure=` | Appel sortant : météo + qualité de l'air |
| GET | `/api/systeme/execution` | Diagnostic threads virtuels |

#### Préférences utilisateur

| Méthode | Route | Effet |
|---------|-------|-------|
| `GET` | `/api/profil/preferences` | Thème, langue et valeurs par défaut **des cinq types** |
| `PUT` | `/api/profil/preferences` | Remplace l'ensemble |

La réponse est **toujours complète** : les types jamais réglés sont servis avec
les valeurs d'usine (5 km / 60 min). Le client n'a donc aucun repli à inventer,
et un type ajouté à l'enum apparaît sans modification côté front.

L'écriture **remplace** (`delete` + `flush` + `insert`) au lieu de fusionner :
cinq lignes par compte, un différentiel n'apporterait que de la complexité. Le
`flush()` explicite est indispensable — sans lui la suppression et l'insertion
partent dans le désordre et la contrainte d'unicité `(utilisateur, type)` saute.

### Réservé ADMIN
| Méthode | Route | Description |
|---------|-------|-------------|
| GET | `/api/admin/utilisateurs` | Liste des comptes |
| GET | `/api/admin/utilisateurs/{id}/seances` | Séances d'un compte |
| PATCH | `/api/admin/utilisateurs/{id}/role` | Change le rôle |
| DELETE | `/api/admin/utilisateurs/{id}` | Supprime un compte |
| GET | `/api/admin/utilisateurs/export.pdf` | **Export PDF** de l'annuaire (ADMIN) |
| GET | `/api/admin/metriques` | Métriques de l'API |

#### Édition, blocage

| Méthode | Route | Effet |
|---------|-------|-------|
| `PATCH` | `/api/admin/utilisateurs/{id}` | Nom, ville, pays. **Pas l'email** : identifiant de connexion et clé unique |
| `PUT` | `/api/admin/utilisateurs/{id}/mot-de-passe` | Réinitialisation — l'ancien n'est **pas** demandé, un administrateur ne le connaît pas |
| `PATCH` | `/api/admin/utilisateurs/{id}/blocage` | Bloque ou débloque. Réversible, contrairement à la suppression |

Le blocage n'est pas un simple drapeau : `UserDetails.disabled` fait refuser
l'authentification **avant** la comparaison du mot de passe, et `JwtAuthFilter`
revérifie l'état à chaque requête — sans quoi un jeton déjà émis resterait
valable jusqu'à une heure. Un compte bloqué reçoit **403** et non 401 :
l'identité est correcte, c'est l'accès qui est suspendu.

Garde-fous : jamais soi-même, jamais le dernier administrateur — et pour le
blocage, jamais le dernier administrateur **ACTIF**, un admin déjà bloqué
n'administrant plus rien.

#### Suppression en masse

`DELETE /api/admin/utilisateurs` avec `{"ids": [3, 4, 7]}` renvoie **200 et un
rapport**, jamais 204 :

```json
{ "supprimes": [3, 4], "refuses": [{ "id": 7, "motif": "DERNIER_ADMINISTRATEUR", "detail": "…" }] }
```

Le résultat est **partiel par nature** : un identifiant refusé n'empêche pas les
autres de partir. L'implémentation réutilise la suppression unitaire en boucle —
un `deleteAllById` serait plus rapide et faux, les garde-fous étant des règles
par compte.

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

0. **Enrichissement météo** : si `ville` est fourni à la création, l'API interroge
   un service externe et **stocke** le résultat sur la séance. En cas de panne, la
   séance est créée sans ces données — une dépendance externe ne fait jamais
   échouer le cas d'usage principal.

   Le **pays accompagne la ville** et appartient à la séance : `pays` est un champ
   optionnel de la requête, à défaut duquel celui du compte sert de repli. On ne
   court pas toujours chez soi — le géocodage verrouillé sur le pays du compte
   renvoyait un homonyme français pour une séance à Dakar, ou rien du tout. Le
   pays reste indispensable pour lever l'homonymie : « Saint-Louis » désigne
   autant le Missouri que le Sénégal.

   ⚠️ `PUT /api/seances/{id}` ne touche **ni au lieu ni à la météo** : une
   observation passée ne change plus, et le client n'offre donc pas ces champs en
   modification.
1. **Horizon de planification** : une séance peut être planifiée jusqu'à 30 jours
   à l'avance (`kayedaw.entrainement.planification-max-jours`). Au-delà → HTTP 422
   `DATE_TROP_LOINTAINE`. ⚠️ Cet horizon DÉPASSE la portée des prévisions Open-Meteo,
   limitée en pratique à **J+14** : entre J+15 et J+30 la séance est valide mais
   revient sans météo — le front prévient dès la saisie.

   ⚠️ La borne utile est J+14, pas J+15. Mesuré sur l'API : J+14 rend une valeur,
   **J+15 est accepté mais ne rend RIEN**, et J+16 est refusé (« Parameter
   'start_date' is out of allowed range »). Prendre le dernier jour accepté pour
   le dernier jour couvert laissait une séance à J+15 échapper à l'avertissement
   tout en revenant sans mesure — précisément le cas qu'il doit couvrir.
2. **Plafond hebdomadaire** de 80 km (configurable) → HTTP 422 `PLAFOND_HEBDOMADAIRE`
3. **Propriété des données** : chacun ne voit que ses séances → HTTP 403

Les deux premières règles sont volontairement **asymétriques** vis-à-vis des
séances planifiées :

- elles **comptent** dans le plafond hebdomadaire — autant signaler le
  surentraînement dès la planification ;
- elles sont **exclues des statistiques** — les agrégats doivent refléter ce qui a
  réellement été couru (d'où la borne `maintenant` dans le JPQL de `SeanceRepository`).

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
│  ├─ WebClientConfig.kt        timeouts et pool du client HTTP sortant
│  ├─ DatasInitiales.kt         comptes de démonstration (@Profile("!prod"))
│  └─ VirtualThreadConfig.kt    Java 21 / Loom
├─ common/
│  ├─ Extensions.kt             fonctions d'extension
│  ├─ Resultat.kt               sealed interface (cas métier)
│  ├─ Exceptions.kt             exceptions (cas anormaux)
│  ├─ GestionErreurs.kt         @RestControllerAdvice
│  ├─ CompteurRequetes.kt       ReentrantLock vs synchronized
│  └─ SystemeController.kt      diagnostic Java 21
├─ meteo/                       appel sortant WebClient + coroutines
│  ├─ MeteoClient.kt            Open-Meteo : suspend + retry + repli
│  ├─ MeteoFranceClient.kt      DPClim : commande puis scrutation, parsing CSV
│  ├─ JetonMeteoFranceService.kt  OAuth2, cache du jeton, Mutex
│  ├─ EnrichissementMeteoService.kt  orchestration parallèle, choix de la source
│  ├─ Pays.kt                   référentiel ISO des 249 pays, libellés français
│  ├─ MeteoController.kt        /api/meteo
│  └─ MeteoDtos.kt              DTO tolérants aux champs inconnus
├─ security/                    JwtService, JwtAuthFilter, UserDetailsService
├─ auth/                        inscription / connexion
├─ user/                        entité Utilisateur, rôles, thème, langue, ProfilController
├─ seance/                      entité, DTO, repository, services, controller
├─ preference/                  valeurs par défaut par type de séance
└─ admin/                       endpoints réservés ADMIN (@PreAuthorize)
```

### Ce que la suppression d'un compte efface

Trois entités seulement : `Utilisateur`, `Seance`, `PreferenceSeance`. Les deux
dernières portent `utilisateur_id` et partent **avant** le compte, contrainte de
clé étrangère oblige. Les statistiques n'ont rien à effacer : elles sont
calculées à la volée depuis les séances.

`SuppressionCompleteTest` le vérifie sur la base réelle, et va plus loin : son
second test interroge `INFORMATION_SCHEMA` pour comparer les tables portant
`utilisateur_id` à celles que la suppression traite. Ajouter demain une table
liée sans compléter le nettoyage **fait échouer ce test** — il ne décrit pas le
présent, il protège l'avenir.

### Préférences : pourquoi un paquet à part

`PreferenceSeance` a besoin de `TypeSeance` (paquet `seance`), lequel dépend déjà
de `Utilisateur` (paquet `user`). Loger la relation dans `user` créerait un
**cycle entre paquets** ; `preference` dépend des deux et personne ne dépend de
lui. `Theme` et `Langue`, eux, sont deux valeurs uniques par compte : de simples
colonnes sur `Utilisateur`, une table séparée n'apporterait qu'une jointure.

L'unicité `(utilisateur, type)` est portée par une **contrainte de base**, pas
seulement par le code : c'est la seule garantie qui résiste à deux requêtes
concurrentes.

⚠️ `AdminService.supprimer` doit effacer les préférences **avant** l'utilisateur,
comme les séances. Toute nouvelle table référençant `utilisateur` s'ajoute là,
sinon la contrainte de clé étrangère rejette la suppression.

### D'où viennent les pays et les villes

Deux référentiels, deux mécanismes **opposés** — et la raison de cette
opposition est le point à retenir.

| | Pays | Villes |
|---|---|---|
| Source | le **JDK** : `Locale.getISOCountries()` + libellés CLDR | **Open-Meteo**, par le réseau |
| Quand | calculé une fois au **démarrage** | à **chaque frappe** dans le champ |
| Volume | 249, connus d'avance | des millions, inconnus d'avance |
| Hors ligne | fonctionne | ne fonctionne pas |
| Stocké en base | **non** | **non** |

Un référentiel **fermé et petit** se met en mémoire ; un référentiel **ouvert et
immense** s'interroge. Personne n'embarque les communes du monde pour remplir un
champ de saisie, et personne n'appelle un service tiers pour lister 249 pays.

**Les pays** (`meteo/Pays.kt`) sont un `object` Kotlin, donc un singleton
construit une seule fois par démarrage. Aucune table à maintenir, aucune faute
d'orthographe possible, et les libellés suivent les mises à jour **CLDR**
livrées avec le JDK — le projet tourne sur **OpenJDK 21**, qui donne 249 codes
et 249 libellés français.

⚠️ **Le NOM est stocké, pas le code.** « Sénégal » se lit dans une base et dans
un écran, « SN » non ; le code ISO est retrouvé à la volée par `Pays.code(nom)`
au moment d'interroger le géocodeur, parce que c'est le paramètre que l'API
attend. Conséquence à connaître : si une version ultérieure du JDK **renommait**
un pays, les comptes existants garderaient l'ancien libellé, qui ne figurerait
plus dans la liste proposée — le sélecteur afficherait alors son texte
d'invitation sur une valeur pourtant valide. Ce n'est pas un problème
aujourd'hui, c'est le prix assumé du choix.

**Les villes** ne sont écrites nulle part : aucune liste en dur, aucune table.
Une seule source, `url-geocodage`, et c'est elle qui répond à l'autocomplétion
comme à la résolution des coordonnées.

### Ce qui est conservé d'un lieu, et ce qui ne l'est pas

De toute cette machinerie, la base ne garde que **le nom retenu par
l'utilisateur** : `utilisateur.ville_par_defaut` et `utilisateur.pays` pour le
compte, `seance.ville` et `seance.pays` pour chaque sortie.

⚠️ **Les coordonnées ne sont PAS stockées.** Le géocodage sert à obtenir les
mesures, puis les coordonnées sont jetées et c'est **la météo** qui est
conservée. Le partage est volontaire : le géocodeur est consulté à la saisie et
à l'enregistrement, **jamais à l'affichage**. Une séance de l'an dernier reste
donc lisible avec ses mesures le jour où Open-Meteo est indisponible ou change
de contrat — et rien ne dépend d'une seconde résolution qui pourrait, elle,
répondre autre chose.

### Export PDF : pourquoi le serveur, et pas le navigateur

Deux points d'entrée — le carnet d'un membre, l'annuaire d'un administrateur —
produits par `common/DocumentPdf.kt` (Apache **PDFBox**).

Trois raisons de générer côté serveur :

- **l'autorisation y est déjà.** L'annuaire est réservé à ADMIN, vérifié par la
  règle d'URL *et* par `@PreAuthorize`. La refaire côté client serait une
  seconde règle à tenir, et un bouton caché ne protège rien : n'importe qui peut
  appeler l'URL. Un test le vérifie — **403** pour un membre ;
- **les données aussi.** L'export porte sur TOUT le carnet, pas sur la page
  affichée ni sur les filtres en cours. Un document dont le contenu dépend de
  l'état de l'écran au moment du clic est impossible à relire six mois plus tard ;
- **le fichier ne dépend de rien d'autre** : ni du navigateur, ni de sa
  configuration d'impression.

PDFBox plutôt qu'iText : licence **Apache 2.0**, sans clause AGPL qui obligerait
à publier le code appelant. Le prix est une API de bas niveau — on dessine du
texte à des coordonnées, sans notion de tableau ni de saut de ligne — d'où
l'utilitaire, écrit une fois.

⚠️ Trois pièges qui y sont déjà payés :

- **le saut de page se teste AVANT d'écrire**, jamais après : PDFBox ne connaît
  pas la marge basse et laisse la dernière ligne déborder sans rien signaler ;
- **l'en-tête est répété** sur chaque page — une page 2 sans intitulés de
  colonnes n'est qu'une grille de nombres ;
- **les polices Standard 14 LÈVENT** sur un caractère hors WinAnsi, au moment de
  l'écriture, donc au milieu d'un document à moitié construit : une ville en
  cyrillique suffirait à faire échouer l'export entier. `assainir()` les
  remplace en amont — mieux vaut un point d'interrogation dans une cellule
  qu'un 500 sur un document de quarante lignes.

⚠️ **L'annuaire ne contient aucun mot de passe**, même haché : un export
circule par courriel et atterrit dans un dossier de téléchargement. Il ne porte
que ce qui s'affiche déjà à l'écran d'administration.

### Sources météo

Trois sources, choisies par `EnrichissementMeteoService` et signalées au client
via le champ `sourceMeteo` :

| Cas | Source | Nature |
|-----|--------|--------|
| Séance passée, station disponible | `OBSERVATION_METEO_FRANCE` | observations réelles, température à l'heure de la séance |
| Séance passée, DPClim indisponible | `ARCHIVE_OPEN_METEO` | réanalyse, agrégats journaliers |
| Séance planifiée | `PREVISION_OPEN_METEO` | prévision (valeurs jusqu'à **J+14**) |

**Le monde entier, pas seulement la France.** Le géocodeur était verrouillé sur
`countryCode=FR` : aucune ville étrangère n'était trouvable, et la séance partait
sans lieu ni météo — en silence. Le pays du compte, puis celui de la séance,
lèvent l'homonymie (voir *D'où viennent les pays et les villes* ci-dessus). La
liste est servie par l'API plutôt qu'écrite en dur côté client : deux listes
divergentes rendraient sélectionnables des pays sans aucune ville trouvable.

**La profondeur de recherche est une règle, pas un réglage.** Le géocodeur
classe MONDIALEMENT avant de tronquer, *puis* applique le filtre de pays : une
commune modeste est évincée par ses homonymes plus peuplés bien avant que son
pays n'entre en jeu. Mesuré sur « bambi » au Sénégal — `count=20` : aucun
résultat sénégalais ; `count=50` : aucun ; `count=100` : **Bambilor**. D'où une
profondeur portée au maximum accepté. Les grandes villes restent en tête, rien
n'est perdu à chercher large.

⚠️ **Ce que le géocodeur ne sait pas faire** : il cherche par RESSEMBLANCE et
non par préfixe, en pénalisant l'écart de longueur. « bamb » lui évoque Banba,
Bamba ou Mbamb — tous de cinq lettres — jamais Bambilor. Aucun réglage ne
rattrape ce cas, et Nominatim, essayé en comparaison, fait pire. Le client
l'assume en affichant « continuez à saisir » plutôt qu'une liste vide, qui se
lirait « cette ville n'existe pas ».

Cinq détails déjà payés, à ne pas défaire :

- le rapprochement se fait sur le **code ISO** (`FR`, `SN`), le nom normalisé
  n'étant qu'un repli — le géocodeur ne répond pas toujours dans la langue de la
  requête ;
- `MINIMUM_RESULTATS = 2` contourne un bug d'Open-Meteo : avec `count=1` l'API
  renvoie parfois un résultat situé dans un autre pays. On en demande plusieurs,
  puis on filtre ;
- le **fuseau horaire** vient de la réponse du géocodeur ; codé en dur sur
  `Europe/Paris`, il décalait de deux heures la température « à l'heure de la
  séance » à Dakar ;
- les codes postaux ressemblent à `"69061 CEDEX 06"` : `departementDepuisCodePostal`
  ne garde que les chiffres de tête. Exiger une chaîne entièrement numérique
  cassait Lyon, Marseille et toutes les grandes villes ;
- la **région** (`admin1`) accompagne chaque suggestion, et ne se confond pas
  avec le département : celui-ci porte le NUMÉRO dont Météo-France a besoin pour
  trouver ses stations. Hors de France le code postal ne donne rien, et la liste
  n'affichait que des noms nus — rien ne les rattachait au pays choisi, ce qui se
  lisait comme du bruit venu d'ailleurs alors que le filtre était correct.

**La météo est stockée, pas recalculée.** Les colonnes vivent sur `Seance` et
sont renseignées une seule fois, à la création : une observation passée ne change
plus, et une séance de l'an dernier resterait sans mesure le jour où l'API tierce
est indisponible ou change de contrat.

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
