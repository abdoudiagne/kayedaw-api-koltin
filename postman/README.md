# Collection Postman — KayeDaw API

24 requêtes, 48 assertions automatiques.

## Import

1. Ouvrir Postman → **Import**
2. Glisser les deux fichiers :
   - `KayeDaw-API.postman_collection.json` (la collection)
   - `KayeDaw-Local.postman_environment.json` (l'environnement)
3. En haut à droite, sélectionner l'environnement **KayeDaw — Local**

## Démarrer l'API

```bash
cd ..
mvn spring-boot:run     # http://localhost:8080
```

## Utilisation

**Le jeton JWT est capturé automatiquement.** Lancez d'abord
`1. Authentification > Inscription` : le script de test enregistre le jeton dans
la variable `token`, que toutes les requêtes suivantes réutilisent via
l'authentification `Bearer` héritée au niveau de la collection.

De même, l'identifiant de séance créé est stocké dans `seanceId` et réutilisé
par les requêtes de lecture, modification et suppression.

Un email unique est généré à chaque exécution (`abdou.<timestamp>@test.fr`),
ce qui permet de relancer la collection autant de fois que voulu sans conflit.

## Exécuter toute la collection

Bouton **Run** (Runner) → *Run KayeDaw API*. Les requêtes s'exécutent dans
l'ordre, avec leurs assertions. Tout doit être au vert.

## Contenu

| Dossier | Contenu |
|---------|---------|
| 1. Authentification | inscription, connexion, 401 mauvais mot de passe, 409 doublon, 400 validation |
| 2. Séances (CRUD) | création, liste paginée, détail, filtre par type, statistiques, modification, suppression, vérification 404 |
| 3. Sécurité & erreurs | 401 sans jeton, 401 jeton invalide, 400 validation, 404, isolation entre utilisateurs (403), 422 règle métier |
| 4. Système (Java 21) | diagnostic threads virtuels, actuator health |

## En ligne de commande (CI)

Avec [Newman](https://github.com/postmanlabs/newman) :

```bash
npm install -g newman

newman run KayeDaw-API.postman_collection.json \
  -e KayeDaw-Local.postman_environment.json \
  --reporters cli,junit --reporter-junit-export resultats.xml
```

Le rapport JUnit s'intègre directement dans GitHub Actions ou GitLab CI.

## Notes

- L'ordre des requêtes compte : `Inscription` doit être jouée en premier pour
  alimenter le jeton.
- Le test « plafond hebdo (422) » utilise une date éloignée (2026-08-12) pour ne
  pas interférer avec les séances créées précédemment.
- Le test « Diagnostic — threads virtuels » échouera si l'API tourne sur une JVM
  antérieure à 21 ou si `spring.threads.virtual.enabled` est à `false`.
