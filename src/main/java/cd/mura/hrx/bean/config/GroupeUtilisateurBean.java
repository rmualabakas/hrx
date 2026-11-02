package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.GroupeUtilisateurDAO;
import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.GroupeUtilisateur;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.entity.config.Privilege;
import cd.mura.hrx.entity.config.Users;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.primefaces.model.DualListModel;

@Named
@ViewScoped
@Data
public class GroupeUtilisateurBean implements Serializable {

    @Inject
    private AuthorizationService authorization;

    @Inject
    private LoginBean loginBean;

    @Inject
    private AuditService auditService;

    @EJB
    private GroupeUtilisateurDAO groupeUtilisateurDAO;

    @EJB
    private UserDAO userDAO;

    @EJB
    private PrivilegeDAO privilegeDAO;

    private GroupeUtilisateur groupe;
    private GroupeUtilisateur groupeSelected;
    private List<GroupeUtilisateur> groupes = new ArrayList<>();
    private List<GroupeUtilisateur> groupesFiltered;
    private List<Users> usersActifs = new ArrayList<>();
    private List<Integer> membreIds = new ArrayList<>();

    @PostConstruct
    private void init() {
        groupe = new GroupeUtilisateur();
        groupeSelected = new GroupeUtilisateur();
        refresh();
    }

    public void refresh() {
        groupes = groupeUtilisateurDAO.findAll();
        usersActifs = userDAO.findAllActif();
    }

    private boolean creation = true;

    public void nouveau() {
        creation = true;
        groupe = new GroupeUtilisateur();
        membreIds = new ArrayList<>();
    }

    public void modifier(GroupeUtilisateur selected) {
        creation = false;
        prepareEdit(selected);
    }

    public GroupeUtilisateur getCible() { return creation ? groupe : groupeSelected; }

    /** PickList : source = actifs non membres, cible = membres (ordre de la liste des actifs). */
    public DualListModel<Users> getMembresPick() {
        Set<Integer> ids = membreIds == null ? Set.of() : new LinkedHashSet<>(membreIds);
        List<Users> source = new ArrayList<>();
        List<Users> target = new ArrayList<>();
        for (Users u : usersActifs) (ids.contains(u.getId()) ? target : source).add(u);
        return new DualListModel<>(source, target);
    }

    public void setMembresPick(DualListModel<Users> model) {
        membreIds = new ArrayList<>();
        if (model != null && model.getTarget() != null) for (Users u : model.getTarget()) if (u != null) membreIds.add(u.getId());
    }

    public void enregistrer() {
        authorization.requirePrivilege("GRP_EDIT");
        if (creation) save(); else update();
    }

    public long getNbActifs() { return groupes.stream().filter(g -> g.getStatut() == 1).count(); }
    public long getNbMembres() { return groupes.stream().filter(g -> g.getStatut() == 1).mapToLong(this::membreCount).sum(); }

    public String initiales(Users u) {
        String p = u.getPrenom() == null || u.getPrenom().isEmpty() ? "" : u.getPrenom().substring(0, 1);
        String n = u.getNom() == null || u.getNom().isEmpty() ? "" : u.getNom().substring(0, 1);
        return (p + n).toUpperCase(Locale.ROOT);
    }

    public void prepareEdit(GroupeUtilisateur selected) {
        groupeSelected = selected != null && selected.getId() != null
                ? groupeUtilisateurDAO.find(selected.getId())
                : new GroupeUtilisateur();
        membreIds = idsMembres(groupeSelected);
    }

    public void save() {
        authorization.requirePrivilege("GRP_EDIT");
        if (!hasPrivilege("ADDGRPUSR") && !hasPrivilege("ASGRECLA")) {
            deny("Vous n'avez pas le privilège de créer un groupe utilisateur");
            return;
        }
        if (!validate(groupe, null)) {
            return;
        }
        applyMembres(groupe);
        if (loginBean != null && loginBean.getUsers() != null) {
            groupe.setCreePar(loginBean.getUsers().getId());
        }

        GroupeUtilisateur saved = groupeUtilisateurDAO.persist(groupe);
        if (saved != null && saved.getId() != null && saved.getId() > 0) {
            auditService.created("Administration", "GroupeUtilisateur", saved.getId(),
                    "Création groupe utilisateur", saved.getNom());
            msg(FacesMessage.SEVERITY_INFO, "Groupe enregistré avec succès");
            groupe = new GroupeUtilisateur();
            membreIds = new ArrayList<>();
            refresh();
        } else {
            msg(FacesMessage.SEVERITY_ERROR, "Impossible d'enregistrer le groupe");
        }
    }

    public void update() {
        authorization.requirePrivilege("GRP_EDIT");
        if (!hasPrivilege("MDFGRPUSR") && !hasPrivilege("ASGRECLA")) {
            deny("Vous n'avez pas le privilège de modifier un groupe utilisateur");
            return;
        }
        if (!validate(groupeSelected, groupeSelected != null ? groupeSelected.getId() : null)) {
            return;
        }
        applyMembres(groupeSelected);
        if (loginBean != null && loginBean.getUsers() != null) {
            groupeSelected.setModifiePar(loginBean.getUsers().getId());
        }

        if (groupeUtilisateurDAO.merge(groupeSelected) == 1) {
            auditService.updated("Administration", "GroupeUtilisateur", groupeSelected.getId(),
                    "Modification groupe utilisateur", groupeSelected.getNom());
            msg(FacesMessage.SEVERITY_INFO, "Groupe mis à jour avec succès");
            refresh();
        } else {
            msg(FacesMessage.SEVERITY_ERROR, "Impossible de mettre à jour le groupe");
        }
    }

