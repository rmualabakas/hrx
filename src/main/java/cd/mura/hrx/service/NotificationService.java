package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import cd.mura.hrx.entity.eval.EvaluationEssai;
import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.Objectif;
import cd.mura.hrx.entity.eval.Relance;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;

import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Notifications métier HRX (e-mails HTML à la charte BGFI) : accusés d'envoi au collaborateur,
 * alertes « à traiter » au N+1, au directeur, à la DCHT, retours et validations.
 */
@Stateless
public class NotificationService {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final SimpleDateFormat DT = new SimpleDateFormat("dd/MM/yyyy 'à' HH:mm");

    @EJB private MailService mail;
    @EJB private AppDAO appDAO;
    @EJB private UserDAO userDAO;

    // gabarit
    private String base() {
        App a = appDAO.findFirst();
        String u = a == null || a.getUrl() == null ? "" : a.getUrl().trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String nom(Users u) { return u == null ? "—" : esc(u.getNomComplet()); }
    /** Formule d'appel : « Monsieur Jean KABILA » / « Madame … » si le sexe est renseigné, sinon le prénom. */
    private static String prenom(Users u) { return u == null ? "" : esc(u.getCivilite().isEmpty() ? u.getPrenom() : u.getNomAvecCivilite()); }

    /** Page HTML : bandeau marine, filet or, carte blanche, bouton d'action, pied discret. */
    private String gabarit(String titre, String accroche, String corps, String ctaLibelle, String ctaUrl, String badge) {
        String bouton = ctaUrl == null ? "" : "<table role='presentation' cellspacing='0' cellpadding='0' style='margin:22px 0 6px'><tr><td style='background:#0a5c8f;border-radius:8px'>"
                + "<a href='" + ctaUrl + "' style='display:inline-block;padding:12px 22px;color:#ffffff;text-decoration:none;font-weight:600;font-size:14px;font-family:Arial,Helvetica,sans-serif'>" + esc(ctaLibelle) + " &rarr;</a></td></tr></table>";
        String pastille = badge == null ? "" : "<span style='display:inline-block;padding:4px 10px;border-radius:999px;background:#fdf3e1;color:#b8872b;font-size:12px;font-weight:600;letter-spacing:.02em'>" + esc(badge) + "</span>";
        return "<!doctype html><html lang='fr'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'></head>"
                + "<body style='margin:0;background:#eef3f6;font-family:Arial,Helvetica,sans-serif;color:#1f2937'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='background:#eef3f6;padding:28px 12px'><tr><td align='center'>"
                + "<table role='presentation' width='600' cellspacing='0' cellpadding='0' style='max-width:600px;width:100%'>"
                + "<tr><td style='background:#07294a;border-radius:12px 12px 0 0;padding:20px 28px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='color:#ffffff;font-size:20px;font-weight:700;letter-spacing:-.01em'>HRX</td>"
                + "<td align='right' style='color:#b9c7d6;font-size:12px'>Plateforme collaborative RH &middot; BGFIBank RDC</td></tr></table></td></tr>"
                + "<tr><td style='height:3px;background:#b8872b'></td></tr>"
                + "<tr><td style='background:#ffffff;padding:28px 28px 22px'>"
                + pastille
                + "<h1 style='margin:12px 0 6px;font-size:21px;line-height:1.3;color:#07294a'>" + esc(titre) + "</h1>"
                + "<p style='margin:0 0 18px;font-size:15px;line-height:1.55;color:#374151'>" + accroche + "</p>"
                + corps
                + bouton
                + "</td></tr>"
                + "<tr><td style='background:#ffffff;border-radius:0 0 12px 12px;border-top:1px solid #e5e9ee;padding:14px 28px;font-size:11.5px;line-height:1.5;color:#6b7280'>"
                + "Message envoy&eacute; automatiquement par HRX, Direction du Capital Humain et de la Transformation. Ce message est confidentiel, merci de ne pas le transf&eacute;rer.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Petit tableau clé / valeur. */
    private static String fiche(String... kv) {
        StringBuilder b = new StringBuilder("<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;border:1px solid #e5e9ee;border-radius:8px;border-collapse:separate;overflow:hidden'>");
        for (int i = 0; i + 1 < kv.length; i += 2)
            b.append("<tr><td style='padding:9px 12px;background:#f6f8fa;color:#6b7280;font-size:12px;width:38%;border-bottom:1px solid #eef1f4'>").append(esc(kv[i])).append("</td>")
             .append("<td style='padding:9px 12px;font-size:13.5px;color:#111827;font-weight:600;border-bottom:1px solid #eef1f4'>").append(kv[i + 1]).append("</td></tr>");
        return b.append("</table>").toString();
    }

    private String lienFiche(Evaluation e) { return base() + "/ui/fiche?id=" + e.getId(); }
    private String lienObjectifs(FicheObjectifs f) { return base() + "/ui/objectifs?salarie=" + f.getSalarie().getId() + "&exercice=" + f.getExercice(); }
    private String contexte(Evaluation e) {
        return fiche("Collaborateur", nom(e.getSalarie()), "Campagne", esc(e.getCampagne().getLibelle()), "Évaluateur (N+1)", nom(e.getEvaluateur()),
                "Échéance", e.getCampagne().getDateFin() == null ? "—" : e.getCampagne().getDateFin().format(D));
    }
    private void envoyer(Users dest, String sujet, String html) { if (dest != null && dest.getEmail() != null && !dest.getEmail().isBlank()) mail.envoyerHtml(dest.getEmail(), sujet, html); }

    // évaluations
    /** Intégration à la campagne : le collaborateur peut renseigner son auto-évaluation. */
    public void ficheInitiee(Evaluation e) {
        envoyer(e.getSalarie(), "Votre fiche d'évaluation est ouverte : " + e.getCampagne().getLibelle(),
                gabarit("Votre auto-évaluation est à renseigner", "Bonjour " + prenom(e.getSalarie()) + ", votre supérieur hiérarchique vous a intégré(e) à la campagne <b>" + esc(e.getCampagne().getLibelle()) + "</b>. Vous pouvez dès maintenant remplir votre auto-évaluation.",
                        contexte(e), "Renseigner mon auto-évaluation", lienFiche(e), "Auto-évaluation"));
    }

    /** Le collaborateur a soumis : accusé d'envoi au collaborateur, alerte au N+1. */
    public void autoEvaluationSoumise(Evaluation e) {
        String quand = e.getDateSoumission() == null ? "" : DT.format(e.getDateSoumission());
        envoyer(e.getSalarie(), "Accusé de réception - auto-évaluation transmise à votre N+1",
                gabarit("Votre auto-évaluation a bien été transmise", "Bonjour " + prenom(e.getSalarie()) + ", nous accusons réception de votre auto-évaluation, transmise le <b>" + quand + "</b> à <b>" + nom(e.getEvaluateur()) + "</b>" + (e.isSoumiseATemps() ? " - dans les délais de la campagne." : " - <span style='color:#b45309'>après l'échéance de la campagne</span>.") + " Vous serez informé(e) dès que votre évaluation sera réalisée.",
                        contexte(e), "Suivre ma fiche", lienFiche(e), "Accusé d'envoi"));
        envoyer(e.getEvaluateur(), "Évaluation à réaliser : " + e.getSalarie().getNomComplet(),
                gabarit("Une auto-évaluation attend votre revue", "Bonjour " + prenom(e.getEvaluateur()) + ", <b>" + nom(e.getSalarie()) + "</b> a soumis son auto-évaluation le <b>" + quand + "</b>. Merci de la relire, de compléter votre évaluation et de la transmettre.",
                        contexte(e), "Évaluer maintenant", lienFiche(e), "À traiter"));
    }

    /** Le N+1 a transmis : information du collaborateur, alertes N+2 / directeur / DCHT. */
    public void evaluationTransmise(Evaluation e, Users n2, Users directeur) {
        String note = e.getNoteGlobale() == null ? "—" : e.getNoteGlobale() + " / 20 : " + esc(e.getAppreciation());
        envoyer(e.getSalarie(), "Votre évaluation a été réalisée : " + e.getCampagne().getLibelle(),
                gabarit("Votre évaluation a été réalisée par votre N+1", "Bonjour " + prenom(e.getSalarie()) + ", <b>" + nom(e.getEvaluateur()) + "</b> a terminé votre évaluation. Elle part maintenant chez le directeur puis à la DCHT. Vous pouvez la consulter et y ajouter vos commentaires.",
                        contexte(e) + "<p style='margin:14px 0 0;font-size:14px'>Note provisoire : <b>" + note + "</b></p>", "Consulter et commenter", lienFiche(e), "Évaluation réalisée"));
        String corpsResp = contexte(e) + "<p style='margin:14px 0 0;font-size:14px'>Note : <b>" + note + "</b></p>";
        if (n2 != null) envoyer(n2, "Visa à apposer - évaluation de " + e.getSalarie().getNomComplet(),
                gabarit("Une évaluation attend votre visa (N+2)", "Bonjour " + prenom(n2) + ", l'évaluation de <b>" + nom(e.getSalarie()) + "</b> réalisée par <b>" + nom(e.getEvaluateur()) + "</b> vous est transmise pour visa.", corpsResp, "Apposer mon visa", lienFiche(e), "À viser"));
        if (directeur != null) envoyer(directeur, "Avis du directeur à donner : " + e.getSalarie().getNomComplet(),
                gabarit("Une évaluation attend votre avis", "Bonjour " + prenom(directeur) + ", l'évaluation de <b>" + nom(e.getSalarie()) + "</b> attend votre avis.", corpsResp, "Donner mon avis", lienFiche(e), "Avis du directeur"));
        for (Users rh : dcht()) envoyer(rh, "Évaluation à valider : " + e.getSalarie().getNomComplet(),
                gabarit("Une évaluation est prête pour validation", "Bonjour " + prenom(rh) + ", l'évaluation de <b>" + nom(e.getSalarie()) + "</b> (" + esc(e.getCampagne().getLibelle()) + ") a été réalisée par le N+1 et attend le contrôle de conformité et la validation de la DCHT.", corpsResp, "Contrôler et valider", lienFiche(e), "À valider"));
    }

    public void ficheRetournee(Evaluation e, boolean versSalarie, String motif) {
        if (versSalarie) envoyer(e.getSalarie(), "Votre auto-évaluation vous est retournée pour correction",
                gabarit("Correction demandée sur votre auto-évaluation", "Bonjour " + prenom(e.getSalarie()) + ", <b>" + nom(e.getEvaluateur()) + "</b> vous retourne votre fiche pour correction :",
                        "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px'>" + esc(motif) + "</blockquote>" + contexte(e), "Corriger ma fiche", lienFiche(e), "À corriger"));
        else envoyer(e.getEvaluateur(), "Évaluation retournée par la DCHT : " + e.getSalarie().getNomComplet(),
                gabarit("La DCHT vous retourne une évaluation", "Bonjour " + prenom(e.getEvaluateur()) + ", l'évaluation de <b>" + nom(e.getSalarie()) + "</b> vous est retournée pour correction :",
                        "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px'>" + esc(motif) + "</blockquote>" + contexte(e), "Corriger l'évaluation", lienFiche(e), "À corriger"));
    }

    public void ficheValidee(Evaluation e) {
        String note = e.getNoteGlobale() == null ? "—" : e.getNoteGlobale() + " / 20 : " + esc(e.getAppreciation());
        String corps = contexte(e) + "<p style='margin:14px 0 0;font-size:14px'>Note définitive : <b>" + note + "</b></p>";
        envoyer(e.getSalarie(), "Votre évaluation est validée : " + e.getCampagne().getLibelle(),
                gabarit("Votre évaluation est validée", "Bonjour " + prenom(e.getSalarie()) + ", la DCHT a validé votre fiche d'évaluation. Elle est maintenant définitive ; vous pouvez la consulter ou la télécharger quand vous voulez.", corps, "Consulter ma fiche", lienFiche(e), "Validée"));
        envoyer(e.getEvaluateur(), "Évaluation validée : " + e.getSalarie().getNomComplet(),
                gabarit("Évaluation validée par la DCHT", "Bonjour " + prenom(e.getEvaluateur()) + ", l'évaluation de <b>" + nom(e.getSalarie()) + "</b> a été validée.", corps, "Consulter la fiche", lienFiche(e), "Validée"));
    }

    /** Relance DCHT : message libre adressé au collaborateur, au N+1 ou au directeur selon l'étape de la fiche. */
    public void relance(Evaluation e, Users dest, String objet, String message, Users auteur) {
        String corps = "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px;white-space:pre-line'>" + esc(message) + "</blockquote>" + contexte(e)
                + "<p style='margin:14px 0 0;font-size:12.5px;color:#6b7280'>Relance émise par " + nom(auteur) + " (DCHT) le " + DT.format(new java.util.Date()) + ".</p>";
        envoyer(dest, "Relance : " + objet, gabarit(objet, "Bonjour " + prenom(dest) + ", la Direction du Capital Humain vous adresse une relance concernant la fiche d'évaluation de <b>" + nom(e.getSalarie()) + "</b> :", corps, "Ouvrir la fiche", lienFiche(e), "Relance DCHT"));
    }

    /** Relance d'un N+1 qui n'a pas encore intégré un collaborateur évaluable à la campagne. */
    public void relanceIntegration(Users n1, Users salarie, Campagne c, String message, Users auteur) {
        String corps = "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px;white-space:pre-line'>" + esc(message) + "</blockquote>"
                + fiche("Collaborateur", nom(salarie), "Campagne", esc(c.getLibelle()), "Échéance", c.getDateFin() == null ? "—" : c.getDateFin().format(D))
                + "<p style='margin:14px 0 0;font-size:12.5px;color:#6b7280'>Relance émise par " + nom(auteur) + " (DCHT) le " + DT.format(new java.util.Date()) + ".</p>";
        envoyer(n1, "Relance - collaborateur à intégrer à la campagne " + c.getLibelle(),
                gabarit("Un collaborateur attend son intégration à la campagne", "Bonjour " + prenom(n1) + ", <b>" + nom(salarie) + "</b> n'a pas encore de fiche d'évaluation pour la campagne <b>" + esc(c.getLibelle()) + "</b>.", corps, "Ouvrir mon équipe", base() + "/ui/equipe", "Relance DCHT"));
    }

    /**
     * Ouverture d'une campagne : annonce à un collaborateur actif. Sans objectif validé pour l'exercice,
     * le message porte en rouge qu'il ne peut pas être évalué en l'état.
     */
    public void campagneOuverte(Campagne c, Users u, long nbObjectifs) {
        boolean eligible = nbObjectifs > 0;
        String echeance = c.getDateFin() == null ? "—" : c.getDateFin().format(D);
        String corps = fiche("Campagne", esc(c.getLibelle()), "Type", esc(c.getTypeLabel()), "Exercice évalué", String.valueOf(c.getExercice()),
                "Échéance", echeance, "Objectifs validés", eligible ? nbObjectifs + " objectif" + (nbObjectifs > 1 ? "s" : "") : "<span style='color:#b91c1c'>Aucun</span>");
        if (c.getDescription() != null && !c.getDescription().isBlank())
            corps += "<blockquote style='margin:14px 0 0;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px;white-space:pre-line'>" + esc(c.getDescription()) + "</blockquote>";
        if (eligible) {
            corps += "<p style='margin:16px 0 0;font-size:14px;line-height:1.55'>Votre supérieur hiérarchique va vous intégrer à la campagne. Vous recevrez alors un e-mail pour renseigner votre auto-évaluation.</p>";
        } else {
            corps += "<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;margin:16px 0 0'><tr><td style='padding:14px 16px;border:1px solid #fecaca;border-left:4px solid #dc2626;border-radius:8px;background:#fef2f2;color:#b91c1c;font-size:14px;line-height:1.55'>"
                    + "<b>Vous ne pouvez pas être évalué(e) pour le moment : aucun objectif validé n'est enregistré à votre nom pour l'exercice " + c.getExercice() + ".</b><br/>"
                    + "Rapprochez-vous de votre supérieur hiérarchique pour fixer et valider vos objectifs avant l'échéance du " + echeance + ".</td></tr></table>";
        }
        envoyer(u, "Ouverture de la campagne d'évaluation : " + c.getLibelle(),
                gabarit("La campagne " + c.getLibelle() + " est ouverte", "Bonjour " + prenom(u) + ", la Direction du Capital Humain lance la campagne d'évaluation <b>" + esc(c.getLibelle()) + "</b>.",
                        corps, eligible ? "Consulter mes objectifs" : "Voir mes objectifs", base() + "/ui/objectifs", eligible ? "Campagne ouverte" : "Action requise"));
    }

    // objectifs
    public void objectifPropose(FicheObjectifs f, Objectif o, Users auteur) {
        Users n1 = f.getSalarie().getSuperieurHierarchique();
        envoyer(n1, "Objectif proposé à valider : " + f.getSalarie().getNomComplet(),
                gabarit("Un collaborateur propose un objectif", "Bonjour " + prenom(n1) + ", <b>" + nom(f.getSalarie()) + "</b> propose un objectif pour l'exercice " + f.getExercice() + ". Validez-le, modifiez-le ou refusez-le avec un motif.",
                        fiche("Rubrique", esc(o.getNature() == null ? "" : o.getNature().getLibelle()), "Objectif", esc(o.getLibelle()), "Indicateur", esc(o.getIndicateur()), "Périodicité", esc(o.getPeriodiciteEnum().getLibelle())),
                        "Examiner la proposition", lienObjectifs(f), "À valider"));
        envoyer(f.getSalarie(), "Accusé de réception - objectif proposé",
                gabarit("Votre proposition d'objectif est transmise", "Bonjour " + prenom(f.getSalarie()) + ", votre proposition a été transmise à <b>" + nom(n1) + "</b> pour validation.",
                        fiche("Objectif", esc(o.getLibelle())), "Voir mes objectifs", lienObjectifs(f), "Accusé d'envoi"));
    }

    public void objectifTraite(FicheObjectifs f, Objectif o, boolean valide) {
        envoyer(f.getSalarie(), valide ? "Objectif validé par votre N+1" : "Objectif refusé par votre N+1",
                gabarit(valide ? "Votre objectif a été validé" : "Votre proposition d'objectif a été refusée", "Bonjour " + prenom(f.getSalarie()) + ", votre supérieur hiérarchique a " + (valide ? "validé" : "refusé") + " l'objectif suivant" + (valide ? "." : " :"),
                        (valide ? "" : "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px'>" + esc(o.getMotifRefus()) + "</blockquote>") + fiche("Objectif", esc(o.getLibelle())),
                        "Voir mes objectifs", lienObjectifs(f), valide ? "Validé" : "Refusé"));
    }

    public void objectifConteste(FicheObjectifs f, Objectif o) {
        Users n1 = f.getSalarie().getSuperieurHierarchique();
        String corps = "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px'><b>"
                + esc(o.getContestationMotifLabel()) + "</b><br/>" + esc(o.getContestationCommentaire()) + "</blockquote>"
                + fiche("Objectif", esc(o.getLibelle()), "Indicateur", esc(o.getIndicateur()), "Cible", esc(o.getCible()));
        envoyer(n1, "Demande de révision d'objectif : " + f.getSalarie().getNomComplet(),
                gabarit("Un collaborateur demande la révision d'un objectif", "Bonjour " + prenom(n1) + ", <b>" + nom(f.getSalarie()) + "</b> demande la révision d'un objectif de l'exercice " + f.getExercice()
                        + ". Révisez-le ou maintenez-le en motivant votre réponse : la fiche ne pourra être signée qu'ensuite.", corps, "Traiter la demande", lienObjectifs(f), "Révision demandée"));
        envoyer(f.getSalarie(), "Accusé de réception - demande de révision d'objectif",
                gabarit("Votre demande de révision est transmise", "Bonjour " + prenom(f.getSalarie()) + ", votre demande a été transmise à <b>" + nom(n1) + "</b>. Vous serez prévenu de sa réponse.",
                        corps, "Voir mes objectifs", lienObjectifs(f), "Accusé d'envoi"));
    }

    public void contestationTraitee(FicheObjectifs f, Objectif o) {
        boolean revise = o.isContestationRevise();
        envoyer(f.getSalarie(), revise ? "Objectif révisé suite à votre demande" : "Objectif maintenu par votre N+1",
                gabarit(revise ? "Votre N+1 a révisé l'objectif" : "Votre N+1 maintient l'objectif",
                        "Bonjour " + prenom(f.getSalarie()) + ", votre supérieur hiérarchique a répondu à votre demande de révision.",
                        (o.getContestationReponse() == null || o.getContestationReponse().isBlank() ? "" : "<blockquote style='margin:0 0 14px;padding:12px 16px;border-left:4px solid #07294a;background:#f2f6fa;font-size:14px'>" + esc(o.getContestationReponse()) + "</blockquote>")
                                + fiche("Objectif", esc(o.getLibelle()), "Votre motif", esc(o.getContestationMotifLabel())),
                        "Voir mes objectifs", lienObjectifs(f), revise ? "Révisé" : "Maintenu"));
    }

    public void ficheObjectifsTransmise(FicheObjectifs f) {
        Users n1 = f.getSalarie().getSuperieurHierarchique();
        envoyer(f.getSalarie(), "Vos objectifs " + f.getExercice() + " sont à consulter et signer",
                gabarit("Votre fiche d'objectifs vous est transmise", "Bonjour " + prenom(f.getSalarie()) + ", <b>" + nom(n1) + "</b> vous transmet vos objectifs pour l'exercice " + f.getExercice()
                        + ". Prenez-en connaissance : signez la fiche si elle vous convient, ou demandez la révision d'un objectif en précisant pourquoi. Votre N+1 signera après vous.",
                        fiche("Exercice", String.valueOf(f.getExercice()), "Objectifs", String.valueOf(f.getNbValides())), "Consulter mes objectifs", lienObjectifs(f), "À signer"));
    }

    public void ficheObjectifsSigneeCollaborateur(FicheObjectifs f) {
        Users n1 = f.getSalarie().getSuperieurHierarchique();
        envoyer(n1, "Fiche d'objectifs signée par " + f.getSalarie().getNomComplet() + " : à votre tour",
                gabarit("Le collaborateur a signé sa fiche d'objectifs", "Bonjour " + prenom(n1) + ", <b>" + nom(f.getSalarie()) + "</b> a signé sa fiche d'objectifs " + f.getExercice() + ". Signez-la à votre tour pour la figer.",
                        fiche("Exercice", String.valueOf(f.getExercice()), "Objectifs", String.valueOf(f.getNbValides())), "Signer la fiche", lienObjectifs(f), "À signer"));
    }

    public void ficheObjectifsSignee(FicheObjectifs f) {
        String corps = fiche("Collaborateur", nom(f.getSalarie()), "Exercice", String.valueOf(f.getExercice()), "Objectifs validés", String.valueOf(f.getNbValides()));
        envoyer(f.getSalarie(), "Votre fiche d'objectifs " + f.getExercice() + " est signée",
                gabarit("Fiche d'objectifs signée par les deux parties", "Bonjour " + prenom(f.getSalarie()) + ", votre fiche d'objectifs est signée. Elle servira de référence pour vos évaluations de l'année.", corps, "Consulter ma fiche", lienObjectifs(f), "Signée"));
        envoyer(f.getN1(), "Fiche d'objectifs signée : " + f.getSalarie().getNomComplet(),
                gabarit("Fiche d'objectifs signée", "Bonjour " + prenom(f.getN1()) + ", la fiche d'objectifs de <b>" + nom(f.getSalarie()) + "</b> est signée par les deux parties.", corps, "Consulter la fiche", lienObjectifs(f), "Signée"));
    }

    public List<Users> destinatairesDcht() {
        try { return userDAO.findActiveByPrivilegeCodes(List.of("EVAL_DCHT", "CAMP_CREATE")); } catch (Exception e) { return List.of(); }
    }

    // Rappels de fixation des objectifs
    private static final String TH = "<th style='text-align:left;padding:8px 10px;background:#f6f8fa;color:#6b7280;font-size:11.5px;font-weight:600;border-bottom:1px solid #e5e9ee'>";
    private static final String TD = "<td style='padding:8px 10px;font-size:13px;color:#111827;border-bottom:1px solid #eef1f4'>";
    private static final String TDN = "<td style='padding:8px 10px;font-size:13px;color:#111827;border-bottom:1px solid #eef1f4;text-align:right'>";

    /** Rappel au N+1 : ses collaborateurs directs dont la fiche d'objectifs n'est pas signée. */
    public void rappelObjectifsManager(Users n1, List<SuiviObjectifsService.Ligne> lignes, int exercice) {
        long retard = lignes.stream().filter(SuiviObjectifsService.Ligne::enRetard).count();
        StringBuilder t = new StringBuilder("<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;border:1px solid #e5e9ee;border-radius:8px;border-collapse:separate;overflow:hidden'>")
                .append("<tr>").append(TH).append("Collaborateur</th>").append(TH).append("Situation</th>").append(TH).append("Objectifs</th></tr>");
        for (SuiviObjectifsService.Ligne l : lignes)
            t.append("<tr>").append(TD).append("<b>").append(nom(l.salarie())).append("</b>").append(l.enRetard() ? " <span style='color:#b91c1c;font-size:11.5px'>en retard</span>" : "").append("</td>")
             .append(TD).append(esc(l.statutLabel())).append("</td>")
             .append(TD).append(l.valides()).append(" validé(s)").append(l.proposes() > 0 ? ", " + l.proposes() + " proposé(s)" : "").append("</td></tr>");
        t.append("</table>");
        String accroche = "Bonjour " + prenom(n1) + ", " + lignes.size() + (lignes.size() > 1 ? " de vos collaborateurs n'ont" : " de vos collaborateurs n'a")
                + " pas encore de fiche d'objectifs " + exercice + " signée" + (retard > 0 ? ", dont <b>" + retard + " en retard</b>" : "") + ". Sans objectifs validés, ils ne pourront pas être évalués.";
        envoyer(n1, "Rappel : objectifs " + exercice + " à fixer pour votre équipe",
                gabarit("Objectifs " + exercice + " à finaliser", accroche, t.toString(), "Ouvrir mon équipe", base() + "/ui/equipe", "Rappel"));
    }

    /** Synthèse DCHT : avancement de la fixation des objectifs par direction. */
    public void rappelObjectifsDcht(Users rh, List<SuiviObjectifsService.Agregat> dirs, SuiviObjectifsService.Agregat total, int exercice) {
        StringBuilder t = new StringBuilder("<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;border:1px solid #e5e9ee;border-radius:8px;border-collapse:separate;overflow:hidden'>")
                .append("<tr>").append(TH).append("Direction</th>").append(TH.replace("text-align:left", "text-align:right")).append("Évaluables</th>")
                .append(TH.replace("text-align:left", "text-align:right")).append("Signées</th>").append(TH.replace("text-align:left", "text-align:right")).append("Restantes</th>")
                .append(TH.replace("text-align:left", "text-align:right")).append("En retard</th>").append(TH.replace("text-align:left", "text-align:right")).append("Taux</th></tr>");
        for (SuiviObjectifsService.Agregat a : dirs)
            t.append("<tr>").append(TD).append(esc(a.getNom())).append("</td>").append(TDN).append(a.getEffectif()).append("</td>").append(TDN).append(a.getSignees()).append("</td>")
             .append(TDN).append(a.getRestants()).append("</td>").append(TDN).append(a.getRetard() > 0 ? "<span style='color:#b91c1c'>" + a.getRetard() + "</span>" : "0").append("</td>")
             .append(TDN).append("<b>").append(a.getTaux()).append(" %</b></td></tr>");
        t.append("<tr>").append(TD).append("<b>Total</b></td>").append(TDN).append("<b>").append(total.getEffectif()).append("</b></td>").append(TDN).append("<b>").append(total.getSignees()).append("</b></td>")
         .append(TDN).append("<b>").append(total.getRestants()).append("</b></td>").append(TDN).append("<b>").append(total.getRetard()).append("</b></td>").append(TDN).append("<b>").append(total.getTaux()).append(" %</b></td></tr></table>");
        String accroche = "Bonjour " + prenom(rh) + ", voici où en est la fixation des objectifs " + exercice + " : <b>" + total.getSignees() + " fiche(s) signée(s) sur " + total.getEffectif()
                + "</b>" + (total.getRetard() > 0 ? ", " + total.getRetard() + " en retard" : "") + ". Les N+1 concernés reçoivent leur propre rappel.";
        envoyer(rh, "Fixation des objectifs " + exercice + " : point par direction",
                gabarit("Fixation des objectifs " + exercice, accroche, t.toString(), "Voir le suivi", base() + "/ui/suiviObjectifs", "Synthèse"));
    }

    // Période d'essai
    private String lienEssai(EvaluationEssai e) { return base() + "/ui/periodeEssai?id=" + e.getId(); }
    private static String d(java.time.LocalDate x) { return x == null ? "—" : x.format(D); }

    /** « fin de période d'essai » ou « fin de CDD ». */
    private static String quoi(EvaluationEssai e) { return e.isFinCdd() ? "fin de CDD" : "fin de période d'essai"; }
    private static String decision(EvaluationEssai e) {
        String d = esc(e.getDecisionLabel());
        if (e.getProlongationMois() != null) d += " (" + e.getProlongationMois() + " mois)";
        if (e.getNouvelleEcheance() != null) d += " jusqu'au " + d(e.getNouvelleEcheance());
        return d;
    }

    public void essaiOuverte(EvaluationEssai e) {
        envoyer(e.getSalarie(), "Votre évaluation de " + quoi(e),
                gabarit("Évaluation de " + quoi(e), "Bonjour " + prenom(e.getSalarie()) + ", <b>" + nom(e.getEvaluateur()) + "</b> a ouvert votre évaluation de " + quoi(e) + ". Une fois transmise par votre N+1, vous pourrez en prendre connaissance, ajouter votre commentaire et la signer.",
                        fiche("Contrat", esc(e.getTypeLabel()), "Échéance", d(e.getEcheance()), "Évaluateur", nom(e.getEvaluateur())),
                        "Consulter mon évaluation", lienEssai(e), e.isFinCdd() ? "Fin de CDD" : "Période d'essai"));
    }

    /** Le N+1 a transmis : avis attendu du directeur, visa facultatif du N+2 ; la DCHT n'est alertée qu'après l'avis. */
    public void essaiTransmise(EvaluationEssai e, Users n2, Users directeur) {
        String corps = fiche("Collaborateur", nom(e.getSalarie()), "Contrat", esc(e.getTypeLabel()), "Échéance", d(e.getEcheance()), "Évaluateur", nom(e.getEvaluateur()),
                "Proposition", decision(e));
        envoyer(e.getSalarie(), "Votre évaluation de " + quoi(e) + " est à signer",
                gabarit("Votre évaluation est à signer", "Bonjour " + prenom(e.getSalarie()) + ", <b>" + nom(e.getEvaluateur()) + "</b> a transmis votre évaluation de " + quoi(e) + ". Prenez-en connaissance, ajoutez votre commentaire si vous le souhaitez et signez-la.",
                        fiche("Contrat", esc(e.getTypeLabel()), "Échéance", d(e.getEcheance())), "Consulter et signer", lienEssai(e), "À signer"));
        if (directeur != null)
            envoyer(directeur, "Avis du directeur à donner - " + quoi(e) + " de " + e.getSalarie().getNomComplet(),
                    gabarit("Une évaluation de " + quoi(e) + " attend votre avis", "Bonjour " + prenom(directeur) + ", <b>" + nom(e.getEvaluateur()) + "</b> a transmis l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b>. Votre avis est requis avant la validation par la DCHT.",
                            corps, "Donner mon avis", lienEssai(e), "Avis du directeur"));
        if (n2 != null && (directeur == null || n2.getId() != directeur.getId()))
            envoyer(n2, "Visa facultatif - " + quoi(e) + " de " + e.getSalarie().getNomComplet(),
                    gabarit("Une évaluation de " + quoi(e) + " peut recevoir votre visa", "Bonjour " + prenom(n2) + ", l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b> réalisée par <b>" + nom(e.getEvaluateur()) + "</b> peut recevoir votre visa (facultatif).",
                            corps, "Apposer mon visa", lienEssai(e), "Visa N+2"));
        if (directeur == null)
            for (Users rh : dcht())
                envoyer(rh, "Évaluation de " + quoi(e) + " à valider : " + e.getSalarie().getNomComplet(),
                        gabarit("Évaluation de " + quoi(e) + " à valider", "Bonjour " + prenom(rh) + ", <b>" + nom(e.getEvaluateur()) + "</b> a transmis l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b>. La direction n'a pas de directeur désigné : saisissez l'avis puis validez.",
                                corps, "Examiner l'évaluation", lienEssai(e), "À valider"));
    }

    /** Avis du directeur donné : l'évaluation est prête pour la DCHT. */
    public void essaiAvisDirecteur(EvaluationEssai e) {
        String corps = fiche("Collaborateur", nom(e.getSalarie()), "Évaluateur", nom(e.getEvaluateur()),
                "Proposition", decision(e),
                "Avis du directeur", esc(e.getAvisDirecteur()));
        for (Users rh : dcht())
            envoyer(rh, "Évaluation de " + quoi(e) + " à valider : " + e.getSalarie().getNomComplet(),
                    gabarit("Évaluation de " + quoi(e) + " à valider", "Bonjour " + prenom(rh) + ", le directeur a donné son avis sur l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b>. Elle attend votre validation.",
                            corps, "Examiner l'évaluation", lienEssai(e), "À valider"));
    }

    public void essaiRetournee(EvaluationEssai e) {
        envoyer(e.getEvaluateur(), "Évaluation de " + quoi(e) + " retournée : " + e.getSalarie().getNomComplet(),
                gabarit("Évaluation retournée par la DCHT", "Bonjour " + prenom(e.getEvaluateur()) + ", la DCHT vous retourne l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b>.",
                        fiche("Motif", esc(e.getMotifRetour())), "Corriger l'évaluation", lienEssai(e), "Retournée"));
    }

    public void essaiValidee(EvaluationEssai e) {
        String corps = fiche("Décision", decision(e));
        for (Users u : new Users[]{e.getSalarie(), e.getEvaluateur()})
            envoyer(u, "Évaluation de " + quoi(e) + " validée",
                    gabarit("Évaluation de " + quoi(e) + " validée", "Bonjour " + prenom(u) + ", la DCHT a validé l'évaluation (" + quoi(e) + ") de <b>" + nom(e.getSalarie()) + "</b>.",
                            corps, "Consulter l'évaluation", lienEssai(e), "Validée"));
    }

    // Alertes graduées (rappels, relances, niveau critique)
    private static String couleurNiveau(String n) {
        return switch (n == null ? "" : n) { case Relance.CRITIQUE -> "#b91c1c"; case Relance.URGENT -> "#c2410c"; case Relance.RELANCE -> "#b8872b"; default -> "#0a5c8f"; };
    }
    private static String pastilleNiveau(Relance r) {
        String c = couleurNiveau(r.getNiveau());
        String txt = r.getNiveauLabel() + (r.getNbRelances() > 0 ? " · relance n°" + r.getNbRelances() : "");
        return "<span style='display:inline-block;padding:2px 8px;border-radius:999px;border:1px solid " + c + ";color:" + c + ";font-size:11px;font-weight:700;white-space:nowrap'>" + esc(txt) + "</span>";
    }
    private static String echeanceTexte(Relance r) {
        if (r.getEcheance() == null) return "—";
        long j = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), r.getEcheance());
        String date = r.getEcheance().format(D);
        if (j > 0) return date + " <span style='color:#6b7280'>(dans " + j + " j)</span>";
        if (j == 0) return date + " <span style='color:#b8872b;font-weight:700'>(aujourd'hui)</span>";
        return date + " <span style='color:#b91c1c;font-weight:700'>(dépassée de " + (-j) + " j)</span>";
    }
    private String tableAlertes(List<Relance> l, boolean avecLien) {
        StringBuilder t = new StringBuilder("<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;border:1px solid #e5e9ee;border-radius:8px;border-collapse:separate;overflow:hidden'>")
                .append("<tr>").append(TH).append("Action attendue</th>").append(TH).append("Échéance</th>").append(TH).append("Niveau</th></tr>");
        for (Relance r : l) {
            String lib = esc(r.getLibelle());
            if (avecLien && r.getLien() != null) lib = "<a href='" + base() + r.getLien() + "' style='color:#0a5c8f;text-decoration:none;font-weight:600'>" + lib + "</a>";
            t.append("<tr>").append(TD).append(lib).append("</td>").append(TD).append(echeanceTexte(r)).append("</td>").append(TD).append(pastilleNiveau(r)).append("</td></tr>");
        }
        return t.append("</table>").toString();
    }
    private static final String AVERTISSEMENT_NC = "<div style='margin:16px 0 0;padding:12px 16px;border-left:4px solid #b91c1c;background:#fdecec;font-size:14px;color:#7f1d1d'>"
            + "<b>Situation critique.</b> Malgré les relances successives, ces actions ne sont toujours pas réalisées. "
            + "<b>Une fiche de non-conformité sera établie à l'encontre du responsable</b> et transmise à la Direction du Capital Humain. "
            + "Votre supérieur hiérarchique et la DCHT sont informés.</div>";
    private static final String AVERTISSEMENT_URGENT = "<div style='margin:16px 0 0;padding:12px 16px;border-left:4px solid #c2410c;background:#fff4ec;font-size:14px;color:#7c2d12'>"
            + "<b>Dernier avertissement.</b> À la prochaine relance, ces actions passeront au niveau critique : une fiche de non-conformité sera alors établie à l'encontre du responsable.</div>";

