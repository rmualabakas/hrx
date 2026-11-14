package cd.mura.hrx.bean.config;

import cd.mura.hrx.entity.config.JourFerie;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.CalendrierService;
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
import java.time.Month;
import java.time.format.TextStyle;
import java.util.*;

/** Administration des jours fériés (calendrier ouvré utilisé pour les délais et les relances). */
@Named
@ViewScoped
@Getter @Setter
public class JourFerieView implements Serializable {

    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private CalendrierService calendrier;

    private List<JourFerie> feries = new ArrayList<>();
    private JourFerie edit = new JourFerie();
    private int annee = LocalDate.now().getYear();

    @PostConstruct
    public void init() { charger(); }

    public void charger() { feries = calendrier.lister(); }

    public boolean isEditable() { return authorization.hasPrivilege("FERIE_EDIT"); }

    public List<JourFerie> getRecurrents() { return feries.stream().filter(JourFerie::isRecurrent).toList(); }
    public List<JourFerie> getPonctuels() { return feries.stream().filter(j -> !j.isRecurrent()).sorted(Comparator.comparing(JourFerie::getDateFerie, Comparator.nullsLast(Comparator.reverseOrder()))).toList(); }

    /** Fériés de l'année affichée, avec le jour de la semaine (un férié tombant un week-end ne retire pas de jour ouvré). */
    public record Occurrence(LocalDate date, String libelle, boolean weekend, boolean passe) {
        public String getJour() { return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.FRENCH); }
        public String getDateCourte() { return date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.FRENCH); }
        public LocalDate getDate() { return date; }
        public String getLibelle() { return libelle; }
        public boolean isWeekend() { return weekend; }
        public boolean isPasse() { return passe; }
    }
    public List<Occurrence> getOccurrences() {
        List<Occurrence> l = new ArrayList<>();
        LocalDate auj = LocalDate.now();
        for (JourFerie j : feries) {
            if (!j.isActif()) continue;
            LocalDate d = j.dansAnnee(annee);
            if (d != null) l.add(new Occurrence(d, j.getLibelle(), d.getDayOfWeek().getValue() >= 6, d.isBefore(auj)));
        }
        l.sort(Comparator.comparing(Occurrence::date));
        return l;
    }
    public long getNbOuvresPerdus() { return getOccurrences().stream().filter(o -> !o.weekend()).count(); }
    public Occurrence getProchain() { LocalDate auj = LocalDate.now(); return getOccurrences().stream().filter(o -> !o.date().isBefore(auj)).findFirst().orElse(null); }
    public void anneePrecedente() { annee--; }
    public void anneeSuivante() { annee++; }

    public List<Integer> getJours() { List<Integer> l = new ArrayList<>(); for (int i = 1; i <= 31; i++) l.add(i); return l; }
    public Map<String, Integer> getMoisListe() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Month mo : Month.values()) m.put(mo.getDisplayName(TextStyle.FULL, Locale.FRENCH), mo.getValue());
        return m;
    }

    public String nomMois(Integer m) { return m == null || m < 1 || m > 12 ? "" : Month.of(m).getDisplayName(TextStyle.FULL, Locale.FRENCH); }

    public void nouveau() { edit = new JourFerie(); edit.setRecurrent(true); edit.setActif(true); }
    public void modifier(JourFerie j) { edit = j; }

    public void enregistrer() {
        authorization.requirePrivilege("FERIE_EDIT");
        boolean creation = edit.getId() == 0;
        try {
            edit = calendrier.enregistrer(edit);
            auditService.log(creation ? "CREATE" : "UPDATE", "Administration", "JourFerie", edit.getId(), (creation ? "Ajout" : "Modification") + " d'un jour férié", edit.getLibelle() + " : " + edit.getDateLisible(), true);
            org.primefaces.PrimeFaces.current().ajax().addCallbackParam("ok", true);
            msg(FacesMessage.SEVERITY_INFO, creation ? "Jour férié ajouté" : "Jour férié mis à jour", edit.getLibelle());
            charger();
        } catch (IllegalArgumentException e) {
            msg(FacesMessage.SEVERITY_ERROR, "Jour férié incomplet", e.getMessage());
        }
    }

    public void basculer(JourFerie j) {
        authorization.requirePrivilege("FERIE_EDIT");
        JourFerie x = calendrier.basculer(j.getId());
        if (x == null) return;
        auditService.updated("Administration", "JourFerie", x.getId(), x.isActif() ? "Jour férié activé" : "Jour férié désactivé", x.getLibelle());
        charger();
    }

    public void supprimer(JourFerie j) {
        authorization.requirePrivilege("FERIE_EDIT");
        calendrier.supprimer(j.getId());
        auditService.deleted("Administration", "JourFerie", j.getId(), "Suppression d'un jour férié", j.getLibelle() + " : " + j.getDateLisible());
        msg(FacesMessage.SEVERITY_INFO, "Jour férié supprimé", j.getLibelle());
        charger();
    }

    private static void msg(FacesMessage.Severity s, String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(s, t, d)); }
}
