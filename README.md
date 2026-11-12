# TARIC : état courant PostgreSQL

Java 21, PostgreSQL 17, JDBC et lecture XLSX en flux avec Apache POI. Le snapshot fourni est daté du **2026-09-09**. Les fichiers restent hors du JAR et de Git.

## Exécuter

```bash
docker compose up -d
mvn -s settings-local.xml clean package
java -Xmx2g -jar target/taric-sanctions-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none \
  --taric.ingest.full=src/main/resources/taric/full \
  --taric.ingest.as-of=2026-09-09
```

La date est obligatoire : elle n'est jamais déduite des dates de copie des fichiers. Un nouvel import remplace l'état actif, dans une transaction. Les connexions PostgreSQL se configurent avec les propriétés Spring habituelles.

Démarrer ensuite l'API :

```bash
java -jar target/taric-sanctions-0.0.1-SNAPSHOT.jar
curl 'http://localhost:8080/measures?hs6=291411&direction=export&country=RU'
```

L'entrée `direction` est **vue de l'UE**. La colonne et le champ de réponse `direction` sont **vus du pays partenaire** : `Duties Export` devient `import`, `Duties Import` devient `export`. Exemple : export UE vers RU → recherche `import` / RU en base. Les codes pays sont en majuscules ; `XS` (Serbie TARIC) est normalisé en `RS`.

Le résultat signale une **restriction potentielle** : une seule subdivision concernée suffit. Il ne calcule ni les documents requis, ni les exemptions conditionnelles, ni l'applicabilité juridique complète.

## Modèle et règles

Sept tables métier : `goods`, `measures`, `measure_countries`, `measure_categories`, `footnotes`, `measure_footnotes`, `database_state`. Aucun taux, certificat, condition détaillée, type TARIC ou date de mesure n'y est conservé. Les références et URL juridiques figurent dans `measures`.

Le schéma séparé `taric_source` contient les lignes des neuf jeux de données nécessaires, leurs clés naturelles et un manifeste (provenance, SHA-256 des fichiers, version des règles). Les deux fichiers Duties constituent deux jeux séparés. Cette source garde les subdivisions, dates, conditions brutes et identifiants de rattachement nécessaires à une reconstruction. Les fichiers ignorés de la mission ne sont pas lus.

- **Validités** : début ≤ `as_of` ≤ fin ; une borne vide est ouverte. Les descriptions EN utilisent aussi leur date de début. Les dates restent exclusivement côté source.
- **HS6** : regroupement sur les six premiers chiffres. Les lignes de chapitre/position sont développées vers leurs descendants, et ne créent pas de faux HS6 parents. Le référentiel conserve les codes spéciaux TARIC des chapitres 98/99 ; ce n'est pas un validateur d'une édition internationale du HS.
- **Description** : priorité à la ligne exacte `HS6 + 0000`, suffixe `80` devant les lignes de regroupement `10`, puis code lexical et date de description la plus récente. Le chemin des ancêtres est ajouté selon l'indentation pour rendre « Other » / « Cartridges » compréhensible. Si la ligne HS6 exacte manque, choix déterministe de la première subdivision et avertissement `HS6_DESCRIPTION_FALLBACK` ; aucune concaténation de descriptions sœurs.
- **Identité métier** : HS6, sens, action, règlement, ensemble exact des pays et portée du contrôle. `geography_key` est le SHA-256 des pays triés. `scope_key` est le SHA-256 du libellé du type, du code additionnel, du contingent et du texte de conditions source. Il distingue notamment des contrôles différents du même règlement. Ces empreintes servent uniquement à l'égalité ; le moteur n'interprète aucun code TARIC. À signature identique, catégories et footnotes sont réunies par union.
- **Géographie** : résolution récursive et détection des cycles ; exclusion après développement des groupes. `1011` / ERGA OMNES couvre les codes pays/territoires actifs du référentiel ; `1008` conserve sa composition explicite. `EU` se résout via `1010`. Les codes territoriaux TARIC tels que `XI` sont conservés. Une zone inconnue, un groupe non résolu ou une footnote EN manquante bloque la publication.
- **Exclusions** : attention au fichier fourni : la colonne K, intitulée `Excluded country`, contient le **code**, et la colonne H contient le libellé malgré son intitulé `Excluded country code`. Le lecteur vérifie les en-têtes avant ingestion.
- **Types** : liste explicite dans `Rules`. Les types fiscaux sont ignorés ; un nouveau type non répertorié bloque l'ingestion au lieu de disparaître silencieusement.
- **URL** : référence TARIC interprétable + date de publication de `Legal basis` pour lever l'ambiguïté du siècle. Les doublons de publication utilisent la première date. Exemple : `Regulation 0111/05` → [CELEX 32005R0111](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32005R0111). Une référence non déterminable donne `NULL` et `EURLEX_UNRESOLVED` dans le rapport retourné et les logs du lanceur.

