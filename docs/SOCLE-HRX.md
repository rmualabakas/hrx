# HRX

Application RH de BGFIBank RDC pour la fixation des objectifs et l'évaluation des collaborateurs (mi-parcours et fin d'année), selon le cahier des charges de la DCHT.

## Technique

Jakarta EE 10 sur WildFly 30, JSF/Facelets avec PrimeFaces 15 et OmniFaces, PrettyFaces pour les URL `/ui/...`, Hibernate (MySQL 8, datasource `java:/hrx`), Lombok, jBCrypt, authentification LDAP/AD ou locale. Exports PDF avec iText 8, Word et Excel avec Apache POI.

Package racine : `cd.mura.hrx`.

## Organisation du code

| Dossier | Contenu |
|---|---|
| `entity.config` | Comptes, profils, menus, privilèges, directions, journal, paramètres (`App`) |
| `entity.eval` | Campagnes, fiches d'objectifs, objectifs et rubriques, évaluations, compétences, formations |
| `dao` | Accès aux données (EJB `@Stateless`) |
| `bean.config` | Écrans d'administration : utilisateurs, profils, modules, directions, paramètres, journal |
| `bean.eval` | Écrans métier : tableau de bord, mon équipe, objectifs, fiche d'évaluation, campagnes, suivi, reporting, modèles |
| `service` | Cycle de vie des évaluations, notifications e-mail, exports, audit |
| `filter` | Contrôle d'accès aux pages `/ui/*` selon le profil |
| `bootstrap` | Initialisation au démarrage (menus, rubriques, modèle de fiche, ajustements de schéma) |

## Droits

`Utilisateur → Profil → Menus → Privilèges`. Le filtre `/ui/*` n'ouvre que les pages des menus du profil. Dans les vues, les actions sont conditionnées par `privilegeBean.hasPrivilege('CODE')`. Le profil 1 (root) a accès à tout.

Les rôles métier ne dépendent pas seulement du profil :
- **N+1** : déduit du supérieur hiérarchique renseigné sur le compte ;
- **Directeur** : désigné sur la direction (champ « Directeur ») ;
- **DCHT** : privilèges `EVAL_DCHT`, `CAMP_CREATE`, `RPT_VIEW`…

## Déroulé

1. La DCHT paramètre les rubriques d'objectifs et les modèles de fiche.
2. Le N+1 fixe les objectifs (début d'année ou arrivée), le collaborateur peut en proposer ; la fiche est signée par les deux.
3. La DCHT lance la campagne ; chaque N+1 intègre ses collaborateurs qui ont des objectifs validés.
4. Le collaborateur s'auto-évalue, le N+1 évalue, le directeur donne son avis (et peut corriger), la DCHT valide.
5. Suivi, relances et reporting par direction.

Chaque étape importante envoie un e-mail et laisse une trace dans le journal d'audit.

## Mise en route

1. Créer la datasource `java:/hrx` dans WildFly.
2. Insérer la ligne `app` (ou importer la configuration de clix avec `db/import_from_clix.sql`), le profil `root` (id 1) et un utilisateur local.
3. Déployer depuis IntelliJ ; au premier démarrage, HRX crée les menus et les données de référence manquantes.
4. Renseigner dans Paramètres l'URL de l'application, le SMTP et l'annuaire.

Après une modification Java, il faut recompiler et redéployer l'artefact ; « Update resources » ne recharge que les pages et le CSS.
