package cd.mura.hrx.bootstrap;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Amorçage de HRX au démarrage */
@Singleton
@Startup
public class HrxBootstrap {

    private static final Logger LOG = Logger.getLogger(HrxBootstrap.class.getName());
    private static final int SYSTEM_USER_ID = 0;
    public static final int ROOT_PROFIL_ID = 1;

    @PersistenceContext(unitName = "PU")
    private EntityManager em;

    @jakarta.ejb.EJB
    private cd.mura.hrx.dao.eval.NatureObjectifDAO natureObjectifDAO;
    @jakarta.ejb.EJB
    private cd.mura.hrx.dao.eval.ModeleFicheDAO modeleFicheDAO;

    @jakarta.ejb.EJB private cd.mura.hrx.service.CalendrierService calendrier;

    private record MenuDef(String nom, String url, String icone, String[][] privileges) { }

    private static final MenuDef[] ADMIN_MENUS = {
            new MenuDef("Utilisateurs", "users", "pi pi-users", new String[][]{
                    {"Consulter les utilisateurs", "USR_VIEW"}, {"Créer / modifier un utilisateur", "USR_EDIT"},
                    {"Importer depuis l'annuaire LDAP", "USR_LDAP"}, {"Réinitialiser un mot de passe", "USR_RESET"}}),
            new MenuDef("Profils et habilitations", "profils", "pi pi-shield", new String[][]{
                    {"Gérer les profils", "PRF_EDIT"}, {"Affecter menus et privilèges", "PRF_GRANT"}}),
            new MenuDef("Modules et menus", "module", "pi pi-th-large", new String[][]{{"Gérer les modules et menus", "MOD_EDIT"}}),
            new MenuDef("Groupes d'utilisateurs", "groupesUtilisateurs", "pi pi-users", new String[][]{{"Gérer les groupes", "GRP_EDIT"}}),
            new MenuDef("Directions", "directions", "pi pi-building", new String[][]{{"Gérer les directions", "DIR_EDIT"}}),
            new MenuDef("Organigramme", "organigramme", "pi pi-sitemap", new String[][]{{"Modifier l'organisation (N+1, direction, directeur)", "ORG_EDIT"}}),
            new MenuDef("Journal d'audit", "userLogs", "pi pi-history", new String[][]{{"Consulter le journal", "LOG_VIEW"}}),
            new MenuDef("Paramètres", "parametres", "pi pi-cog", new String[][]{{"Modifier la configuration", "CFG_EDIT"}}),
            new MenuDef("Jours fériés", "joursFeries", "pi pi-calendar-times", new String[][]{{"Gérer les jours fériés", "FERIE_EDIT"}}),
    };

    private static final MenuDef[] EVAL_MENUS = {
            new MenuDef("Campagnes", "campagnes", "pi pi-calendar", new String[][]{
                    {"Consulter les campagnes", "CAMP_VIEW"}, {"Créer et paramétrer une campagne", "CAMP_CREATE"},
                    {"Lancer / clôturer une campagne", "CAMP_LAUNCH"}}),
            new MenuDef("Modèles de fiche", "modelesFiche", "pi pi-file", new String[][]{
                    {"Gérer les modèles, critères et barèmes", "MDL_EDIT"}}),
            new MenuDef("Rubriques d'objectifs", "naturesObjectif", "pi pi-list", new String[][]{
                    {"Paramétrer les rubriques de la fiche d'objectifs", "MDL_EDIT"}}),
            new MenuDef("Mes objectifs", "objectifs", "pi pi-flag", new String[][]{
                    {"Consulter et proposer ses objectifs", "EVAL_SELF"}}),
            new MenuDef("Mes évaluations", "mesEvaluations", "pi pi-user-edit", new String[][]{
                    {"Renseigner et soumettre sa fiche", "EVAL_SELF"}}),
            new MenuDef("Mon équipe", "equipe", "pi pi-sitemap", new String[][]{
                    {"Évaluer et valider ses collaborateurs", "EVAL_N1"}}),
            new MenuDef("Suivi des évaluations", "evaluations", "pi pi-check-square", new String[][]{
                    {"Superviser toutes les évaluations", "EVAL_DCHT"}, {"Traiter les anomalies et relances", "EVAL_ANOM"}}),
            new MenuDef("Suivi des objectifs", "suiviObjectifs", "pi pi-flag-fill", new String[][]{
                    {"Suivre la fixation des objectifs et envoyer les rappels", "OBJ_SUIVI"}}),
            new MenuDef("Reporting", "reporting", "pi pi-chart-bar", new String[][]{
                    {"Consulter les tableaux de bord", "RPT_VIEW"}, {"Exporter (Excel / PDF)", "RPT_EXPORT"}}),
            new MenuDef("Périodes d'essai", "periodeEssai", "pi pi-hourglass", new String[][]{
                    {"Valider les fins de période d'essai", "ESSAI_DCHT"}}),
            new MenuDef("Évaluations formations", "evaluationsFormations", "pi pi-graduation-cap", new String[][]{
                    {"Gérer les évaluations des formations", "FORM_CHAUD"}}),
    };

