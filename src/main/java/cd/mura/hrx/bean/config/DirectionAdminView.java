package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
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
import java.time.LocalDate;
import java.util.*;

/** Référentiel des directions / entités : base du taux de réalisation par direction. */
@Named
@ViewScoped
@Getter
@Setter
public class DirectionAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private DirectionDAO directionDAO;
    @EJB private UserDAO userDAO;

    private List<Direction> directions = new ArrayList<>();
    private List<Users> utilisateurs = new ArrayList<>();
    private Map<Integer, Long> effectifs = new HashMap<>();
    private Map<Integer, Users> dirigeants = new HashMap<>();
    private String filtre = "";
    /** Affichage choisi : cartes ou liste. Mémorisé dans un cookie du navigateur. */
    private String vue = "cartes";
    private static final String COOKIE_VUE = "hrx_directions_vue";
    private Direction edit = new Direction();
    private Integer editDirigePar;

    @PostConstruct
    public void init() {
        Object c = FacesContext.getCurrentInstance().getExternalContext().getRequestCookieMap().get(COOKIE_VUE);
        if (c instanceof jakarta.servlet.http.Cookie ck && ("cartes".equals(ck.getValue()) || "liste".equals(ck.getValue()))) vue = ck.getValue();
        load();
    }

    public void choisirVue(String v) {
        if (!"cartes".equals(v) && !"liste".equals(v)) return;
        vue = v;
        Map<String, Object> props = new HashMap<>();
        props.put("maxAge", 60 * 60 * 24 * 365);
        props.put("path", FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath() + "/");
        FacesContext.getCurrentInstance().getExternalContext().addResponseCookie(COOKIE_VUE, v, props);
    }

    public void load() {
        directions = directionDAO.findAll();
        utilisateurs = userDAO.findAllActif();
        effectifs.clear(); dirigeants.clear();
        Map<Integer, Users> parId = new HashMap<>();
        for (Users u : utilisateurs) parId.put(u.getId(), u);
        for (Users u : userDAO.findAll()) {
            if (u.getDirection() != null && u.getStatut() == 1) effectifs.merge(u.getDirection().getId(), 1L, Long::sum);
        }
        for (Direction d : directions) if (d.getDirigePar() > 0 && parId.containsKey(d.getDirigePar())) dirigeants.put(d.getId(), parId.get(d.getDirigePar()));
    }

    public List<Direction> getDirectionsFiltrees() {
        if (filtre == null || filtre.isBlank()) return directions;
        String f = filtre.trim().toLowerCase(Locale.ROOT);
        return directions.stream().filter(d -> (s(d.getNom()) + " " + s(d.getAbrege())).toLowerCase(Locale.ROOT).contains(f)).toList();
    }
    private static String s(String v) { return v == null ? "" : v; }

    public long effectif(Direction d) { return effectifs.getOrDefault(d.getId(), 0L); }
    public Users dirigeant(Direction d) { return dirigeants.get(d.getId()); }
    public long getTotalEffectif() { return effectifs.values().stream().mapToLong(Long::longValue).sum(); }

    public void nouveau() { edit = new Direction(); editDirigePar = null; }
    public void modifier(Direction d) { edit = d; editDirigePar = d.getDirigePar() > 0 ? d.getDirigePar() : null; }

    public void enregistrer() {
        authorization.requirePrivilege("DIR_EDIT");
        boolean creation = edit.getId() == 0;
        edit.setNom(s(edit.getNom()).trim());
        edit.setAbrege(s(edit.getAbrege()).trim().toUpperCase(Locale.ROOT));
        if (edit.getNom().isEmpty() || edit.getAbrege().isEmpty()) { error("Champs obligatoires", "Le nom et l'abrégé sont requis."); return; }
        boolean doublon = directions.stream().anyMatch(d -> d.getId() != edit.getId()
                && (d.getNom().equalsIgnoreCase(edit.getNom()) || s(d.getAbrege()).equalsIgnoreCase(edit.getAbrege())));
        if (doublon) { error("Doublon", "Une direction porte déjà ce nom ou cet abrégé."); return; }
        edit.setDirigePar(editDirigePar == null ? 0 : editDirigePar);
        edit.setDateMaj(LocalDate.now());
        boolean ok;
        if (creation) {
            edit.setCreePar(loginBean.getUsers().getId());
            edit.setDateCreation(LocalDate.now());
            edit.setStatut(1);
            Direction saved = directionDAO.persist(edit);
            ok = saved != null && saved.getId() > 0;
        } else {
            edit.setMajPar(loginBean.getUsers().getId());
            ok = directionDAO.merge(edit) == 1;
        }
        if (ok) {
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "Direction", edit.getId(), (creation ? "Création" : "Modification") + " de la direction", edit.getAbrege() + " : " + edit.getNom(), true);
            info(creation ? "Direction créée" : "Direction mise à jour", edit.getNom());
            load(); edit = new Direction();
        } else error("Enregistrement impossible", "Vérifiez les informations saisies.");
    }

    public void basculerStatut(Direction d) {
        authorization.requirePrivilege("DIR_EDIT");
        d.setStatut(d.getStatut() == 1 ? 0 : 1);
        d.setDateMaj(LocalDate.now());
        d.setMajPar(loginBean.getUsers().getId());
        if (directionDAO.merge(d) == 1) {
            auditService.updated("Administration", "Direction", d.getId(), d.getStatut() == 1 ? "Direction activée" : "Direction désactivée", d.getNom());
            info(d.getStatut() == 1 ? "Direction activée" : "Direction désactivée", d.getNom());
        }
    }

    public void supprimer(Direction d) {
        authorization.requirePrivilege("DIR_EDIT");
        if (effectif(d) > 0) { error("Suppression impossible", "Des collaborateurs sont rattachés à cette direction : désactivez-la plutôt."); return; }
        try {
            if (directionDAO.remove(d) == 1) {
                auditService.deleted("Administration", "Direction", d.getId(), "Suppression de la direction", d.getNom());
                info("Direction supprimée", d.getNom()); load();
            } else error("Suppression impossible", "La direction est encore référencée.");
        } catch (Exception e) { error("Suppression impossible", "La direction est encore référencée."); }
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
