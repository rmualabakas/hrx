-- =====================================================================
-- HRX - import des données de base depuis la base clix (même serveur MySQL)
-- ---------------------------------------------------------------------
-- À exécuter UNE FOIS dans MySQL Workbench, APRÈS le premier démarrage de HRX
-- (Hibernate a créé les tables, HrxBootstrap a créé le profil 1, les modules,
--  menus et privilèges HRX).
-- Adapter si besoin : le nom de la base clix (ici `charisma`) et le login (`ramses`).
-- =====================================================================

-- Utilisateur repris : 'ramses' (littéral partout : une variable @LOGIN provoque un conflit de collation general_ci / unicode_ci)

-- Les requêtes ci-dessous utilisent le nom de base en dur : remplacer `charisma.` si différent.

USE hrx;

-- 1) Configuration application : LDAP, SMTP, répertoires, relances -------------
--    (remplace la ligne par défaut créée par HrxBootstrap)
DELETE FROM app;
INSERT INTO app (nom_app, url, statut, date_creation, date_maj, admin_mail,
                 host_smtp, expediteur_smtp, email_expediteur_smtp, type_smtp, user_smpt, password_smpt, port_smtp,
                 ldap_server_name, ldap_domain_name, ldap_port, ldap_base_dn, ldap_bind_user, ldap_bind_password,
                 rep_base_fichier, logo, notifications_actives, notification_jour, notification_heure, notification_minute)
SELECT 'HRX', 'http://localhost:8080/hrx', 1, NOW(), NOW(), admin_mail,
       host_smtp, 'HRX - Évaluations', email_expediteur_smtp, type_smtp, user_smpt, password_smpt, port_smtp,
       ldap_server_name, ldap_domain_name, ldap_port, ldap_base_dn, ldap_bind_user, ldap_bind_password,
       CONCAT(IFNULL(rep_base_fichier, '/opt/hrx'), '/hrx'), logo, 1, '1,2,3,4,5', '7', '0'
FROM charisma.app ORDER BY id LIMIT 1;

-- 2) Profil(s) : celui de l'utilisateur repris + le profil 1 de clix (nom conservé) -----
INSERT INTO profil (id, nom, description, date_creation, date_maj, cree_par, statut, profil_par_defaut)
SELECT p.id, p.nom, p.description, p.date_creation, p.date_maj, p.cree_par, p.statut, IFNULL(p.profil_par_defaut, 0)
FROM charisma.profil p
WHERE p.id = 1 OR p.id IN (SELECT u.profil_id FROM charisma.users u WHERE u.login = 'ramses')
ON DUPLICATE KEY UPDATE nom = VALUES(nom), description = VALUES(description), statut = 1;

-- 3) Directions (référentiel des entités, utile au taux de réalisation par direction) -----
INSERT IGNORE INTO direction (id, nom, description, abrege, statut, date_creation, date_maj, cree_par, maj_par, dirige_par)
SELECT id, nom, description, abrege, statut, date_creation, date_maj, cree_par, maj_par, dirige_par FROM charisma.direction;

-- 4) Utilisateur repris (mot de passe = même hash BCrypt, donc le compte local fonctionne) -----
INSERT INTO users (id, nom, prenom, email, login, password, d_creation, d_maj, last_login, cree_par,
                   statut, editable, pass_change, avatar, phone, profil_id, superieur_id, evaluable)
SELECT u.id, u.nom, u.prenom, u.email, u.login, u.password, u.d_creation, u.d_maj, NULL, u.cree_par,
       1, u.editable, 0, u.avatar, u.phone, u.profil_id, NULL, 1
FROM charisma.users u WHERE u.login = 'ramses'
ON DUPLICATE KEY UPDATE password = VALUES(password), profil_id = VALUES(profil_id), statut = 1, pass_change = 0;

-- 5) Donner tous les menus et privilèges HRX au profil de cet utilisateur -----------------
INSERT IGNORE INTO profil_menu (profil_id, menu_id)
SELECT u.profil_id, m.id FROM users u CROSS JOIN menu m WHERE u.login = 'ramses' AND m.statut = 1;

INSERT IGNORE INTO profil_privilege (privilege_id, profil_id)
SELECT pr.id, u.profil_id FROM users u CROSS JOIN privilege pr WHERE u.login = 'ramses' AND pr.statut = 1;

-- 6) Préférences : thème et page d'accueil par défaut -----------------------------------
INSERT IGNORE INTO user_preference (user_id, compact_mode, session_notifications, default_page, theme_code, menu_orientation, date_creation)
SELECT id, 0, 1, '/ui/home', 'BGFI_ELEGANCE', 'SIDEBAR_LEFT', NOW() FROM users WHERE login = 'ramses';

-- Contrôle
SELECT u.login, u.email, p.nom AS profil, u.statut, u.pass_change,
       (SELECT COUNT(*) FROM profil_menu pm WHERE pm.profil_id = u.profil_id) AS menus,
       (SELECT COUNT(*) FROM profil_privilege pp WHERE pp.profil_id = u.profil_id) AS privileges
FROM users u JOIN profil p ON p.id = u.profil_id WHERE u.login = 'ramses';
SELECT nom_app, ldap_server_name, ldap_domain_name, ldap_port, host_smtp FROM app;