    private static final MenuDef[] RECRUT_MENUS = {
            new MenuDef("Présélection des CV", "recrutement", "pi pi-briefcase", new String[][]{
                    {"Gérer les postes et analyser les CV", "RECRUT"}}),
            new MenuDef("CVthèque", "cvtheque", "pi pi-folder-open", new String[][]{
                    {"Consulter et ranger la CVthèque", "CVTHEQUE"}}),
    };

    @PostConstruct
    @TransactionAttribute(TransactionAttributeType.REQUIRED)
    public void bootstrap() {
        try {
            // Les ALTER/DROP hérités ne doivent jamais s'exécuter implicitement à
            // chaque redémarrage. Ils restent activables une fois, explicitement,
            // pour une ancienne base avec -Dhrx.legacy.schema.migrations.enabled=true.
            if (Boolean.getBoolean("hrx.legacy.schema.migrations.enabled")) {
                widenPrivilegeCode();
                migrerSchemaObjectifs();
                convertirLongsVarcharEnText();
                elargirColonne("besoin_formation", "origine", 20);
                elargirColonne("besoin_formation", "priorite", 10);
                elargirColonne("evaluation_critere", "niveau", 2);
                elargirColonneNullable("evaluation", "souhait_salarie", 120);
                LOG.warning("HRX : migrations de schéma historiques explicitement activées");
            }
            try { natureObjectifDAO.initialiserSiVide(); } catch (Exception e) { LOG.log(Level.WARNING, "HRX : rubriques d'objectifs non initialisees", e); }
            reparerNaturesOrphelines();
            try { modeleFicheDAO.initialiserSiVide(); } catch (Exception e) { LOG.log(Level.WARNING, "HRX : modele de fiche standard non initialise", e); }
            ensureRootProfil();
            if (isPremierDemarrage()) {
                int admin = ensureModule("Administration", "pi pi-cog");
                for (MenuDef def : ADMIN_MENUS) ensureMenu(admin, def);
                int eval = ensureModule("Évaluations", "pi pi-star");
                for (MenuDef def : EVAL_MENUS) ensureMenu(eval, def);
                int recrut = ensureModule("Recrutement", "pi pi-briefcase");
                for (MenuDef def : RECRUT_MENUS) ensureMenu(recrut, def);
                LOG.info("HRX : référentiel initial des modules, menus et privilèges créé");
            } else {
                // Les administrateurs gèrent ensuite le référentiel depuis l'écran Modules :
                // on ne recrée jamais un menu supprimé. On garantit seulement l'accès du profil root.
                regrantRoot();
                ajouterMenuSuiviObjectifs();
                ajouterMenuOrganigramme();
                ajouterMenuEval("periodeEssai", "ESSAI_DCHT");
                ajouterMenuEval("evaluationsFormations", "FORM_CHAUD");
                ajouterModuleRecrutement();
                ajouterMenuAdmin("joursFeries", "FERIE_EDIT");
            }
            ensureApp();
            try { calendrier.initialiserSiVide(); } catch (Exception e) { LOG.log(Level.WARNING, "HRX : jours fériés non initialisés", e); }
            LOG.info("HRX : amorçage terminé");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "HRX : amorçage impossible", e);
        }
    }

    /** Ajout du menu « Suivi des objectifs » sur une base existante, une seule fois (le privilège OBJ_SUIVI sert de témoin). */
    private void ajouterMenuSuiviObjectifs() {
        if (firstInt("select id from privilege where code = ?1", "OBJ_SUIVI") != null) return;
        Integer module = firstInt("select module_id from menu where url = ?1 order by id", "evaluations");
        if (module == null) module = firstInt("select module_id from menu where url = ?1 order by id", "campagnes");
        if (module == null) return;
        for (MenuDef def : EVAL_MENUS) if ("suiviObjectifs".equals(def.url())) ensureMenu(module, def);
        LOG.info("HRX : menu « Suivi des objectifs » ajouté");
    }

    /** Ajout du menu « Organigramme » sur une base existante, une seule fois (le privilège ORG_EDIT sert de témoin). */
    private void ajouterMenuOrganigramme() {
        if (firstInt("select id from privilege where code = ?1", "ORG_EDIT") != null) return;
        Integer module = firstInt("select module_id from menu where url = ?1 order by id", "directions");
        if (module == null) module = firstInt("select module_id from menu where url = ?1 order by id", "users");
        if (module == null) return;
        for (MenuDef def : ADMIN_MENUS) if ("organigramme".equals(def.url())) ensureMenu(module, def);
        LOG.info("HRX : menu « Organigramme » ajouté");
    }

    /** Ajout d'un menu du module Évaluations sur une base existante, une seule fois (son privilège sert de témoin). */
    private void ajouterMenuEval(String url, String privilegeTemoin) {
        if (firstInt("select id from privilege where code = ?1", privilegeTemoin) != null) return;
        Integer module = firstInt("select module_id from menu where url = ?1 order by id", "evaluations");
        if (module == null) module = firstInt("select module_id from menu where url = ?1 order by id", "campagnes");
        if (module == null) return;
        for (MenuDef def : EVAL_MENUS) if (url.equals(def.url())) ensureMenu(module, def);
        LOG.info("HRX : menu « " + url + " » ajouté");
    }

    /** Ajout d'un menu du module Administration sur une base existante, une seule fois (son privilège sert de témoin). */
    private void ajouterMenuAdmin(String url, String privilegeTemoin) {
        if (firstInt("select id from privilege where code = ?1", privilegeTemoin) != null) return;
        Integer module = firstInt("select module_id from menu where url = ?1 order by id", "parametres");
        if (module == null) module = firstInt("select module_id from menu where url = ?1 order by id", "users");
        if (module == null) return;
        for (MenuDef def : ADMIN_MENUS) if (url.equals(def.url())) ensureMenu(module, def);
        LOG.info("HRX : menu « " + url + " » ajouté");
    }

    /** Ajout du module « Recrutement » sur une base existante, une seule fois (le privilège RECRUT sert de témoin). */
    private void ajouterModuleRecrutement() {
        if (firstInt("select id from privilege where code = ?1", "RECRUT") != null) return;
        int module = ensureModule("Recrutement", "pi pi-briefcase");
        for (MenuDef def : RECRUT_MENUS) ensureMenu(module, def);
        LOG.info("HRX : module « Recrutement » ajouté");
    }

    /** Vrai tant qu'aucun module n'existe : la base vient d'être créée par Hibernate. */
    private boolean isPremierDemarrage() {
        Integer n = firstInt("select count(*) from module");
        return n == null || n == 0;
    }

    /** Le profil root reçoit tous les menus et privilèges actifs existants (filet de sécurité). */
    private void regrantRoot() {
        em.createNativeQuery("""
                insert into profil_menu (profil_id, menu_id)
                select ?1, m.id from menu m where m.statut = 1
                  and not exists (select 1 from profil_menu pm where pm.profil_id = ?1 and pm.menu_id = m.id)
                """).setParameter(1, ROOT_PROFIL_ID).executeUpdate();
        em.createNativeQuery("""
                insert into profil_privilege (privilege_id, profil_id)
                select p.id, ?1 from privilege p where p.statut = 1
                  and not exists (select 1 from profil_privilege pp where pp.profil_id = ?1 and pp.privilege_id = p.id)
                """).setParameter(1, ROOT_PROFIL_ID).executeUpdate();
    }

    /** Les codes de privilège HRX dépassent les 10 caractères hérités de clix ; hbm2ddl=update n'élargit pas une colonne. */
    private void widenPrivilegeCode() {
        try {
            Integer len = firstInt("""
                    select character_maximum_length from information_schema.columns
                    where table_schema = database() and table_name = 'privilege' and column_name = 'code'
                    """);
            if (len != null && len < 30) {
                em.createNativeQuery("alter table privilege modify code varchar(30)").executeUpdate();
                LOG.info("HRX : colonne privilege.code élargie à 30 caractères");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX : impossible d'élargir privilege.code", e);
        }
    }

    private void ensureRootProfil() {
        Integer id = firstInt("select id from profil where id = " + ROOT_PROFIL_ID);
        if (id == null) {
            em.createNativeQuery("""
                    insert into profil (id, nom, description, date_creation, date_maj, cree_par, statut, profil_par_defaut)
                    values (?1, 'Administrateur', 'Administration technique et fonctionnelle de HRX', current_date, current_date, ?2, 1, 0)
                    """).setParameter(1, ROOT_PROFIL_ID).setParameter(2, SYSTEM_USER_ID).executeUpdate();
        }
    }

    private int ensureModule(String nom, String icone) {
        Integer id = firstInt("select id from module where upper(nom) = upper(?1) order by id", nom);
        if (id == null) {
            em.createNativeQuery("""
                    insert into module (cree_par, date_creation, date_maj, icone, nom, statut, style)
                    values (?1, current_date, current_date, ?2, ?3, 1, '')
                    """).setParameter(1, SYSTEM_USER_ID).setParameter(2, icone).setParameter(3, nom).executeUpdate();
            id = firstInt("select id from module where upper(nom) = upper(?1) order by id", nom);
        }
        return id == null ? 0 : id;
    }

    private void ensureMenu(int moduleId, MenuDef def) {
        Integer id = firstInt("select id from menu where url = ?1 order by id", def.url());
        if (id == null) {
            em.createNativeQuery("""
                    insert into menu (cree_par, date_creation, date_maj, icone, nom, parent_id, statut, url, module_id)
                    values (?1, current_date, current_date, ?2, ?3, 0, 1, ?4, ?5)
                    """).setParameter(1, SYSTEM_USER_ID).setParameter(2, def.icone()).setParameter(3, def.nom())
                    .setParameter(4, def.url()).setParameter(5, moduleId).executeUpdate();
            id = firstInt("select id from menu where url = ?1 order by id", def.url());
        }
        if (id == null) return;
        grantMenu(id, ROOT_PROFIL_ID);
        for (String[] p : def.privileges()) ensurePrivilege(p[0], p[1], id);
    }

    private void ensurePrivilege(String nom, String code, int menuId) {
        Integer id = firstInt("select id from privilege where code = ?1 order by id", code);
        if (id == null) {
            em.createNativeQuery("""
                    insert into privilege (cree_par, date_creation, date_maj, nom, statut, menu_id, code)
                    values (?1, current_date, current_date, ?2, 1, ?3, ?4)
                    """).setParameter(1, SYSTEM_USER_ID).setParameter(2, nom).setParameter(3, menuId).setParameter(4, code).executeUpdate();
            id = firstInt("select id from privilege where code = ?1 order by id", code);
        }
        if (id == null) return;
        Integer linked = firstInt("select privilege_id from profil_privilege where privilege_id = ?1 and profil_id = ?2", id, ROOT_PROFIL_ID);
        if (linked == null) {
            em.createNativeQuery("insert into profil_privilege (privilege_id, profil_id) values (?1, ?2)")
                    .setParameter(1, id).setParameter(2, ROOT_PROFIL_ID).executeUpdate();
        }
    }

    private void grantMenu(int menuId, int profilId) {
        Integer linked = firstInt("select menu_id from profil_menu where menu_id = ?1 and profil_id = ?2", menuId, profilId);
        if (linked == null) {
            em.createNativeQuery("insert into profil_menu (profil_id, menu_id) values (?1, ?2)")
                    .setParameter(1, profilId).setParameter(2, menuId).executeUpdate();
        }
    }

    private void ensureApp() {
        Integer id = firstInt("select id from app order by id");
        if (id == null) {
            em.createNativeQuery("""
                    insert into app (nom_app, url, statut, date_creation, date_maj, admin_mail,
                                     notifications_actives, notification_jour, notification_heure, notification_minute)
                    values ('HRX', 'http://localhost:8080/hrx', 1, now(), now(), 'dsim@bgfi.com', 1, '1,2,3,4,5', '7', '0')
                    """).executeUpdate();
            LOG.warning("HRX : ligne app créée par défaut - importer la configuration LDAP/SMTP depuis clix (db/import_from_clix.sql)");
        }
    }

    /** L'objectif est rattache a la fiche de fixation (fiche_id) et non plus a une evaluation : on retire les anciennes colonnes
     *  que hbm2ddl=update ne supprime jamais. Sans effet sur une base deja a jour. */
    private void migrerSchemaObjectifs() {
        for (String col : new String[]{"evaluation_id", "realisation_salarie", "note_n1", "commentaire_salarie", "commentaire_n1", "description", "nature"}) {
            dropColumnIfExists("objectif", col);
        }
    }

    /** Objectifs créés avant la table des rubriques (nature_id = 0 posé par hbm2ddl) : on les rattache à la première rubrique. */
    private void reparerNaturesOrphelines() {
        try {
            Integer n = firstInt("select count(*) from objectif o where o.nature_id is null or o.nature_id = 0 or o.nature_id not in (select id from nature_objectif)");
            if (n == null || n == 0) return;
            int fixes = em.createNativeQuery("update objectif set nature_id = (select min(id) from nature_objectif) where nature_id is null or nature_id = 0 or nature_id not in (select id from nature_objectif)").executeUpdate();
            LOG.info("HRX : " + fixes + " objectif(s) rattache(s) a la premiere rubrique");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX : reparation des rubriques d'objectifs impossible", e);
        }
    }

    /** MySQL limite une ligne à 65 535 octets : les longs textes (>= 1000 caractères) passent en TEXT, hors ligne. */
    private void convertirLongsVarcharEnText() {
        try {
            List<?> rows = em.createNativeQuery("select table_name, column_name from information_schema.columns where table_schema = database() and data_type = 'varchar' and character_maximum_length >= 1000").getResultList();
            for (Object r : rows) {
                Object[] c = (Object[]) r;
                em.createNativeQuery("alter table " + c[0] + " modify " + c[1] + " text").executeUpdate();
                LOG.info("HRX : " + c[0] + "." + c[1] + " converti en TEXT");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX : conversion des colonnes longues impossible", e);
        }
    }

    /** Élargit un varchar existant (hbm2ddl=update ne modifie jamais une colonne). */
    private void elargirColonne(String table, String column, int longueur) {
        try {
            Integer len = firstInt("select character_maximum_length from information_schema.columns where table_schema = database() and table_name = ?1 and column_name = ?2", table, column);
            LOG.info("HRX : " + table + "." + column + " longueur actuelle = " + len);
            if (len == null) return;
            if (len < longueur) {
                em.createNativeQuery("alter table " + table + " modify " + column + " varchar(" + longueur + ") not null").executeUpdate();
                LOG.info("HRX : colonne " + table + "." + column + " elargie a " + longueur);
            }
        } catch (Exception e) { LOG.log(Level.WARNING, "HRX : impossible d'elargir " + table + "." + column + " : " + e.getMessage(), e); }
    }

    /** Élargit un varchar nullable (souhaits du salarié : plusieurs codes séparés par des virgules). */
    private void elargirColonneNullable(String table, String column, int longueur) {
        try {
            Integer len = firstInt("select character_maximum_length from information_schema.columns where table_schema = database() and table_name = ?1 and column_name = ?2", table, column);
            if (len != null && len < longueur) {
                em.createNativeQuery("alter table " + table + " modify " + column + " varchar(" + longueur + ") null").executeUpdate();
                LOG.info("HRX : colonne " + table + "." + column + " elargie a " + longueur);
            }
        } catch (Exception e) { LOG.log(Level.WARNING, "HRX : impossible d'elargir " + table + "." + column + " : " + e.getMessage(), e); }
    }

    private void dropColumnIfExists(String table, String column) {
        try {
            Integer n = firstInt("select count(*) from information_schema.columns where table_schema = database() and table_name = ?1 and column_name = ?2", table, column);
            if (n == null || n == 0) return;
            List<?> fks = em.createNativeQuery("select constraint_name from information_schema.key_column_usage where table_schema = database() and table_name = ?1 and column_name = ?2 and referenced_table_name is not null")
                    .setParameter(1, table).setParameter(2, column).getResultList();
            for (Object fk : fks) em.createNativeQuery("alter table " + table + " drop foreign key " + fk).executeUpdate();
            em.createNativeQuery("alter table " + table + " drop column " + column).executeUpdate();
            LOG.info("HRX : colonne " + table + "." + column + " supprimee (schema migre)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX : impossible de supprimer " + table + "." + column, e);
        }
    }

    private Integer firstInt(String sql, Object... params) {
        var q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
        q.setMaxResults(1);
        List<?> rows = q.getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return null;
        return ((Number) rows.get(0)).intValue();
    }
}
