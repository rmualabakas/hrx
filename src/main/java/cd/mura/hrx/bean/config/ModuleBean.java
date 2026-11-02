package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.ModuleDAO;
import cd.mura.hrx.entity.config.ModuleP;
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
import java.util.Locale;

@Named
@ViewScoped
@Data
public class ModuleBean implements Serializable {

    @Inject
    private AuthorizationService authorization;

    @Inject
    LoginBean loginBean;

    @EJB
    ModuleDAO moduleDAO;

    ModuleP moduleP;
    List<ModuleP> modulesActives;
    List<ModuleP> modulePAll;
    List<ModuleP> modulesFiltre;
    ModuleP modulePSelected;

    @PostConstruct
    private void init() {

        moduleP = new ModuleP();
        modulePAll = new ArrayList<>();
        modulesActives = new ArrayList<>();
        findAllActive();
        findAll();

    }

    public void findAllActive() {

        modulesActives = moduleDAO.findAllActive();

    }

    public void save() {
        authorization.requirePrivilege("MOD_EDIT");

        moduleP.setCreePar(loginBean.getUsers().getId());

        if (moduleDAO.persist(moduleP) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Module ajoutée");
            FacesContext.getCurrentInstance().addMessage(null, message);

            moduleP = new ModuleP();
            findAll();

        }

    }

    public void findAll() {

        modulePAll = moduleDAO.findAll();

    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (filterText == null || filterText.isEmpty()) {
            return true;
        }
        ModuleP moduleP = (ModuleP) value;
        return moduleP.getNom().toLowerCase().contains(filterText);
    }

    public void changeStatus(int statut, ModuleP moduleP1) {

        moduleP1.setStatut(statut);
        moduleP1.setDateMaj(new Date());

        if (moduleDAO.merge(moduleP1) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Statut changé");
            FacesContext.getCurrentInstance().addMessage(null, message);

            findAll();

        }

    }

    public void remove(ModuleP moduleP1) {
        authorization.requirePrivilege("MOD_EDIT");

        if (moduleDAO.remove(moduleP1) == 1) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Module supprimé");
            FacesContext.getCurrentInstance().addMessage(null, message);

            findAll();

        }

    }

    public void update() {
        authorization.requirePrivilege("MOD_EDIT");

        modulePSelected.setDateMaj(new Date());

        if (moduleDAO.merge(modulePSelected) == 1) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Mise à jour effectuée");
            FacesContext.getCurrentInstance().addMessage(null, message);
            modulePSelected = new ModuleP();
            findAll();
        }

    }

}