    /** Alerte regroupée à un acteur : toutes ses actions en attente, du plus grave au moins grave. */
    public void alertes(Users dest, List<Relance> l) {
        if (l.isEmpty()) return;
        String max = l.stream().map(Relance::getNiveau).max(java.util.Comparator.comparingInt(Relance::rang)).orElse(Relance.RAPPEL);
        long enRetard = l.stream().filter(r -> r.getNbRelances() > 0).count();
        String accroche = "Bonjour " + prenom(dest) + ", " + (l.size() > 1 ? l.size() + " actions sont attendues" : "une action est attendue") + " de votre part"
                + (enRetard > 0 ? ", dont <b>" + enRetard + " en dépassement de délai</b>" : "") + ".";
        String corps = tableAlertes(l, true);
        if (Relance.CRITIQUE.equals(max)) corps += AVERTISSEMENT_NC;
        else if (Relance.URGENT.equals(max)) corps += AVERTISSEMENT_URGENT;
        else if (Relance.RELANCE.equals(max)) corps += "<p style='margin:14px 0 0;font-size:13px;color:#6b7280'>Sans action de votre part, vous serez relancé tous les " + cd.mura.hrx.service.AlertesService.INTERVALLE_RELANCE
                + " jours ; à la " + cd.mura.hrx.service.AlertesService.SEUIL_CRITIQUE + "<sup>e</sup> relance, la situation devient critique et donne lieu à une fiche de non-conformité.</p>";
        String sujet = switch (max) {
            case Relance.CRITIQUE -> "CRITIQUE - fiche de non-conformité : actions en retard";
            case Relance.URGENT -> "URGENT - actions en retard à régulariser";
            case Relance.RELANCE -> "Relance - délai dépassé";
            default -> "Rappel - échéance proche";
        };
        String titre = switch (max) {
            case Relance.CRITIQUE -> "Situation critique : actions toujours en attente";
            case Relance.URGENT -> "Actions urgentes à régulariser";
            case Relance.RELANCE -> "Délai dépassé : merci de régulariser";
            default -> "Échéance proche";
        };
        envoyer(dest, sujet, gabarit(titre, accroche, corps, "Ouvrir HRX", base() + "/ui/home", Relance.niveauLabel(max)));
    }

