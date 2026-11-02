package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.ProfilDAO;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.AuditService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Data;

import java.io.Serializable;
import java.util.*;

@Named
@ViewScoped
@Data
public class ProfilBean implements Serializable {

    @Inject
    private AuthorizationService authorization;

    @Inject
    LoginBean loginBean;

    @EJB
    ProfilDAO profilDAO;

    @Inject
    PrivilegeBean privilegeBean;

    @Inject
    AuditService auditService;

    Profil profile;
    List<Profil> profilesActives;
    List<Profil> profileAll;
    List<Profil> profilesFiltre;
    Profil profileSelected;

    @PostConstruct
    private void init() {
        profile = new Profil();
        profileAll = new ArrayList<>();
        profilesActives = new ArrayList<>();
        findAllActive();
        findAll();
    }

    public List<Profil> findAllActive() {
        return profilesActives = profilDAO.findAllActive();
    }

    public void save() {
        authorization.requirePrivilege("PRF_EDIT");
        profile.setCreePar(loginBean.getUsers().getId());
        profile = profilDAO.persist(profile);

        if (profile.getId() > 0) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Profil ajouté");
            FacesContext.getCurrentInstance().addMessage(null, message);

            if (profile.getProfilParDefaut()) {
                profilDAO.updateDefault(profile.getId());
            }

            profile = new Profil();
            findAll();
        }
    }

    public void findAll() {
        profileAll = profilDAO.findAll();
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (filterText == null || filterText.equals("")) {
            return true;
        }
        Profil profile = (Profil) value;
        return profile.getNom().toLowerCase().contains(filterText);
    }

    public void changeStatus(int statut, Profil profile1) {
        profile1.setStatut(statut);
        profile1.setDateMaj(new Date());

        if (profilDAO.merge(profile1) == 1) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Statut changé");
            FacesContext.getCurrentInstance().addMessage(null, message);
            findAll();
        }
    }

    public void remove(Profil profile1) {
        authorization.requirePrivilege("PRF_EDIT");
        if (profilDAO.remove(profile1) == 1) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Profil supprimé");
            FacesContext.getCurrentInstance().addMessage(null, message);
            findAll();
        }
    }

    public void update() {
        authorization.requirePrivilege("PRF_EDIT");
        profileSelected.setDateMaj(new Date());

        if (profileSelected.getProfilParDefaut()) {
            profilDAO.updateDefault(profileSelected.getId());
        }

        if (profilDAO.merge(profileSelected) == 1) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_INFO, "",
                    "Mise à jour effectuée");
            FacesContext.getCurrentInstance().addMessage(null, message);
            profileSelected = new Profil();
            findAll();
        }
    }

    private void requirePrivilege(String code) {
        if (privilegeBean == null || !privilegeBean.hasPrivilege(code)) {
            throw new SecurityException("Vous ne disposez pas du privilège requis pour configurer ce profil.");
        }
    }
}
