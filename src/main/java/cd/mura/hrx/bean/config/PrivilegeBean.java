package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.Privilege;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.service.AuthorizationService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Named
@ViewScoped
@Data
public class PrivilegeBean implements Serializable {

    @Inject
    private LoginBean loginBean;

    @EJB
    private PrivilegeDAO privilegeDAO;

    @Inject
    private MenuView menuView;

    @Inject
    private AuthorizationService authorization;

    private Privilege privilege;
    private Privilege privilegeSelected;
    private Menu menuSelected;

    private List<Privilege> privileges;
    private List<Privilege> privilegeListActif;
    private List<Privilege> privilegeListSelected;
    private List<Privilege> privilegesByMenu;
    private boolean allPrivilegesSelected;

    @PostConstruct
    private void init() {
        privilege = new Privilege();
        privilegeSelected = new Privilege();
        menuSelected = new Menu();
        privilegeListActif = new ArrayList<>();
        privilegeListSelected = new ArrayList<>();
        privileges = new ArrayList<>();
        privilegesByMenu = new ArrayList<>();
    }

    public void getAllByProfilMenu(Menu menu, Profil profil) {
        privileges = privilegeDAO.findAllByProfil(profil);
        privilegesByMenu = privilegeDAO.findAllByMenu(menu);
        updateAllPrivilegesSelected();
    }

    public List<Privilege> findAllByMenu(Menu menu) {
        privileges = privilegeDAO.findAllByMenu(menu);
        return privileges;
    }

    public Privilege findPrivilegeById(int id) {
        return privilegeDAO.findPrivilegeById(id);
    }

    public List<Privilege> findPrivilegeByProfil() {
        return privilegeDAO.findAllByProfil(loginBean.getUsers().getProfil());
    }

    public void save() {
        authorization.requirePrivilege("MOD_EDIT");
        if (menuSelected == null || menuSelected.getId() == 0) {
            showMessage(FacesMessage.SEVERITY_ERROR,
                    "Vous devez sélectionner un menu avant d’ajouter un privilège.");
            return;
        }

        privilege.setCreePar(loginBean.getUsers().getId());
        privilege.setMenu(menuSelected);

        if (privilegeDAO.persist(privilege) == 1) {
            showMessage(FacesMessage.SEVERITY_INFO, "Privilège ajouté avec succès.");
            privilege = new Privilege();
            findAllByMenu(menuSelected);
        }
    }

    public List<Privilege> update() {
        authorization.requirePrivilege("MOD_EDIT");
        privilegeSelected.setDateMaj(new Date());

        if (privilegeDAO.merge(privilegeSelected) == 1) {
            showMessage(FacesMessage.SEVERITY_INFO, "Mise à jour effectuée.");
        }

        return findAllByMenu(menuSelected);
    }

    public List<Privilege> remove(Privilege targetPrivilege) {
        authorization.requirePrivilege("MOD_EDIT");
        if (privilegeDAO.remove(targetPrivilege) == 1) {
            showMessage(FacesMessage.SEVERITY_INFO, "Privilège supprimé.");
        }

        return findAllByMenu(menuSelected);
    }

    public List<Privilege> changeStatus(int statut, Privilege targetPrivilege) {
        authorization.requirePrivilege("MOD_EDIT");
        targetPrivilege.setStatut(statut);
        if (privilegeDAO.merge(targetPrivilege) == 1) {
            showMessage(FacesMessage.SEVERITY_INFO, "Statut modifié avec succès.");
        }
        return findAllByMenu(menuSelected);
    }

    public void setProfilPrivilege(Privilege targetPrivilege, Profil profil, int mode) {
        authorization.requirePrivilege("PRF_GRANT");
        if (mode == 1) {
            privilegeDAO.removeProfilPrivilege(targetPrivilege, profil);
            privilegeDAO.removeProfilMenuWithoutActivePrivilege(targetPrivilege.getMenu(), profil);
            showMessage(FacesMessage.SEVERITY_INFO, "Privilège retiré du profil : " + profil.getNom());
        } else {
            privilegeDAO.addProfilPrivilege(targetPrivilege, profil);
            privilegeDAO.ensureProfilMenu(targetPrivilege.getMenu(), profil);
            showMessage(FacesMessage.SEVERITY_INFO, "Privilège ajouté au profil : " + profil.getNom());
        }

        privilegesByMenu = privilegeDAO.findAllByMenu(targetPrivilege.getMenu());
        privileges = privilegeDAO.findAllByProfil(profil);
        updateAllPrivilegesSelected();
        refreshCurrentMenu(profil);
    }

    public void toggleAllProfilPrivileges(Profil profil) {
        authorization.requirePrivilege("PRF_GRANT");
        if (menuSelected == null || menuSelected.getId() == 0 || privilegesByMenu.isEmpty()) {
            return;
        }

        if (allPrivilegesSelected) {
            privilegeDAO.addAllMenuPrivilegesToProfil(menuSelected, profil);
            privilegeDAO.ensureProfilMenu(menuSelected, profil);
            showMessage(FacesMessage.SEVERITY_INFO,
                    "Tous les privilèges ont été ajoutés au profil : " + profil.getNom());
        } else {
            privilegeDAO.removeAllMenuPrivilegesFromProfil(menuSelected, profil);
            privilegeDAO.removeProfilMenuWithoutActivePrivilege(menuSelected, profil);
            showMessage(FacesMessage.SEVERITY_INFO,
                    "Tous les privilèges ont été retirés du profil : " + profil.getNom());
        }

        privileges = privilegeDAO.findAllByProfil(profil);
        privilegesByMenu = privilegeDAO.findAllByMenu(menuSelected);
        updateAllPrivilegesSelected();
        refreshCurrentMenu(profil);
    }

    private void refreshCurrentMenu(Profil changedProfile) {
        if (menuView == null || loginBean == null || loginBean.getUsers() == null
                || loginBean.getUsers().getProfil() == null || changedProfile == null) return;
        if (loginBean.getUsers().getProfil().getId() == changedProfile.getId()) {
            menuView.loadFor(loginBean.getUsers());
        }
    }

    private void updateAllPrivilegesSelected() {
        if (privilegesByMenu == null || privilegesByMenu.isEmpty()) {
            allPrivilegesSelected = false;
            return;
        }

        List<Integer> assignedIds = privileges.stream()
                .map(Privilege::getId)
                .toList();
        allPrivilegesSelected = privilegesByMenu.stream()
                .allMatch(item -> assignedIds.contains(item.getId()));
    }

    public Privilege findPrivilegeByCode(String code) {
        return privilegeDAO.findPrivilegeByCode(code);
    }

    public boolean isPrivilegeInProfil(Privilege privilege, Profil profil) {
        List<Integer> ids = privilegeDAO.findAllByProfil(profil)
                .stream()
                .map(Privilege::getId)
                .toList();
        return ids.contains(privilege.getId());
    }

    public boolean hasPrivilege(String privilegeCode) {
        Privilege privilege = privilegeDAO.findPrivilegeByCode(privilegeCode);
        if (privilege == null) return false;

        List<Integer> ids = privilegeDAO.findAllByProfil(loginBean.getUsers().getProfil())
                .stream()
                .map(Privilege::getId)
                .toList();
        return ids.contains(privilege.getId());
    }

    private void showMessage(FacesMessage.Severity severity, String message) {
        FacesContext.getCurrentInstance()
                .addMessage(null, new FacesMessage(severity, "", message));
    }
}