    /** Copie au supérieur hiérarchique d'un acteur arrivé au niveau critique. */
    public void alertesCritiquesResponsable(Users chef, Users dest, List<Relance> l) {
        String accroche = "Bonjour " + prenom(chef) + ", malgré plusieurs relances, <b>" + nom(dest) + "</b> n'a pas réalisé les actions ci-dessous. "
                + "La situation est critique : une fiche de non-conformité sera établie à son encontre.";
        envoyer(chef, "CRITIQUE - actions en retard de " + dest.getNomComplet(),
                gabarit("Collaborateur en situation critique", accroche, tableAlertes(l, false), "Ouvrir HRX", base() + "/ui/home", "Critique"));
    }

    /** Synthèse DCHT des acteurs au niveau critique (fiches de non-conformité à établir). */
    public void alertesCritiquesDcht(Users rh, java.util.Map<Users, List<Relance>> m) {
        StringBuilder t = new StringBuilder("<table role='presentation' cellspacing='0' cellpadding='0' style='width:100%;border:1px solid #e5e9ee;border-radius:8px;border-collapse:separate;overflow:hidden'>")
                .append("<tr>").append(TH).append("Responsable</th>").append(TH).append("Actions en retard</th>").append(TH).append("Relances</th></tr>");
        for (java.util.Map.Entry<Users, List<Relance>> e : m.entrySet()) {
            StringBuilder actions = new StringBuilder();
            int maxRel = 0;
            for (Relance r : e.getValue()) { actions.append("• ").append(esc(r.getLibelle())).append(" <span style='color:#b91c1c'>(+").append(r.getJoursRetard()).append(" j)</span><br>"); maxRel = Math.max(maxRel, r.getNbRelances()); }
            t.append("<tr>").append(TD).append("<b>").append(nom(e.getKey())).append("</b></td>").append(TD).append(actions).append("</td>").append(TDN).append(maxRel).append("</td></tr>");
        }
        t.append("</table>");
        String accroche = "Bonjour " + prenom(rh) + ", " + (m.size() > 1 ? m.size() + " responsables sont" : "un responsable est")
                + " en situation critique après plusieurs relances restées sans effet. Une fiche de non-conformité est à établir pour chacun.";
        envoyer(rh, "Fiches de non-conformité à établir : " + m.size() + " responsable(s)",
                gabarit("Responsables en situation critique", accroche, t.toString(), "Ouvrir HRX", base() + "/ui/home", "Non-conformité"));
    }

