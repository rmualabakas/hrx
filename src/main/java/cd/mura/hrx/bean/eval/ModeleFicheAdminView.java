package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.eval.ModeleFicheDAO;
import cd.mura.hrx.entity.eval.Critere;
import cd.mura.hrx.entity.eval.ModeleFiche;
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
import org.primefaces.PrimeFaces;

import java.io.Serializable;
import java.util.*;

/** Modèles de fiche d'évaluation (MAF 159) */
@Named
@ViewScoped
@Getter
@Setter
public class ModeleFicheAdminView implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String[][] FAMILLES = {
            {"SAVOIR_FAIRE", "Savoir-faire", "II"},
            {"SAVOIR_ETRE", "Savoir-être", "II"},
            {"MANAGERIAL", "Compétences managériales", "II"},
            {"VALEURS_GROUPE", "Valeurs du Groupe", "III"},
            {"DISCIPLINE", "Respect de la discipline", "III"}};
    public static final String[][] CATEGORIES = {
            {"", "Toutes catégories"}, {"CADRE_SUP", "Cadre supérieur"}, {"CADRE", "Cadre"}, {"MAITRISE", "Agent de maîtrise"}, {"EMPLOYE", "Employé"}};

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private ModeleFicheDAO dao;

    private List<ModeleFiche> modeles = new ArrayList<>();
    private final Map<Integer, long[]> usages = new HashMap<>();   // [évaluations, campagnes]
    private ModeleFiche courant;          // modèle ouvert (avec critères)
    private Integer courantId;

    private ModeleFiche edit = new ModeleFiche();
    private boolean editActif = true;
    private Critere critere = new Critere();
    private String critereFamille;

    @PostConstruct
    public void init() {
        charger();
        String p = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("id");
        if (p != null) try { courantId = Integer.valueOf(p); } catch (NumberFormatException ignored) { }
        if (courantId == null && !modeles.isEmpty()) courantId = modeles.get(0).getId();
        ouvrirCourant();
    }

    public void charger() {
        modeles = dao.findAll();
        usages.clear();
        for (ModeleFiche m : modeles) usages.put(m.getId(), new long[]{dao.nbEvaluations(m.getId()), dao.nbCampagnes(m.getId())});
    }

    private void ouvrirCourant() { courant = courantId == null ? null : dao.findAvecCriteres(courantId); }

    // lecture

    public boolean isPeutEditer() {
        return (loginBean.getUsers().getProfil() != null && loginBean.getUsers().getProfil().getId() == 1) || privilegeBean.hasPrivilege("MDL_EDIT");
    }
    public long nbEvaluations(ModeleFiche m) { long[] u = usages.get(m.getId()); return u == null ? 0 : u[0]; }
    public long nbCampagnes(ModeleFiche m) { long[] u = usages.get(m.getId()); return u == null ? 0 : u[1]; }
    public boolean isUtilise(ModeleFiche m) { return nbEvaluations(m) > 0; }
    public String categorieLabel(String code) {
        for (String[] c : CATEGORIES) if (c[0].equals(code == null ? "" : code)) return c[1];
        return code;
    }
    public List<String[]> getCategories() { return Arrays.asList(CATEGORIES); }
    public List<String[]> getFamilles() { return Arrays.asList(FAMILLES); }
    public List<String[]> getFamillesSection(String section) { return Arrays.stream(FAMILLES).filter(f -> f[2].equals(section)).toList(); }

    public List<Critere> criteresDe(String famille) {
        if (courant == null) return List.of();
        return courant.getCriteres().stream().filter(c -> famille.equals(c.getFamille()))
                .sorted(Comparator.comparingInt(Critere::getOrdre).thenComparingInt(Critere::getId)).toList();
    }
    public long nbCriteres(ModeleFiche m) {
        if (courant != null && courant.getId() == m.getId()) return courant.getCriteres().size();
        ModeleFiche x = dao.findAvecCriteres(m.getId());
        return x == null ? 0 : x.getCriteres().size();
    }
    public long getNbManagers() { return courant == null ? 0 : courant.getCriteres().stream().filter(Critere::isReserveManagers).count(); }

    // modèles

    public void selectionner(ModeleFiche m) { courantId = m.getId(); ouvrirCourant(); }

    public void nouveau() {
        edit = new ModeleFiche();
        edit.setNoteMax(20); edit.setPoidsObjectifs(100);
        editActif = true;
    }

    public void modifier(ModeleFiche m) { edit = dao.find(m.getId()); editActif = edit.getStatut() == 1; }

    public void enregistrer() {
        authorization.requirePrivilege("MDL_EDIT");
        if (!isPeutEditer()) return;
        if (s(edit.getLibelle()).isBlank()) { error("Libellé requis", ""); return; }
        String code = s(edit.getCode()).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (code.isBlank()) code = s(edit.getLibelle()).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
        if (code.length() > 30) code = code.substring(0, 30);
        if (dao.codeExiste(code, edit.getId())) { error("Code déjà utilisé", "Le code « " + code + " » est déjà attribué à un autre modèle."); return; }
        edit.setCode(code);
        if (s(edit.getCategorieCible()).isBlank()) edit.setCategorieCible(null);
        edit.setStatut(editActif ? 1 : 0);
        boolean creation = edit.getId() == 0;
        if (creation) edit.setCreePar(loginBean.getUsers().getId());
        edit.setDateMaj(new Date());
        edit = dao.save(edit);
        auditService.log(creation ? "CREATE" : "UPDATE", "Évaluations", "ModeleFiche", edit.getId(), (creation ? "Création" : "Modification") + " d'un modèle de fiche", edit.getLibelle(), true);
        charger();
        courantId = edit.getId(); ouvrirCourant();
        info(creation ? "Modèle créé" : "Modèle enregistré", creation ? "Ajoutez maintenant ses critères." : "");
        PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    /** Copie complète (critères inclus) : base de travail pour un nouvel exercice sans toucher aux fiches passées. */
    public void dupliquer(ModeleFiche m) {
        if (!isPeutEditer()) return;
        ModeleFiche src = dao.findAvecCriteres(m.getId());
        ModeleFiche c = new ModeleFiche();
        String base = (src.getCode().length() > 24 ? src.getCode().substring(0, 24) : src.getCode()) + "_COPIE";
        String code = base; int i = 2;
        while (dao.codeExiste(code, 0)) code = base + i++;
        c.setCode(code); c.setLibelle(src.getLibelle() + " (copie)"); c.setDescription(src.getDescription());
        c.setCategorieCible(src.getCategorieCible()); c.setNoteMax(src.getNoteMax()); c.setPoidsObjectifs(src.getPoidsObjectifs());
        c.setStatut(0); c.setCreePar(loginBean.getUsers().getId());
        for (Critere k : src.getCriteres()) {
            Critere n = new Critere();
            n.setModele(c); n.setLibelle(k.getLibelle()); n.setDescription(k.getDescription()); n.setFamille(k.getFamille());
            n.setReserveManagers(k.isReserveManagers()); n.setPoids(k.getPoids()); n.setOrdre(k.getOrdre());
            c.getCriteres().add(n);
        }
        c = dao.save(c);
        auditService.log("CREATE", "Évaluations", "ModeleFiche", c.getId(), "Duplication d'un modèle de fiche", src.getLibelle() + " → " + c.getLibelle(), true);
        charger(); courantId = c.getId(); ouvrirCourant();
        info("Modèle dupliqué", "La copie est inactive : ajustez-la puis activez-la.");
    }

    public void basculer(ModeleFiche m) {
        authorization.requirePrivilege("MDL_EDIT");
        if (!isPeutEditer()) return;
        ModeleFiche x = dao.find(m.getId());
        x.setStatut(x.getStatut() == 1 ? 0 : 1); x.setDateMaj(new Date());
        dao.save(x);
        auditService.log("UPDATE", "Évaluations", "ModeleFiche", x.getId(), x.getStatut() == 1 ? "Activation d'un modèle" : "Désactivation d'un modèle", x.getLibelle(), true);
        charger(); ouvrirCourant();
    }

    public void supprimer(ModeleFiche m) {
        authorization.requirePrivilege("MDL_EDIT");
        if (!isPeutEditer()) return;
        if (nbEvaluations(m) > 0) { error("Suppression impossible", nbEvaluations(m) + " fiche(s) d'évaluation reposent sur ce modèle. Désactivez-le plutôt."); return; }
        if (nbCampagnes(m) > 0) { error("Suppression impossible", "Ce modèle est utilisé par " + nbCampagnes(m) + " campagne(s)."); return; }
        dao.remove(m);
        auditService.log("DELETE", "Évaluations", "ModeleFiche", m.getId(), "Suppression d'un modèle de fiche", m.getLibelle(), true);
        charger();
        courantId = modeles.isEmpty() ? null : modeles.get(0).getId(); ouvrirCourant();
        info("Modèle supprimé", "");
    }

    // critères

    public void nouveauCritere(String famille) {
        critere = new Critere();
        critere.setFamille(famille);
        critere.setReserveManagers("MANAGERIAL".equals(famille));
        critereFamille = famille;
    }

    public void modifierCritere(Critere c) {
        critere = new Critere();
        critere.setId(c.getId()); critere.setLibelle(c.getLibelle()); critere.setDescription(c.getDescription());
        critere.setFamille(c.getFamille()); critere.setReserveManagers(c.isReserveManagers()); critere.setPoids(c.getPoids()); critere.setOrdre(c.getOrdre());
        critereFamille = c.getFamille();
    }

    public void enregistrerCritere() {
        authorization.requirePrivilege("MDL_EDIT");
        if (!isPeutEditer() || courant == null) return;
        if (s(critere.getLibelle()).isBlank()) { error("Libellé requis", ""); return; }
        ModeleFiche m = dao.findAvecCriteres(courant.getId());
        Critere cible;
        boolean creation = critere.getId() == 0;
        if (creation) {
            cible = new Critere(); cible.setModele(m);
            cible.setOrdre(m.getCriteres().stream().mapToInt(Critere::getOrdre).max().orElse(0) + 1);
            m.getCriteres().add(cible);
        } else {
            cible = m.getCriteres().stream().filter(k -> k.getId() == critere.getId()).findFirst().orElse(null);
            if (cible == null) { error("Critère introuvable", ""); return; }
        }
        cible.setLibelle(critere.getLibelle().trim()); cible.setDescription(critere.getDescription());
        cible.setFamille(critere.getFamille()); cible.setReserveManagers(critere.isReserveManagers());
        cible.setPoids(Math.max(1, critere.getPoids()));
        m.setDateMaj(new Date());
        dao.save(m);
        auditService.log(creation ? "CREATE" : "UPDATE", "Évaluations", "Critere", cible.getId(), (creation ? "Ajout" : "Modification") + " d'un critère", m.getLibelle() + " : " + cible.getLibelle(), true);
        ouvrirCourant();
        info(creation ? "Critère ajouté" : "Critère enregistré", "");
        PrimeFaces.current().ajax().addCallbackParam("ok", true);
    }

    public void supprimerCritere(Critere c) {
        authorization.requirePrivilege("MDL_EDIT");
        if (!isPeutEditer() || courant == null) return;
        ModeleFiche m = dao.findAvecCriteres(courant.getId());
        m.getCriteres().removeIf(k -> k.getId() == c.getId());
        m.setDateMaj(new Date());
        dao.save(m);
        auditService.log("DELETE", "Évaluations", "Critere", c.getId(), "Suppression d'un critère", m.getLibelle() + " : " + c.getLibelle(), true);
        ouvrirCourant();
        info("Critère retiré", "Les fiches déjà initiées conservent leur grille.");
    }

    public void monter(Critere c) { deplacer(c, -1); }
    public void descendre(Critere c) { deplacer(c, 1); }

    private void deplacer(Critere c, int sens) {
        if (!isPeutEditer() || courant == null) return;
        ModeleFiche m = dao.findAvecCriteres(courant.getId());
        List<Critere> l = m.getCriteres().stream().filter(k -> k.getFamille().equals(c.getFamille()))
                .sorted(Comparator.comparingInt(Critere::getOrdre).thenComparingInt(Critere::getId)).toList();
        int i = -1;
        for (int k = 0; k < l.size(); k++) if (l.get(k).getId() == c.getId()) i = k;
        int j = i + sens;
        if (i < 0 || j < 0 || j >= l.size()) return;
        // renumérotation propre de la famille puis échange
        for (int k = 0; k < l.size(); k++) l.get(k).setOrdre((k + 1) * 10);
        int oi = l.get(i).getOrdre();
        l.get(i).setOrdre(l.get(j).getOrdre()); l.get(j).setOrdre(oi);
        dao.save(m);
        ouvrirCourant();
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
    private static String s(String v) { return v == null ? "" : v; }
}
