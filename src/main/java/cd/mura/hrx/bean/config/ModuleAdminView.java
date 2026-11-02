package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.MenuDAO;
import cd.mura.hrx.dao.config.ModuleDAO;
import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.ModuleP;
import cd.mura.hrx.entity.config.Privilege;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Écran « Modules » : navigation module → menus → privilèges en trois volets.
 * Reprend la logique clix (ModuleBean / MenuBean / PrivilegeBean + DAO) avec un seul état de sélection.
 */
@Named
@ViewScoped
@Getter
@Setter
public class ModuleAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private MenuView menuView;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private ModuleDAO moduleDAO;
    @EJB private MenuDAO menuDAO;
    @EJB private PrivilegeDAO privilegeDAO;
    @EJB private HabilitationAdminService habilitationAdminService;

    private List<ModuleP> modules = new ArrayList<>();
    private List<Menu> menus = new ArrayList<>();
    private List<Privilege> privileges = new ArrayList<>();

    private ModuleP selectedModule;
    private Menu selectedMenu;

    /** Objets en édition dans les dialogues (nouveau ou existant). */
    private ModuleP moduleEdit = new ModuleP();
    private Menu menuEdit = new Menu();
    private Privilege privilegeEdit = new Privilege();

    private String filtre = "";

    @PostConstruct
    public void init() {
        loadModules();
        if (!modules.isEmpty()) selectModule(modules.get(0));
    }

    // Modules

    public void loadModules() {
        modules = moduleDAO.findAll();
    }

    public List<ModuleP> getModulesFiltres() {
        if (filtre == null || filtre.isBlank()) return modules;
        String f = filtre.trim().toLowerCase(Locale.ROOT);
        return modules.stream().filter(m -> m.getNom() != null && m.getNom().toLowerCase(Locale.ROOT).contains(f)).toList();
    }

    public void selectModule(ModuleP module) {
        selectedModule = module;
        selectedMenu = null;
        privileges = new ArrayList<>();
        menus = module == null ? new ArrayList<>() : menuDAO.findAllByModule(module);
    }

    public void newModule() {
        moduleEdit = new ModuleP();
        moduleEdit.setIcone("pi pi-folder");
        moduleEdit.setStyle("");
    }

    public void editModule(ModuleP module) { moduleEdit = module; }

    public void saveModule() {
        authorization.requirePrivilege("MOD_EDIT");
        boolean creation = moduleEdit.getId() == 0;
        if (moduleEdit.getStyle() == null) moduleEdit.setStyle("");
        if (moduleEdit.getIcone() == null || moduleEdit.getIcone().isBlank()) moduleEdit.setIcone("pi pi-folder");
        int ok;
        if (creation) {
            moduleEdit.setCreePar(loginBean.getUsers().getId());
            moduleEdit.setDateCreation(new Date());
            moduleEdit.setDateMaj(new Date());
            ok = moduleDAO.persist(moduleEdit);
        } else {
            moduleEdit.setDateMaj(new Date());
            ok = moduleDAO.merge(moduleEdit);
        }
        if (ok == 1) {
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "ModuleP", moduleEdit.getId(),
                    (creation ? "Création" : "Modification") + " du module", moduleEdit.getNom(), true);
            info(creation ? "Module créé" : "Module mis à jour", moduleEdit.getNom());
            loadModules();
            ModuleP again = modules.stream().filter(m -> m.getNom().equals(moduleEdit.getNom())).findFirst().orElse(null);
            selectModule(again);
            refreshSidebar();
            moduleEdit = new ModuleP();
        } else {
            error("Enregistrement impossible", "Le nom du module existe peut-être déjà.");
        }
    }

    public void toggleModule(ModuleP module) {
        authorization.requirePrivilege("MOD_EDIT");
        module.setStatut(module.getStatut() == 1 ? 0 : 1);
        module.setDateMaj(new Date());
        if (moduleDAO.merge(module) == 1) {
            auditService.updated("Administration", "ModuleP", module.getId(), module.getStatut() == 1 ? "Module activé" : "Module désactivé", module.getNom());
            info(module.getStatut() == 1 ? "Module activé" : "Module désactivé", module.getNom());
            refreshSidebar();
        }
    }

    public void removeModule(ModuleP module) {
        authorization.requirePrivilege("MOD_EDIT");
        try {
            habilitationAdminService.deleteModule(module.getId());
            auditService.deleted("Administration", "ModuleP", module.getId(), "Suppression du module et de ses menus", module.getNom());
            info("Module supprimé", module.getNom());
            loadModules();
            selectModule(modules.isEmpty() ? null : modules.get(0));
            refreshSidebar();
        } catch (Exception e) {
            error("Suppression impossible", rootMessage(e));
        }
    }

    // Menus

    public void selectMenu(Menu menu) {
        selectedMenu = menu;
        privileges = menu == null ? new ArrayList<>() : privilegeDAO.findAllByMenu(menu);
    }

    public void newMenu() {
        menuEdit = new Menu();
        menuEdit.setIcone("pi pi-circle");
        menuEdit.setParentId(0);
    }

    public void editMenu(Menu menu) { menuEdit = menu; }

    /** Menus du module pouvant servir de parent (exclut le menu en cours d'édition). */
    public List<Menu> getParentsPossibles() {
        return menus.stream().filter(m -> menuEdit == null || m.getId() != menuEdit.getId()).toList();
    }

    public String parentLabel(Menu menu) {
        if (menu == null || menu.getParentId() <= 0) return "";
        return menus.stream().filter(m -> m.getId() == menu.getParentId()).map(Menu::getNom).findFirst().orElse("");
    }

    public void saveMenu() {
        authorization.requirePrivilege("MOD_EDIT");
        if (selectedModule == null) { error("Aucun module", "Sélectionnez d'abord un module."); return; }
        boolean creation = menuEdit.getId() == 0;
        if (menuEdit.getUrl() == null || menuEdit.getUrl().isBlank()) menuEdit.setUrl("#");
        if (menuEdit.getIcone() == null || menuEdit.getIcone().isBlank()) menuEdit.setIcone("pi pi-circle");
        int ok;
        if (creation) {
            menuEdit.setCreePar(loginBean.getUsers().getId());
            menuEdit.setModuleP(selectedModule);
            menuEdit.setStatut(1);
            menuEdit.setDateCreation(new Date());
            menuEdit.setDateMaj(new Date());
            ok = menuDAO.persist(menuEdit);
        } else {
            menuEdit.setDateMaj(new Date());
            ok = menuDAO.merge(menuEdit);
        }
        if (ok == 1) {
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "Menu", menuEdit.getId(),
                    (creation ? "Création" : "Modification") + " du menu", menuEdit.getNom() + " (" + menuEdit.getUrl() + ")", true);
            info(creation ? "Menu créé" : "Menu mis à jour", menuEdit.getNom());
            menus = menuDAO.findAllByModule(selectedModule);
            refreshSidebar();
            menuEdit = new Menu();
        } else {
            error("Enregistrement impossible", "Vérifiez le libellé et l'URL du menu.");
        }
    }

    public void toggleMenu(Menu menu) {
        authorization.requirePrivilege("MOD_EDIT");
        menu.setStatut(menu.getStatut() == 1 ? 0 : 1);
        menu.setDateMaj(new Date());
        if (menuDAO.merge(menu) == 1) {
            auditService.updated("Administration", "Menu", menu.getId(), menu.getStatut() == 1 ? "Menu activé" : "Menu désactivé", menu.getNom());
            info(menu.getStatut() == 1 ? "Menu activé" : "Menu désactivé", menu.getNom());
            refreshSidebar();
        }
    }

    public void removeMenu(Menu menu) {
        authorization.requirePrivilege("MOD_EDIT");
        try {
            long profils = habilitationAdminService.countProfilsForMenu(menu.getId());
            habilitationAdminService.deleteMenu(menu.getId());
            auditService.deleted("Administration", "Menu", menu.getId(), "Suppression du menu", menu.getNom() + " (désaffecté de " + profils + " profil(s))");
            info("Menu supprimé", menu.getNom());
            if (selectedMenu != null && selectedMenu.getId() == menu.getId()) selectMenu(null);
            menus = menuDAO.findAllByModule(selectedModule);
            refreshSidebar();
        } catch (Exception e) {
            error("Suppression impossible", rootMessage(e));
        }
    }

    // Arborescence des menus

    /** Menus racine du module sélectionné (parentId = 0 ou parent absent). */
    public List<Menu> getMenusRacine() {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Menu m : menus) ids.add(m.getId());
        return menus.stream().filter(m -> m.getParentId() <= 0 || !ids.contains(m.getParentId())).toList();
    }

    /** Sous-menus directs d'un menu. */
    public List<Menu> enfantsDe(Menu parent) {
        if (parent == null) return List.of();
        return menus.stream().filter(m -> m.getParentId() == parent.getId()).toList();
    }

    public boolean hasEnfants(Menu menu) { return !enfantsDe(menu).isEmpty(); }

    /** Un dossier est ouvert quand lui-même ou l'un de ses enfants est sélectionné. */
    public boolean isOuvert(Menu parent) {
        if (selectedMenu == null) return false;
        if (selectedMenu.getId() == parent.getId()) return true;
        return enfantsDe(parent).stream().anyMatch(c -> c.getId() == selectedMenu.getId());
    }

    // Privilèges

    public void newPrivilege() { privilegeEdit = new Privilege(); }

    public void editPrivilege(Privilege privilege) { privilegeEdit = privilege; }

    public void savePrivilege() {
        authorization.requirePrivilege("MOD_EDIT");
        if (selectedMenu == null) { error("Aucun menu", "Sélectionnez d'abord un menu."); return; }
        boolean creation = privilegeEdit.getId() == 0;
        if (privilegeEdit.getCode() != null) privilegeEdit.setCode(privilegeEdit.getCode().trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        int ok;
        if (creation) {
            privilegeEdit.setCreePar(loginBean.getUsers().getId());
            privilegeEdit.setMenu(selectedMenu);
            privilegeEdit.setStatut(1);
            privilegeEdit.setDateCreation(new Date());
            privilegeEdit.setDateMaj(new Date());
            ok = privilegeDAO.persist(privilegeEdit);
        } else {
            privilegeEdit.setDateMaj(new Date());
            ok = privilegeDAO.merge(privilegeEdit);
        }
        if (ok == 1) {
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "Privilege", privilegeEdit.getId(),
                    (creation ? "Création" : "Modification") + " du privilège", privilegeEdit.getCode(), true);
            info(creation ? "Privilège créé" : "Privilège mis à jour", privilegeEdit.getCode());
            privileges = privilegeDAO.findAllByMenu(selectedMenu);
            privilegeEdit = new Privilege();
        } else {
            error("Enregistrement impossible", "Le code du privilège doit être unique (30 caractères max).");
        }
    }

    public void togglePrivilege(Privilege privilege) {
        authorization.requirePrivilege("MOD_EDIT");
        privilege.setStatut(privilege.getStatut() == 1 ? 0 : 1);
        privilege.setDateMaj(new Date());
        if (privilegeDAO.merge(privilege) == 1) {
            auditService.updated("Administration", "Privilege", privilege.getId(), privilege.getStatut() == 1 ? "Privilège activé" : "Privilège désactivé", privilege.getCode());
            info(privilege.getStatut() == 1 ? "Privilège activé" : "Privilège désactivé", privilege.getCode());
        }
    }

    public void removePrivilege(Privilege privilege) {
        authorization.requirePrivilege("MOD_EDIT");
        try {
            habilitationAdminService.deletePrivilege(privilege.getId());
            auditService.deleted("Administration", "Privilege", privilege.getId(), "Suppression du privilège", privilege.getCode());
            info("Privilège supprimé", privilege.getCode());
            privileges = privilegeDAO.findAllByMenu(selectedMenu);
        } catch (Exception e) {
            error("Suppression impossible", rootMessage(e));
        }
    }

    // Helpers

    public int menuCount(ModuleP module) {
        return module == null ? 0 : menuDAO.findAllByModule(module).size();
    }

    public boolean isModuleSelected(ModuleP m) { return selectedModule != null && m != null && selectedModule.getId() == m.getId(); }
    public boolean isMenuSelected(Menu m) { return selectedMenu != null && m != null && selectedMenu.getId() == m.getId(); }

    private void refreshSidebar() {
        try { if (loginBean.getUsers() != null) menuView.loadFor(loginBean.getUsers()); } catch (Exception ignored) { }
    }

    /** Message lisible à partir d'une exception EJB/JTA imbriquée. */
    private String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        if (m == null || m.isBlank()) return "Une erreur technique est survenue.";
        if (m.contains("foreign key")) return "Cet élément est encore référencé ailleurs.";
        return m;
    }

    private void info(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, summary, detail)); }
    private void error(String summary, String detail) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, summary, detail)); }
}