    // Évaluation à chaud
    public void evalChaudOuverte(EvalChaud c, Users u, boolean relance) {
        String corps = fiche("Formation", esc(c.getIntitule()), "Organisme", esc(c.getOrganisme() == null || c.getOrganisme().isBlank() ? "—" : c.getOrganisme()),
                "Questionnaire ouvert jusqu'au", d(c.getDateFin()));
        if (c.getConsignes() != null && !c.getConsignes().isBlank())
            corps += "<blockquote style='margin:14px 0 0;padding:12px 16px;border-left:4px solid #b8872b;background:#fdf8ee;font-size:14px;white-space:pre-line'>" + esc(c.getConsignes()) + "</blockquote>";
        String accroche = c.isFroid()
                ? "Bonjour " + prenom(u) + ", quelques mois après la formation <b>" + esc(c.getIntitule()) + "</b>, dites-nous ce qu'elle a changé dans votre poste : "
                  + "compétences mises en pratique, changements observés, impact sur l'activité. Votre supérieur hiérarchique complétera ensuite la fiche par son avis."
                : "Bonjour " + prenom(u) + ", vous avez suivi la formation <b>" + esc(c.getIntitule()) + "</b>. Donnez votre avis à chaud : le questionnaire prend quelques minutes.";
        envoyer(u, (relance ? "Rappel - " : "") + (c.isFroid() ? "Évaluation à froid : " : "Évaluation à chaud : ") + c.getIntitule(),
                gabarit(c.isFroid() ? "Évaluation à froid de votre formation" : "Votre avis sur la formation", accroche,
                        corps, "Répondre au questionnaire", base() + "/ui/questionnaire?id=" + c.getId(), relance ? "Rappel" : "Questionnaire"));
    }

    /** Évaluation à froid remplie par un collaborateur : son supérieur hiérarchique donne son avis sur l'efficacité de la formation. */
    public void avisFroidAttendu(EvalChaud c, Users collaborateur, Users n1, int reponseId) {
        String corps = fiche("Collaborateur", esc(collaborateur.getNomComplet()), "Formation", esc(c.getIntitule()),
                "Organisme", esc(c.getOrganisme() == null || c.getOrganisme().isBlank() ? "—" : c.getOrganisme()));
        envoyer(n1, "Avis attendu - évaluation à froid de " + collaborateur.getNomComplet(),
                gabarit("Votre avis sur l'efficacité de la formation", "Bonjour " + prenom(n1) + ", " + esc(collaborateur.getNomComplet())
                                + " a rempli sa fiche d'évaluation à froid. Après un échange avec lui, donnez votre avis général sur l'efficacité de la formation et signez la fiche.",
                        corps, "Donner mon avis", base() + "/ui/questionnaire?rep=" + reponseId, "Avis attendu"));
    }

    private List<Users> dcht() {
        try { return userDAO.findActiveByPrivilegeCodes(List.of("EVAL_DCHT")); } catch (Exception e) { return List.of(); }
    }
}
