package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.bean.config.PrivilegeBean;
import cd.mura.hrx.dao.config.DirectionDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Direction;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.service.OrganisationService;
import cd.mura.hrx.service.OrganigrammePdfService;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;
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
import org.primefaces.event.organigram.OrganigramNodeDragDropEvent;
import org.primefaces.event.organigram.OrganigramNodeSelectEvent;
import org.primefaces.model.DefaultOrganigramNode;
import org.primefaces.model.OrganigramNode;

import java.io.Serializable;
import java.util.*;

/** Organigramme de la banque ou d'une direction, en arbre graphique ou en liste ; modifiable avec le privilège ORG_EDIT. */
@Named
@ViewScoped
@Getter
@Setter
public class OrganigrammeView implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String PRIVILEGE = "ORG_EDIT";

    @Inject private LoginBean loginBean;
    @Inject private PrivilegeBean privilegeBean;
    @Inject private AuditService auditService;
    @Inject private AuthorizationService authorization;
    @EJB private UserDAO userDAO;
    @EJB private DirectionDAO directionDAO;
    @EJB private OrganisationService organisation;
    @Inject private OrganigrammePdfService pdfService;

    /** Ce qu'affiche une case de l'organigramme. */
    public record Poste(int id, String nom, String fonction, String direction, String initiales,
                        boolean directeur, int directs, int effectif, boolean horsDirection, String n1Externe) implements Serializable { }

    /** Une ligne de la vue liste. */
    public record LignePoste(Poste poste, int niveau, String n1) implements Serializable { }

    private boolean peutEditer;
    private String vue = "arbre";                 // arbre | liste
    private Integer directionId;                 // null = toute la banque
    private int profondeur = 3;                  // niveaux dépliés au chargement de l'arbre
    private String recherche = "";

    private List<Direction> directions = new ArrayList<>();
    private Map<Integer, Users> parId = new HashMap<>();
    private Map<Integer, List<Users>> enfants = new HashMap<>();
    private OrganigramNode racine;
    private OrganigramNode selection;
    private List<LignePoste> lignes = new ArrayList<>();

    // édition
    private Integer editId;
    private Integer editN1Id;
    private Integer editDirectionId;
    private boolean editDirecteur;
    private boolean editTransferer = true;
    private OrganisationService.Impact impact;
    private Set<Integer> exclus = new HashSet<>();

    @PostConstruct
    public void init() {
        Users moi = loginBean.getUsers();
        peutEditer = (moi.getProfil() != null && moi.getProfil().getId() == 1) || privilegeBean.hasPrivilege(PRIVILEGE);
        directions = directionDAO.findAllActif();
        directions.sort(Comparator.comparing(d -> s(d.getNom())));
        charger();
    }

    public void charger() {
        parId.clear(); enfants.clear();
        for (Users u : userDAO.findActifsAvecDirection()) parId.put(u.getId(), u);
        for (Users u : parId.values()) {
            Users n1 = u.getSuperieurHierarchique();
            if (n1 != null && parId.containsKey(n1.getId())) enfants.computeIfAbsent(n1.getId(), k -> new ArrayList<>()).add(u);
        }
        enfants.values().forEach(l -> l.sort(Comparator.comparing((Users x) -> !estDirecteur(x)).thenComparing(x -> s(x.getNom())).thenComparing(x -> s(x.getPrenom()))));
        construireArbre();
        construireListe();
    }

    // construction

    private boolean dansPerimetre(Users u) {
        return directionId == null || (u.getDirection() != null && u.getDirection().getId() == directionId);
    }

    /** Têtes de l'organigramme : sans N+1 dans le périmètre affiché. */
    private List<Users> tetes() {
        List<Users> out = new ArrayList<>();
        for (Users u : parId.values()) {
            if (!dansPerimetre(u)) continue;
            Users n1 = u.getSuperieurHierarchique();
            boolean n1Visible = n1 != null && parId.containsKey(n1.getId()) && dansPerimetre(parId.get(n1.getId()));
            if (!n1Visible) out.add(u);
        }
        out.sort(Comparator.comparing((Users x) -> !estDirecteur(x)).thenComparing((Users x) -> -effectif(x.getId())).thenComparing(x -> s(x.getNom())));
        return out;
    }

    private void construireArbre() {
        Direction d = directionId == null ? null : directions.stream().filter(x -> x.getId() == directionId).findFirst().orElse(null);
        String titre = d == null ? "BGFIBank RDC" : d.getNom();
        long total = parId.values().stream().filter(this::dansPerimetre).count();
        racine = new DefaultOrganigramNode("racine", new Poste(0, titre, total + " collaborateur" + (total > 1 ? "s" : ""), d == null ? "" : s(d.getAbrege()), "", false, 0, (int) total, false, null), null);
        racine.setExpanded(true);
        racine.setDraggable(false);
        racine.setSelectable(false);
        racine.setDroppable(peutEditer);
        for (Users t : tetes()) ajouterNoeud(racine, t, 1, new HashSet<>());
    }

    private void ajouterNoeud(OrganigramNode parent, Users u, int niveau, Set<Integer> vus) {
        if (!vus.add(u.getId())) return;
        Poste p = poste(u);
        OrganigramNode n = new DefaultOrganigramNode(p.directeur() ? "directeur" : "salarie", p, parent);
        n.setExpanded(niveau < profondeur);
        n.setCollapsible(true);
        n.setSelectable(true);
        n.setDraggable(peutEditer);
        n.setDroppable(peutEditer);
        for (Users e : enfants.getOrDefault(u.getId(), List.of())) if (dansPerimetre(e)) ajouterNoeud(n, e, niveau + 1, vus);
    }

    private void construireListe() {
        lignes = new ArrayList<>();
        Set<Integer> vus = new HashSet<>();
        for (Users t : tetes()) parcourir(t, 0, vus);
    }

    private void parcourir(Users u, int niveau, Set<Integer> vus) {
        if (!vus.add(u.getId())) return;
        Users n1 = u.getSuperieurHierarchique();
        lignes.add(new LignePoste(poste(u), niveau, n1 == null ? "" : n1.getNomComplet()));
        for (Users e : enfants.getOrDefault(u.getId(), List.of())) if (dansPerimetre(e)) parcourir(e, niveau + 1, vus);
    }

    private Poste poste(Users u) {
        Users n1 = u.getSuperieurHierarchique();
        boolean externe = directionId != null && n1 != null && parId.containsKey(n1.getId()) && !dansPerimetre(parId.get(n1.getId()));
        String n1Ext = externe ? n1.getNomComplet() + (n1.getDirection() == null ? "" : " (" + s(n1.getDirection().getAbrege()) + ")") : null;
        return new Poste(u.getId(), u.getNomComplet(), s(u.getFonction()), u.getDirection() == null ? "" : s(u.getDirection().getAbrege()),
                initiales(u), estDirecteur(u), enfants.getOrDefault(u.getId(), List.of()).size(), effectif(u.getId()), false, n1Ext);
    }

    private boolean estDirecteur(Users u) { return u.getDirection() != null && u.getDirection().getDirigePar() == u.getId(); }

    private int effectif(int id) {
        int n = 0; Deque<Integer> pile = new ArrayDeque<>(List.of(id)); Set<Integer> vus = new HashSet<>();
        while (!pile.isEmpty()) for (Users e : enfants.getOrDefault(pile.pop(), List.of())) if (vus.add(e.getId())) { n++; pile.push(e.getId()); }
        return n;
    }

    // lecture

    public List<LignePoste> getLignesFiltrees() {
        String q = recherche == null ? "" : recherche.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return lignes;
        return lignes.stream().filter(l -> (l.poste().nom() + " " + l.poste().fonction() + " " + l.poste().direction() + " " + l.n1()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    public long getEffectif() { return parId.values().stream().filter(this::dansPerimetre).count(); }
    public long getNbSansN1() { return parId.values().stream().filter(u -> dansPerimetre(u) && u.getSuperieurHierarchique() == null).count(); }
    public long getNbManagers() { return parId.values().stream().filter(u -> dansPerimetre(u) && enfants.containsKey(u.getId())).count(); }
    public int getProfondeurMax() { int m = 0; for (LignePoste l : lignes) m = Math.max(m, l.niveau() + 1); return m; }
    public String getTitreDirection() {
        if (directionId == null) return "Toute la banque";
        return directions.stream().filter(x -> x.getId() == directionId).map(Direction::getNom).findFirst().orElse("Direction");
    }

    public void changerDirection() { charger(); }

    /** Organigramme du périmètre affiché, tous niveaux dépliés, en PDF paysage. */
    public StreamedContent getExportPdf() {
        List<OrganigrammePdfService.Noeud> tetes = new ArrayList<>();
        Set<Integer> vus = new HashSet<>();
        for (Users t : tetes()) tetes.add(noeudPdf(t, vus));
        String titre = racine != null && racine.getData() instanceof Poste p ? p.nom() : "BGFIBank RDC";
        int effectif = (int) getEffectif();
        byte[] data = pdfService.pdf(new OrganigrammePdfService.Noeud(titre, "", "", false, true, effectif, tetes), getTitreDirection(), effectif);
        auditService.log("EXPORT", "Organigramme", "Direction", directionId, "Export PDF de l'organigramme", getTitreDirection(), true);
        String nom = "Organigramme_" + getTitreDirection().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".pdf";
        return DefaultStreamedContent.builder().name(nom).contentType("application/pdf").stream(() -> new java.io.ByteArrayInputStream(data)).build();
    }

    private OrganigrammePdfService.Noeud noeudPdf(Users u, Set<Integer> vus) {
        vus.add(u.getId());
        List<OrganigrammePdfService.Noeud> k = new ArrayList<>();
        for (Users e : enfants.getOrDefault(u.getId(), List.of())) if (dansPerimetre(e) && !vus.contains(e.getId())) k.add(noeudPdf(e, vus));
        Poste p = poste(u);
        return new OrganigrammePdfService.Noeud(p.nom(), p.fonction(), p.direction(), p.directeur(), false, p.effectif(), k);
    }
    public void changerProfondeur() { construireArbre(); }

    /** Collaborateurs pouvant devenir N+1 de la personne éditée (ni elle-même, ni quelqu'un de sa branche). */
    public List<Users> getN1Possibles() {
        return parId.values().stream().filter(u -> editId == null || !exclus.contains(u.getId()))
                .sorted(Comparator.comparing((Users x) -> s(x.getNom())).thenComparing(x -> s(x.getPrenom()))).toList();
    }

    public Users getEdite() { return editId == null ? null : parId.get(editId); }
    public String getInitialesEdite() { Users u = getEdite(); return u == null ? "" : initiales(u); }
    public String libelleUser(Users u) {
        return u.getNomComplet() + (u.getFonction() == null || u.getFonction().isBlank() ? "" : " · " + u.getFonction()) + (u.getDirection() == null ? "" : " · " + s(u.getDirection().getAbrege()));
    }
    public boolean isN1Modifie() { Users u = getEdite(); return u != null && !Objects.equals(idN1(u), editN1Id); }

    // édition

    public void onSelect(OrganigramNodeSelectEvent e) {
        if (e.getOrganigramNode() != null && e.getOrganigramNode().getData() instanceof Poste p && p.id() > 0) {
            preparer(p.id(), null, false);
            PrimeFaces.current().ajax().update("dlgOrg");
            PrimeFaces.current().executeScript("PF('dlgOrg').show()");
        }
    }

    /** Glisser-déposer : on ne change rien tout de suite, on demande confirmation. */
    public void onDragDrop(OrganigramNodeDragDropEvent e) {
        authorization.requirePrivilege("ORG_EDIT");
        if (!peutEditer) { charger(); return; }
        OrganigramNode source = e.getOrganigramNode(), cible = e.getTargetOrganigramNode();
        if (!(source.getData() instanceof Poste p) || p.id() == 0) { charger(); return; }
        Integer nouveau = cible != null && cible.getData() instanceof Poste c && c.id() > 0 ? c.id() : null;
        preparer(p.id(), nouveau, true);
        PrimeFaces.current().ajax().update("dlgOrg");
        PrimeFaces.current().executeScript("PF('dlgOrg').show()");
    }

    public void ouvrir(int id) { preparer(id, null, false); }

    private void preparer(int id, Integer nouveauN1, boolean depuisGlisser) {
        Users u = parId.get(id);
        if (u == null) return;
        editId = id;
        editN1Id = depuisGlisser ? nouveauN1 : idN1(u);
        editDirectionId = u.getDirection() == null ? null : u.getDirection().getId();
        editDirecteur = estDirecteur(u);
        editTransferer = true;
        exclus = new HashSet<>(organisation.descendants(id));
        exclus.add(id);
        impact = organisation.impact(id);
    }

    public void enregistrer() {
        authorization.requirePrivilege("ORG_EDIT");
        if (!peutEditer || editId == null) return;
        Users u = parId.get(editId);
        try {
            OrganisationService.Resultat r = organisation.appliquer(editId, editN1Id, editDirectionId, editDirecteur, editTransferer);
            if (!r.n1Change() && !r.directionChangee() && !r.directeurChange()) { info("Aucun changement", "L'organisation de " + u.getNomComplet() + " est inchangée."); }
            else {
                StringBuilder d = new StringBuilder(r.avant()).append(" → ").append(r.apres());
                if (r.evaluationsTransferees() + r.fichesTransferees() > 0)
                    d.append(" · fiches confiées au nouveau N+1 : ").append(r.evaluationsTransferees()).append(" évaluation(s), ").append(r.fichesTransferees()).append(" fiche(s) d'objectifs");
                auditService.updated("Organigramme", "Users", editId, "Changement d'organisation : " + u.getNomComplet(), d.toString());
                String msg = r.apres();
                if (r.evaluationsTransferees() + r.fichesTransferees() > 0)
                    msg += ". " + (r.evaluationsTransferees() + r.fichesTransferees()) + " fiche(s) en cours suivent le nouveau N+1.";
                info("Organisation mise à jour", msg);
            }
            PrimeFaces.current().ajax().addCallbackParam("ok", true);
        } catch (IllegalArgumentException ex) {
            error("Changement refusé", ex.getMessage());
        }
        charger();
    }

    public void annuler() { editId = null; charger(); }

    private static Integer idN1(Users u) { return u.getSuperieurHierarchique() == null ? null : u.getSuperieurHierarchique().getId(); }
    private static String initiales(Users u) {
        String p = s(u.getPrenom()), n = s(u.getNom());
        return ((p.isEmpty() ? "" : p.substring(0, 1)) + (n.isEmpty() ? "" : n.substring(0, 1))).toUpperCase(Locale.ROOT);
    }
    private static String s(String v) { return v == null ? "" : v; }
    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void error(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
