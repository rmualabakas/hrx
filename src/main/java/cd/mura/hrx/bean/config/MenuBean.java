package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.MenuDAO;
import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.entity.config.Menu;
import cd.mura.hrx.entity.config.ModuleP;
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
public class MenuBean implements Serializable {

    @Inject
    private AuthorizationService authorization;

    @Inject
    LoginBean loginBean;

    @EJB
    MenuDAO menuDAO;

    @EJB
    PrivilegeDAO privilegeDAO;

    Menu menu;
    List<Menu> menus;
    List<Menu> menuListActif;
    List<Menu> menuListSelected;
    List<Menu> menuListByProfil;
    List<Menu> menuProfilDisponible;
    Menu menuSelected;

    @PostConstruct
    private void init() {

        menu = new Menu();
        menuListActif = new ArrayList<>();
        menuListSelected = new ArrayList<>();
        menus = new ArrayList<>();
        menuSelected = new Menu();
        menuListByProfil = new ArrayList<>();
        menuProfilDisponible = new ArrayList<>();

    }

    public List<Menu> findAllByParent(int parentId) {

        return menus = menuDAO.findAllByParent(parentId);

    }

    public List<Menu> findAllByModule(ModuleP moduleP) {

        return menus = menuDAO.findAllByModule(moduleP);

    }

    public List<Menu> findAllByProfil(Profil profil) {

        return menus = menuDAO.findAllByProfil(profil);

    }

    public List<Menu> findMenuProfilDisponible(Profil profil) {

        menuProfilDisponible = menuDAO.findMenuProfilDisponible(profil);
        return menuProfilDisponible;
    }

    public Menu findMenuById(int id) {

        Menu menu1 = menuDAO.findMenuById(id);
        return menu1;

    }

    public Menu findParent() {

        return menuDAO.findMenuById(menuSelected.getParentId());

    }

    public List<Menu> findMenuActifByParent(int parentId) {

        return menuListActif = menuDAO.findMenuActifByParent(parentId);

    }

    public void save(ModuleP moduleP) {
        authorization.requirePrivilege("MOD_EDIT");

        menu.setCreePar(loginBean.getUsers().getId());
        menu.setModuleP(moduleP);

        if (menuDAO.persist(menu) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Menu correctement ajouté");
            FacesContext.getCurrentInstance().addMessage(null, message);

            findAllByModule(moduleP);
            menu = new Menu();

        }

    }

    public List<Menu> changeStatus(int statut, Menu menu1, ModuleP moduleP) {
        authorization.requirePrivilege("MOD_EDIT");

        menu1.setStatut(statut);

        if (menuDAO.merge(menu1) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Statut changé");
            FacesContext.getCurrentInstance().addMessage(null, message);

        }
        return findAllByModule(moduleP);

    }

    public List<Menu> remove(Menu menu1, ModuleP moduleP) {
        authorization.requirePrivilege("MOD_EDIT");

        if (menuDAO.remove(menu1) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Menu supprimé");
            FacesContext.getCurrentInstance().addMessage(null, message);

        }
        return findAllByModule(moduleP);
    }

    public List<Menu> update(ModuleP moduleP) {
        authorization.requirePrivilege("MOD_EDIT");
        menuSelected.setDateMaj(new Date());

        if (menuDAO.merge(menuSelected) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Mise à jour effectuée");
            FacesContext.getCurrentInstance().addMessage(null, message);

        }

        return findAllByModule(moduleP);
    }

    public List<Menu> affectMenuToProfile(Profil profil) {
        authorization.requirePrivilege("PRF_GRANT");

        if (menuListSelected.size() > 0) {

            int i = 0;

            for (Menu menu1 : menuListSelected) {

                if(menuDAO.affectMenuToProfile(menu1,profil)==1){

                    i++;
                }

            }

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    i+" menu(s) affectés");
            FacesContext.getCurrentInstance().addMessage(null, message);

            findMenuProfilDisponible(profil);
            return findAllByProfil(profil);

        }
        else{

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR, "",
                    "Aucun menu séléctionné");
            FacesContext.getCurrentInstance().addMessage(null, message);

        }

        return null;

    }

    public List<Menu> retirerMenuToProfile(Profil profil, Menu menu1){
        authorization.requirePrivilege("PRF_GRANT");

        boolean retirer = menuDAO.retirerMenuToProfile(profil,menu1);
        if(retirer){

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    menu1.getNom() +" retiré du profil " + profil.getNom());
            FacesContext.getCurrentInstance().addMessage(null, message);
        }

        findMenuProfilDisponible(profil);
        return findAllByProfil(profil);

    }

    public Menu find() {

        return menuDAO.find(menuSelected.getId());
    }
}