Catégories contrôlées, versionnées dans `Rules.VERSION` :

| Catégorie | Preuve déterministe |
|---|---|
| `dual_use` | Type 478, règlements 0821/21 ou 0428/09, références explicites correspondantes dans les footnotes |
| `military_list` | Type 738 explicitement CML ou footnote contenant « Common Military List » ; jamais le seul type 717 |
| `drug_precursor` | Règlements 0111/05 / 0273/04 ou footnote explicite, dont CD259 |
| `sanction` | Liste de règlements revue dans `Rules`, ou mention explicite « restrictive measures » / « sanctions » |
| `torture_repression` | Types 705, 706, 708 |
| `firearms` | Règlement 0258/12 |
| `chemical_control` | Types 479, 761 |
| `restricted_goods_or_technology` | Types 711, 717 |

`measure_categories.source` garde une preuve suffisante parmi `measure_type`, `legal_reference`, `footnote`. En cas de preuves multiples, le choix est déterministe ; les footnotes explicatives restent accessibles. Les règles ne sont pas une classification exhaustive de tout acte futur et n'utilisent aucun LLM.

## Publication et futures mises à jour

La construction utilise des tables PostgreSQL temporaires avec contraintes. Les relations sont vérifiées avant remplacement ; publication métier, état source, manifeste et `database_state` sont validés dans **le même commit**. Le remplacement utilise `DELETE` / `INSERT` sous MVCC : un lecteur garde l'ancien état jusqu'au commit. Un verrou transactionnel sérialise les ingestions. L'API lit ses mesures, catégories et footnotes dans une transaction `REPEATABLE READ`.

`Ingestion.daily` est le point d'entrée **pour un delta JSON normalisé**, pas encore un lecteur des XLSX Daily bruts :

```json
{
  "previousAsOf": "2026-09-09",
  "asOf": "2026-09-10",
  "changes": [
    {"operation": "DELETE", "table": "EXPORT", "key": "clé source SHA-256", "cells": null}
  ]
}
```

Pour `PUBLISH`, `cells` contient les cellules dans l'ordre vérifié de `Source.Table`, et `key` doit correspondre à `table.key(cells)`. Une suppression inconnue est refusée. Si une modification change la clé naturelle, l'adaptateur doit produire DELETE de l'ancienne clé puis PUBLISH de la nouvelle.

Le transport doit fournir **séparément** `VerifiedDownload(origin, sha256)`, depuis un manifeste de confiance. Le pipeline vérifie le checksum et l'origine HTTPS `ec.europa.eu`, puis la continuité des dates et la date active. Une URL déclarée dans un document ne prouve pas son origine : l'acquisition authentifiée reste à la charge du futur adaptateur.

Les exports full n'exposent pas de SID de mesure. Les clés source sont donc composées : marchandises, code additionnel, contingent, début, zone, type, règlement pour les mesures ; clés analogues avec code de footnote/pays exclu pour les associations. Les Daily présents utilisent notamment `PUBLISH=INSERT` et `REC_SEQ_NUM` : **ne pas assimiler ce numéro de séquence à un SID**. Leur décodage, la normalisation des dates/règlements et l'identification du sens restent à implémenter dans l'adaptateur XLSX/XML.

Après application des changements source, le pipeline reconsolide pour comparer les résultats par HS6 et ne remplace que ceux dont les mesures changent. Cette comparaison globale, volontairement simple, couvre aussi les références partagées, groupes géographiques et expirations au changement de date. Une future optimisation peut restreindre le calcul avec un index de dépendances, sans modifier le modèle métier. Une suppression de subdivision ne supprime donc jamais directement un HS6 consolidé.

## Tests

```bash
mvn -s settings-local.xml test
# PostgreSQL 17 jetable, indépendant de la base du projet ; nécessite Docker
mvn -s settings-local.xml -Dtaric.postgres.tests=true test
```

Les tests unitaires couvrent les règles métier et les cas limites. `FullSnapshotTest` lit les fichiers réels s'ils sont présents (sinon test explicitement ignoré). `PostgresIngestionTest` vérifie ingestion XLSX, recherche, date du snapshot, deltas, contrôle checksum/provenance, géographie partagée, expiration et rollback source/métier après échec de publication.

Validation du snapshot fourni : **27 tests réussis**, 5 717 codes à six chiffres (dont 104 codes spéciaux des chapitres 98/99), 72 135 mesures, 1 351 footnotes et 3 642 251 associations pays ; aucun avertissement. Sur le PostgreSQL local : environ **340 Mo métier**, index compris, et **163 Mo de source**. La relation pays constitue l'essentiel du volume. La recherche 291411 / export UE / RU a été vérifiée en HTTP ; son SQL utilise les deux clés indexées, sans parcours récursif.
