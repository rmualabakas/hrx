package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.MenuDAO;
import cd.mura.hrx.dao.config.ModuleDAO;
import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.dao.config.ProfilDAO;
import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.ModuleP;
import cd.mura.hrx.entity.config.Privilege;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.HabilitationAdminService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.*;

/** Écran « Profils et habilitations » */
@Named
@ViewScoped
@Getter
@Setter
public class ProfilAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private MenuView menuView;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private ProfilDAO profilDAO;
    @EJB private ModuleDAO moduleDAO;
    @EJB private MenuDAO menuDAO;
    @EJB private PrivilegeDAO privilegeDAO;
    @EJB private HabilitationAdminService habilitations;

    private List<Profil> profils = new ArrayList<>();
    private Profil selected;
    private Profil profilEdit = new Profil();
    private String filtre = "";

    /** Référentiel complet, chargé une fois par vue. */
    private List<ModuleP> modules = new ArrayList<>();
    private Map<Integer, List<Menu>> menusByModule = new LinkedHashMap<>();
    private Map<Integer, List<Privilege>> privilegesByMenu = new LinkedHashMap<>();

    /** Habilitations du profil sélectionné. */
    private Set<Integer> menuIds = new HashSet<>();
    private Set<Integer> privilegeIds = new HashSet<>();
    private Map<Integer, Long> usersByProfil = new HashMap<>();

    /** Copie d'habilitations : profil source choisi dans le dialogue. */
    private Integer copieSourceId;

    @PostConstruct
    public void init() {
        loadReferentiel();
        loadProfils();
        if (!profils.isEmpty()) select(profils.get(0));
    }

    // Chargement

    private void loadReferentiel() {
        modules = moduleDAO.findAll();
        menusByModule.clear();
        privilegesByMenu.clear();
        for (ModuleP mod : modules) {
            List<Menu> menus = enOrdreArbre(menuDAO.findAllByModule(mod));
            menusByModule.put(mod.getId(), menus);
            for (Menu m : menus) privilegesByMenu.put(m.getId(), privilegeDAO.findAllByMenu(m));
        }
    }

    public void loadProfils() {
        profils = profilDAO.findAll();
        usersByProfil.clear();
        for (Profil p : profils) usersByProfil.put(p.getId(), habilitations.countUsersForProfil(p.getId()));
    }

    public List<Profil> getProfilsFiltres() {
        if (filtre == null || filtre.isBlank()) return profils;
        String f = filtre.trim().toLowerCase(Locale.ROOT);
        return profils.stream().filter(p -> p.getNom() != null && p.getNom().toLowerCase(Locale.ROOT).contains(f)).toList();
    }

    public void select(Profil profil) {
        selected = profil;
        if (profil == null) { menuIds = new HashSet<>(); privilegeIds = new HashSet<>(); return; }
        menuIds = habilitations.menuIdsForProfil(profil.getId());
        privilegeIds = habilitations.privilegeIdsForProfil(profil.getId());
    }

    private void reloadSelection() {
        if (selected != null) select(selected);
        refreshSidebarIfConcerned();
    }

    /** Parents (par nom) puis, sous chacun, ses sous-menus (par nom) : l'ordre d'affichage suit la hiérarchie. */
    private static List<Menu> enOrdreArbre(List<Menu> flat) {
        Set<Integer> ids = new HashSet<>();
        for (Menu m : flat) ids.add(m.getId());
        Comparator<Menu> parNom = Comparator.comparing(m -> m.getNom() == null ? "" : m.getNom(), String.CASE_INSENSITIVE_ORDER);
        List<Menu> out = new ArrayList<>();
        flat.stream().filter(m -> m.getParentId() <= 0 || !ids.contains(m.getParentId())).sorted(parNom).forEach(root -> {
            out.add(root);
            ajouterEnfants(flat, root, out, parNom, 0);
        });
        return out;
    }

    private static void ajouterEnfants(List<Menu> flat, Menu parent, List<Menu> out, Comparator<Menu> parNom, int depth) {
        if (depth > 5) return;
        flat.stream().filter(m -> m.getParentId() == parent.getId()).sorted(parNom).forEach(c -> {
            out.add(c);
            ajouterEnfants(flat, c, out, parNom, depth + 1);
        });
    }

    /** Profondeur d'un menu dans son module (0 = racine), pour l'indentation. */
    public int niveau(Menu menu) {
        int depth = 0; Menu cur = menu;
        List<Menu> all = new ArrayList<>();
        menusByModule.values().forEach(all::addAll);
        while (cur != null && cur.getParentId() > 0 && depth < 6) {
            int pid = cur.getParentId();
            cur = all.stream().filter(m -> m.getId() == pid).findFirst().orElse(null);
            if (cur != null) depth++;
        }
        return depth;
    }

    /** Vrai si le menu a des sous-menus (pour afficher le bouton replier/déplier). */
    public boolean hasEnfants(Menu menu) {
        return menusByModule.values().stream().flatMap(List::stream).anyMatch(m -> m.getParentId() == menu.getId());
    }

    /** Vrai si retirer ce menu au profil sélectionné enfermerait l'utilisateur courant hors de cet écran. */
    private boolean autoVerrouillage(Menu menu) {
        return selected != null && loginBean.getUsers() != null && loginBean.getUsers().getProfil() != null
                && loginBean.getUsers().getProfil().getId() == selected.getId()
                && "profils".equalsIgnoreCase(menu.getUrl());
    }

    // Lecture pour la vue

    public List<Menu> menusOf(ModuleP module) { return menusByModule.getOrDefault(module.getId(), List.of()); }
    public List<Privilege> privilegesOf(Menu menu) { return privilegesByMenu.getOrDefault(menu.getId(), List.of()); }
    public boolean hasMenu(Menu menu) { return menuIds.contains(menu.getId()); }
    public boolean hasPrivilege(Privilege p) { return privilegeIds.contains(p.getId()); }
    public boolean isSelected(Profil p) { return selected != null && p != null && selected.getId() == p.getId(); }
    public long usersOf(Profil p) { return usersByProfil.getOrDefault(p.getId(), 0L); }

    /** Nombre de menus du module accordés au profil. */
    public int grantedMenusOf(ModuleP module) {
        return (int) menusOf(module).stream().filter(this::hasMenu).count();
    }
    public int grantedPrivilegesOf(Menu menu) {
        return (int) privilegesOf(menu).stream().filter(this::hasPrivilege).count();
    }
    public boolean isModuleComplete(ModuleP module) {
        List<Menu> menus = menusOf(module);
        return !menus.isEmpty() && grantedMenusOf(module) == menus.size();
    }
    public int getTotalMenus() { return menuIds.size(); }
    public int getTotalPrivileges() { return privilegeIds.size(); }

    // Profil : CRUD

    public void newProfil() { profilEdit = new Profil(); }
    public void editProfil(Profil p) { profilEdit = p; }

    public void saveProfil() {
        authorization.requirePrivilege("PRF_EDIT");
        boolean creation = profilEdit.getId() == 0;
        Profil result;
        if (creation) {
            profilEdit.setCreePar(loginBean.getUsers().getId());
            profilEdit.setDateCreation(new Date());
            profilEdit.setDateMaj(new Date());
            profilEdit.setStatut(1);
            if (profilEdit.getProfilParDefaut() == null) profilEdit.setProfilParDefaut(Boolean.FALSE);
            if (profilEdit.getDescription() == null) profilEdit.setDescription("");
            result = profilDAO.persist(profilEdit);
        } else {
            profilEdit.setDateMaj(new Date());
            result = profilDAO.merge(profilEdit) == 1 ? profilEdit : null;
        }
        if (result != null && result.getId() > 0) {
            if (Boolean.TRUE.equals(result.getProfilParDefaut())) profilDAO.updateDefault(result.getId());
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "Profil", result.getId(),
                    (creation ? "Création" : "Modification") + " du profil", result.getNom(), true);
            info(creation ? "Profil créé" : "Profil mis à jour", result.getNom());
            loadProfils();
            Profil again = profils.stream().filter(p -> p.getId() == result.getId()).findFirst().orElse(null);
            select(again);
            profilEdit = new Profil();
        } else {
            error("Enregistrement impossible", "Le nom du profil existe peut-être déjà.");
        }
    }

    public void toggleStatut(Profil p) {
        authorization.requirePrivilege("PRF_EDIT");
        if (p.getId() == 1) { error("Action refusée", "Le profil administrateur ne peut pas être désactivé."); return; }
        p.setStatut(p.getStatut() == 1 ? 0 : 1);
        p.setDateMaj(new Date());
        if (profilDAO.merge(p) == 1) {
            auditService.updated("Administration", "Profil", p.getId(), p.getStatut() == 1 ? "Profil activé" : "Profil désactivé", p.getNom());
            info(p.getStatut() == 1 ? "Profil activé" : "Profil désactivé", p.getNom());
        }
    }

    public void setDefault(Profil p) {
        p.setProfilParDefaut(Boolean.TRUE);
        p.setDateMaj(new Date());
        if (profilDAO.merge(p) == 1) {
            profilDAO.updateDefault(p.getId());
            auditService.updated("Administration", "Profil", p.getId(), "Profil par défaut", p.getNom());
            info("Profil par défaut", p.getNom() + " sera attribué aux nouveaux comptes importés.");
            loadProfils();
        }
    }

    public void removeProfil(Profil p) {
        authorization.requirePrivilege("PRF_EDIT");
        if (p.getId() == 1) { error("Action refusée", "Le profil administrateur ne peut pas être supprimé."); return; }
        try {
            habilitations.deleteProfil(p.getId());
            auditService.deleted("Administration", "Profil", p.getId(), "Suppression du profil", p.getNom());
            info("Profil supprimé", p.getNom());
            loadProfils();
            select(profils.isEmpty() ? null : profils.get(0));
        } catch (Exception e) {
            error("Suppression impossible", rootMessage(e));
        }
    }

    // Habilitations

    public void toggleMenu(Menu menu) {
        authorization.requirePrivilege("PRF_GRANT");
        if (selected == null) return;
        if (hasMenu(menu)) {
            if (autoVerrouillage(menu)) { error("Action refusée", "Vous ne pouvez pas retirer à votre propre profil l'accès à cet écran."); return; }
            habilitations.revokeMenu(selected.getId(), menu.getId());
            audit("Menu retiré", menu.getNom());
        } else {
            habilitations.grantMenu(selected.getId(), menu.getId());
            audit("Menu accordé", menu.getNom());
        }
        reloadSelection();
    }

    public void togglePrivilege(Privilege p) {
        authorization.requirePrivilege("PRF_GRANT");
        if (selected == null) return;
        if (hasPrivilege(p)) {
            habilitations.revokePrivilege(selected.getId(), p.getId());
            audit("Privilège retiré", p.getCode());
        } else {
            habilitations.grantPrivilege(selected.getId(), p.getId());
            audit("Privilège accordé", p.getCode());
        }
        reloadSelection();
    }

    public void grantAllPrivileges(Menu menu) {
        if (selected == null) return;
        habilitations.grantAllPrivileges(selected.getId(), menu.getId());
        audit("Tous les privilèges accordés", menu.getNom());
        reloadSelection();
    }

    public void toggleModule(ModuleP module) {
        authorization.requirePrivilege("PRF_GRANT");
        if (selected == null) return;
        if (isModuleComplete(module)) {
            if (menusOf(module).stream().anyMatch(this::autoVerrouillage)) { error("Action refusée", "Ce module contient l'écran des habilitations de votre propre profil."); return; }
            habilitations.revokeModule(selected.getId(), module.getId());
            audit("Module retiré", module.getNom());
            info("Module retiré", module.getNom() + " n'est plus visible pour « " + selected.getNom() + " »");
        } else {
            habilitations.grantModule(selected.getId(), module.getId());
            audit("Module accordé", module.getNom());
            info("Module accordé", module.getNom() + " : tous les menus et privilèges pour « " + selected.getNom() + " »");
        }
        reloadSelection();
    }

    public void copierDepuis() {
        if (selected == null || copieSourceId == null || copieSourceId == selected.getId()) {
            error("Copie impossible", "Choisissez un profil source différent."); return;
        }
        habilitations.copyHabilitations(copieSourceId, selected.getId());
        String source = profils.stream().filter(p -> p.getId() == copieSourceId).map(Profil::getNom).findFirst().orElse("?");
        audit("Habilitations copiées", "depuis " + source);
        info("Habilitations copiées", "Les droits de « " + source + " » ont été ajoutés à « " + selected.getNom() + " »");
        copieSourceId = null;
        reloadSelection();
    }

    public List<Profil> getAutresProfils() {
        return profils.stream().filter(p -> selected == null || p.getId() != selected.getId()).toList();
    }

    // Helpers

    private void audit(String summary, String detail) {
        auditService.updated("Administration", "Profil", selected == null ? null : selected.getId(),
                summary + " - profil " + (selected == null ? "" : selected.getNom()), detail);
    }

    private void refreshSidebarIfConcerned() {
        try {
            if (loginBean.getUsers() != null && loginBean.getUsers().getProfil() != null && selected != null
                    && loginBean.getUsers().getProfil().getId() == selected.getId()) {
                menuView.loadFor(loginBean.getUsers());
            }
        } catch (Exception ignored) { }
    }

    private String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return m == null || m.isBlank() ? "Une erreur technique est survenue." : m;
    }

    private void info(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail)); }
    private void error(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail)); }
}
