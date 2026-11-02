# Déploiement HRX en production

## Avant le déploiement

1. Sauvegarder la base MySQL et vérifier la restauration sur un environnement isolé.
2. Appliquer les scripts versionnés de `db/migration` dans l'ordre.
3. Configurer le frontal HTTPS, transmettre à WildFly l'information que la requête est sécurisée et démarrer avec `-Dhrx.cookie.secure=true` (ou `HRX_COOKIE_SECURE=true`).
4. Vérifier que les cookies de session portent `Secure` et `HttpOnly`.
5. Utiliser le port LDAP 636 pour activer automatiquement LDAPS.
6. Exécuter `./mvnw test` puis `./mvnw package`.

## Migrations historiques

Les anciens `ALTER TABLE` du bootstrap sont désactivés par défaut. Pour migrer une ancienne base seulement, après sauvegarde et validation sur une copie :

```text
-Dhrx.legacy.schema.migrations.enabled=true
```

Retirer immédiatement cette option après le premier démarrage réussi. Elle ne doit pas rester dans la configuration permanente de WildFly.

## Contrôles après déploiement

- connexion locale et LDAP ;
- accès refusé à un écran non habilité ;
- création puis lancement d'une campagne de test ;
- enregistrement concurrent d'une fiche dans deux navigateurs ;
- envoi d'une notification de test ;
- présence des en-têtes CSP, HSTS, `X-Content-Type-Options` et `X-Frame-Options` ;
- consultation du journal d'audit sans contenu de champs RH ni identifiant de session brut.