    public void changeStatus(int statut, GroupeUtilisateur selected) {
        if (!hasPrivilege("MDFGRPUSR") && !hasPrivilege("ASGRECLA")) {
            deny("Vous n'avez pas le privilège de modifier le statut du groupe");
            return;
        }
        selected.setStatut(statut);
        if (groupeUtilisateurDAO.merge(selected) == 1) {
            msg(FacesMessage.SEVERITY_INFO, "Statut du groupe modifié");
            refresh();
        }
    }

    public void remove(GroupeUtilisateur selected) {
        authorization.requirePrivilege("GRP_EDIT");
        if (!hasPrivilege("DELGRPUSR") && !hasPrivilege("ASGRECLA")) {
            deny("Vous n'avez pas le privilège de supprimer un groupe utilisateur");
            return;
        }
        if (groupeUtilisateurDAO.remove(selected) == 1) {
            auditService.deleted("Administration", "GroupeUtilisateur",
                    selected != null ? selected.getId() : null,
                    "Suppression groupe utilisateur",
                    selected != null ? selected.getNom() : "");
            msg(FacesMessage.SEVERITY_INFO, "Groupe supprimé avec succès");
            refresh();
        } else {
            msg(FacesMessage.SEVERITY_ERROR, "Impossible de supprimer ce groupe");
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String query = filter == null ? "" : filter.toString().trim().toLowerCase(locale);
        if (query.isEmpty()) {
            return true;
        }
        GroupeUtilisateur g = (GroupeUtilisateur) value;
        String haystack = String.join(" ",
                safe(g.getNom()),
                safe(g.getDescription()),
                membresLabel(g),
                g.getStatut() == 1 ? "actif" : "inactif"
        ).toLowerCase(locale);
        return haystack.contains(query);
    }

    public String membresLabel(GroupeUtilisateur g) {
        if (g == null || g.getMembres() == null || g.getMembres().isEmpty()) {
            return "Aucun membre";
        }
        return g.getMembres().stream()
                .filter(u -> u != null)
                .map(this::nomComplet)
                .collect(Collectors.joining(", "));
    }

    public int membreCount(GroupeUtilisateur g) {
        return g == null || g.getMembres() == null ? 0 : g.getMembres().size();
    }

    private void applyMembres(GroupeUtilisateur target) {
        Set<Users> membres = new LinkedHashSet<>();
        if (membreIds != null) {
            for (Integer id : membreIds) {
                if (id != null && id > 0) {
                    membres.add(userDAO.getReferenceById(id));
                }
            }
        }
        target.setMembres(membres);
    }

    private List<Integer> idsMembres(GroupeUtilisateur g) {
        if (g == null || g.getMembres() == null) {
            return new ArrayList<>();
        }
        return g.getMembres().stream()
                .filter(u -> u != null && u.getId() > 0)
                .map(Users::getId)
                .collect(Collectors.toList());
    }

    private boolean validate(GroupeUtilisateur target, Long idToIgnore) {
        boolean valid = true;
        if (target == null || target.getNom() == null || target.getNom().trim().isEmpty()) {
            msg(FacesMessage.SEVERITY_WARN, "Le nom du groupe est obligatoire");
            valid = false;
        }
        if (valid) {
            List<GroupeUtilisateur> duplicates = groupeUtilisateurDAO.findByNom(target.getNom());
            boolean exists = duplicates.stream()
                    .anyMatch(g -> g != null && g.getId() != null
                            && (idToIgnore == null || !g.getId().equals(idToIgnore)));
            if (exists) {
                msg(FacesMessage.SEVERITY_WARN, "Un groupe porte déjà ce nom");
                valid = false;
            }
        }
        if (!valid) {
            FacesContext.getCurrentInstance().validationFailed();
        }
        return valid;
    }

    private boolean hasPrivilege(String code) {
        try {
            Users current = loginBean == null ? null : loginBean.getUsers();
            if (current == null || current.getProfil() == null) {
                return false;
            }
            if (current.getProfil().getId() == 1
                    || (current.getProfil().getNom() != null
                    && current.getProfil().getNom().toLowerCase(Locale.ROOT).contains("root"))) {
                return true;
            }
            return privilegeDAO.findAllByProfil(current.getProfil()).stream()
                    .filter(p -> p != null && p.getStatut() == 1 && p.getCode() != null)
                    .map(Privilege::getCode)
                    .anyMatch(c -> code.equals(c) || "GRP_EDIT".equals(c));
        } catch (Exception e) {
            return false;
        }
    }

    private String nomComplet(Users user) {
        String prenom = user.getPrenom() == null ? "" : user.getPrenom().trim();
        String nom = user.getNom() == null ? "" : user.getNom().trim();
        String full = (prenom + " " + nom).trim();
        return full.isEmpty() ? user.getLogin() : full;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void deny(String detail) {
        msg(FacesMessage.SEVERITY_WARN, detail);
        FacesContext.getCurrentInstance().validationFailed();
    }

    private void msg(FacesMessage.Severity severity, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, "", detail));
    }
}
