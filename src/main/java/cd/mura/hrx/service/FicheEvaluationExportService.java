package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.*;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.events.Event;
import com.itextpdf.kernel.events.IEventHandler;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.BorderRadius;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Export de la fiche MAF 159 DCHT 02 « Fiche d'évaluation individuelle, Collaborateurs BGFIBank RDC SA », reproduction du modèle */
@ApplicationScoped
public class FicheEvaluationExportService implements Serializable {

    private static final String NAVY = "17365D", GREY = "E7E6E6", BAND = "B8CCE4", CARTOUCHE = "C6D9F1", CYAN = "00B0F0", GREYBLUE = "95B3D7", FRAME = "808080", BLACK = "000000", LABEL = "D9D9D9";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final SimpleDateFormat DT = new SimpleDateFormat("dd/MM/yyyy HH:mm");
    private static final String[][] GRILLE = {{"100%", "20", "Excellent"}, {"85 à 99%", "De 17 à 19", "Bon"}, {"70 à 84%", "De 15 à 16", "Moyennement Bon"}, {"51 à 69%", "De 10 à 14", "Insuffisant"}, {"Moins de 50%", "Moins de 10", "Mauvais"}};
    private static final String PIED = "MAF 159 DCHT  02 / FICHE D'EVALUATION COLLABORATEURS";
    private static final String[][] CASES_N1 = {{"ATTEINTS", "Objectifs atteints"}, {"MOYENNEMENT", "Objectifs moyennement atteints"}, {"FAIBLEMENT", "Objectifs faiblement atteints"}, {"NON_ATTEINTS", "Objectifs non atteints"}};
    private static final String[][] CASES_N = {{"BIEN_POSTE", "Bien dans son poste/poursuivre"}, {"ACCOMPAGNEMENT", "Besoin d'accompagnement"}, {"CHANGER_POSTE", "Souhaite changer de poste"}, {"AUTRES", "Autres souhaits"}};

    private static String s(String v) { return v == null ? "" : v; }
    private static String pct(Integer v) { return v == null ? "" : v + "%"; }
    private static String mp(Evaluation e) { return Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "F-A" : "M-P"; }
    private static String mpLong(Evaluation e) { return Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FIN D'ANNEE" : "MI-PARCOURS"; }
    private static String anciennete(Users u) {
        if (u.getDateEmbauche() == null) return "";
        var d = u.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        var p = java.time.Period.between(d, java.time.LocalDate.now());
        String a = p.getYears() > 0 ? p.getYears() + " ans" : "", m = p.getMonths() > 0 ? p.getMonths() + " mois" : "";
        String r = a + (a.isEmpty() || m.isEmpty() ? "" : " et ") + m;
        return r.isEmpty() ? "moins d'un mois" : r;
    }
    private static byte[] logo() {
        try {
            var ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Exception ignored) { }
        return null;
    }
    private static List<String> activites(List<ObjectifEvaluation> l) { List<String> a = new ArrayList<>(); for (ObjectifEvaluation o : l) { String k = s(o.getObjectif().getActivite()).trim(); if (!a.contains(k)) a.add(k); } return a; }
    private static List<String> familles(Evaluation e, String section) { List<String> f = new ArrayList<>(); for (EvaluationCritere c : e.getCriteres()) if (c.getCritere().getSection().equals(section) && !f.contains(c.getCritere().getFamille())) f.add(c.getCritere().getFamille()); return f; }
    private static String familleLabel(Evaluation e, String fam) { return e.getCriteres().stream().filter(c -> c.getCritere().getFamille().equals(fam)).findFirst().map(c -> c.getCritere().getFamilleLabel()).orElse(fam); }
    private static String[][] renseignements(Evaluation e) {
        Users u = e.getSalarie();
        return new String[][]{{"Nom et Prénom du salarié", s(u.getNom()).toUpperCase() + " " + s(u.getPrenom())}, {"Date de l'évaluation", e.getDateEvaluationRetenue() == null ? "" : "Le " + e.getDateEvaluationRetenue().format(D)},
                {"Emploi Occupé", s(u.getFonction())}, {"Ancienneté dans le poste", anciennete(u)},
                {"Supérieur hiérarchique direct", e.getEvaluateur() == null ? "" : e.getEvaluateur().getNomComplet()}, {"Classification", s(u.getCategorie())},
                {"Société/Direction/Département/Service", "BGFIBank" + (u.getDirection() == null ? "" : "/" + s(u.getDirection().getAbrege()))}};
    }
    private static String signature(String qui, Date quand) { return quand == null ? "" : "Signé électroniquement le " + DT.format(quand) + (qui == null || qui.isBlank() ? "" : " : " + qui); }

    // PDF
    public byte[] pdf(Evaluation e) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(40, 40, 60, 40);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD), ital = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE), boldItal = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLDOBLIQUE);
            Color navy = rgb(NAVY), grey = rgb(GREY), band = rgb(BAND), frame = rgb(FRAME), label = rgb(LABEL), black = rgb(BLACK);
            doc.setFont(reg).setFontSize(8).setFontColor(navy);
            byte[] lg = logo();
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new PageDecor(bold, reg, frame));
            SolidBorder b = new SolidBorder(frame, 0.75f);

            // En-tête : logo + direction à gauche, cartouche bleu arrondi à droite
            Table head = new Table(UnitValue.createPercentArray(new float[]{36, 64})).useAllAvailableWidth().setMarginBottom(10);
            Cell lc = nb();
            if (lg != null) lc.add(new Image(ImageDataFactory.create(lg)).setHeight(40)); else lc.add(new Paragraph("BGFIBank").setFont(bold).setFontSize(16).setMargin(0));
            lc.add(new Paragraph("Direction\ndu Capital Humain et de la Transformation").setFontSize(7.5f).setMargin(0).setMarginTop(4).setMultipliedLeading(1.1f));
            head.addCell(lc);
            Cell cart = new Cell().setBorder(new SolidBorder(rgb(GREYBLUE), 1f)).setBorderRadius(new BorderRadius(8)).setBackgroundColor(rgb(CARTOUCHE)).setPadding(8).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE);
            cart.add(new Paragraph("FICHE D'EVALUATION INDIVIDUELLE").setFont(bold).setFontSize(13).setMargin(0));
            cart.add(new Paragraph("«  Collaborateurs BGFIBank RDC SA  »").setFont(boldItal).setFontSize(9.5f).setMargin(0));
            cart.add(new Paragraph("Evaluation " + mp(e) + " " + e.getCampagne().getExercice()).setFont(boldItal).setFontSize(8.5f).setMargin(0));
            head.addCell(cart);
            doc.add(head);

            // Encadrés de renseignements (arrondis), 2 colonnes puis 1 pleine largeur
            String[][] rs = renseignements(e);
            Table id = new Table(UnitValue.createPercentArray(new float[]{64, 36})).useAllAvailableWidth().setMarginBottom(6);
            for (int i = 0; i < 6; i++) id.addCell(encadre(rs[i][0], rs[i][1], bold, label, black));
            doc.add(id);
            Table id2 = new Table(1).useAllAvailableWidth().setMarginBottom(6);
            id2.addCell(encadre(rs[6][0], rs[6][1], bold, label, black));
            doc.add(id2);

            // I. Objectifs
            doc.add(titreSection("I- APPRECIATION DE LA PERFORMANCE REALISEE " + mpLong(e) + " " + e.getCampagne().getExercice() + " (SUIVANT LES OBJECTIFS FIXES)", bold));
            Table t = new Table(UnitValue.createPercentArray(new float[]{35, 20, 8, 8, 29})).useAllAvailableWidth().setMarginBottom(10);
            t.addHeaderCell(hc(b, band, 1, 2, "OBJECTIFS", bold, 8));
            t.addHeaderCell(hc(b, band, 1, 2, "INDICATEURS DE PERFORMANCE", bold, 8));
            t.addHeaderCell(hc(b, band, 2, 1, "POURCENTAGE D'ATTEINTE\n" + (Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FIN D'ANNEE" : "MI-PARCOURS"), bold, 7.5f));
            t.addHeaderCell(hc(b, band, 1, 2, "COMMENTAIRES", bold, 8));
            t.addHeaderCell(hc(b, band, 1, 1, "Attendu", reg, 7.5f)); t.addHeaderCell(hc(b, band, 1, 1, "Réalisé", reg, 7.5f));
            int ai = 1, num = 1;
            for (String act : activites(e.getObjectifs())) {
                if (!act.isBlank()) { t.addCell(new Cell().setBorder(b).setBackgroundColor(grey).setPadding(4).add(new Paragraph("Activité " + ai++ + " :  " + act).setFont(bold).setFontSize(8).setMargin(0))); for (int k = 0; k < 4; k++) t.addCell(new Cell().setBorder(b).setBackgroundColor(grey)); }
                for (ObjectifEvaluation o : e.getObjectifs()) {
                    if (!s(o.getObjectif().getActivite()).trim().equals(act)) continue;
                    t.addCell(new Cell().setBorder(b).setPadding(4).setPaddingLeft(10).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(num++ + ".  " + s(o.getObjectif().getLibelle())).setFontSize(8).setMargin(0).setMultipliedLeading(1.2f)));
                    t.addCell(new Cell().setBorder(b).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(s(o.getObjectif().getIndicateur()) + (s(o.getObjectif().getCible()).isBlank() ? "" : " (cible " + o.getObjectif().getCible() + ")")).setFontSize(7.5f).setMargin(0).setMultipliedLeading(1.2f)));
                    t.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(o.getAttendu() + "%").setFontSize(8).setMargin(0)));
                    t.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(pct(o.getRealisationN1() != null ? o.getRealisationN1() : o.getRealisationSalarie())).setFontSize(8).setMargin(0)));
                    Paragraph cp = new Paragraph().setMargin(0).setMultipliedLeading(1.15f).setFontSize(6.5f);
                    if (!s(o.getCommentaireRetenu()).isBlank()) cp.add(new Text(o.getCommentaireRetenu()));
                    t.addCell(new Cell().setBorder(b).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE).add(cp));
                }
            }
            doc.add(t);

            // II. Travaux hors objectifs
            doc.add(titreSection("II- APPRECIATION DES TRAVAUX REALISES EN " + mpLong(e) + " " + e.getCampagne().getExercice() + " (EN DEHORS DES OBJECTIFS ASSIGNES)", bold));
            doc.add(new Paragraph("(Facultatif)").setFont(ital).setFontSize(8).setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(2));
            Table tv = new Table(UnitValue.createPercentArray(new float[]{30, 25, 8, 8, 29})).useAllAvailableWidth().setMarginBottom(10);
            tv.addHeaderCell(hc(b, band, 1, 2, "TYPE DE TRAVAUX", bold, 8)); tv.addHeaderCell(hc(b, band, 1, 2, "INDICATEURS DE PERFORMANCE", bold, 8));
            tv.addHeaderCell(hc(b, band, 2, 1, "POURCENTAGE D'ATTEINTE\n" + (Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FIN D'ANNEE" : "MI-PARCOURS"), bold, 7.5f)); tv.addHeaderCell(hc(b, band, 1, 2, "COMMENTAIRES", bold, 8));
            tv.addHeaderCell(hc(b, band, 1, 1, "Attendu", reg, 7.5f)); tv.addHeaderCell(hc(b, band, 1, 1, "Réalisé", reg, 7.5f));
            List<String> tacts = new ArrayList<>(); for (TravailHorsObjectif x : e.getTravauxHorsObjectifs()) { String k = s(x.getActivite()).trim(); if (!tacts.contains(k)) tacts.add(k); }
            if (tacts.isEmpty()) tacts.add("Missions professionnels");
            int ta = 1;
            for (String act : tacts) {
                tv.addCell(new Cell().setBorder(b).setBackgroundColor(grey).setPadding(4).add(new Paragraph("Activité " + ta++ + " : " + act).setFont(bold).setFontSize(8).setMargin(0))); for (int k = 0; k < 4; k++) tv.addCell(new Cell().setBorder(b).setBackgroundColor(grey));
                int n = 1;
                for (TravailHorsObjectif x : e.getTravauxHorsObjectifs()) {
                    if (!s(x.getActivite()).trim().equals(act)) continue;
                    tv.addCell(new Cell().setBorder(b).setPadding(4).add(new Paragraph(n++ + "-  " + x.getLibelle()).setFontSize(8).setMargin(0)));
                    tv.addCell(new Cell().setBorder(b).setPadding(4).add(new Paragraph(s(x.getIndicateur())).setFontSize(7.5f).setMargin(0)));
                    tv.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(x.getAttendu() + "%").setFontSize(8).setMargin(0)));
                    tv.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(pct(x.getRealise())).setFontSize(8).setMargin(0)));
                    tv.addCell(new Cell().setBorder(b).setPadding(4).add(new Paragraph(s(x.getCommentaire())).setFontSize(6.5f).setMargin(0)));
                }
                for (; n <= 3; n++) { tv.addCell(new Cell().setBorder(b).setPadding(4).setHeight(16).add(new Paragraph(n + "-").setFontSize(7.5f).setMargin(0))); for (int k = 0; k < 4; k++) tv.addCell(new Cell().setBorder(b)); }
            }
            doc.add(tv);

            // Compétences / valeurs (si évaluées)
            if (e.isAvecCompetences()) {
                competences(doc, e, "II", "EVALUATION DES COMPETENCES", new String[]{"A", "EA", "NA", "NM"}, bold, reg, band, grey, b);
                competences(doc, e, "III", "EVALUATION DE L'ADHESION AUX VALEURS DU GROUPE", new String[]{"A", "EA", "NA"}, bold, reg, band, grey, b);
                doc.add(new Paragraph("A = acquis · EA = en cours d'acquisition · NA = non acquis · NM = non mesuré").setFont(ital).setFontSize(6.5f).setMargin(0).setMarginBottom(8));
                // souhaits de formation
                doc.add(titreSection("SOUHAITS DE FORMATION", bold));
                Table tf = new Table(UnitValue.createPercentArray(new float[]{24, 36, 40})).useAllAvailableWidth().setMarginBottom(10);
                for (String h : new String[]{"Origine de la demande", "Formations demandées", "Motivation"}) tf.addHeaderCell(hc(b, band, 1, 1, h, bold, 8));
                for (String[] org : new String[][]{{"EVALUATEUR", "EVALUATEUR"}, {"EVALUE", "EVALUE"}, {"DIRECTEUR", "DIRECTEUR"}}) {
                    List<BesoinFormation> l = e.getBesoinsFormation().stream().filter(x -> org[0].equals(x.getOrigine())).toList();
                    tf.addCell(new Cell().setBorder(b).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(org[1]).setFont(bold).setFontSize(8).setMargin(0)));
                    Paragraph pf = new Paragraph().setMargin(0).setFontSize(7.5f), pm = new Paragraph().setMargin(0).setFontSize(7.5f);
                    for (BesoinFormation bf : l) { pf.add(new Text("• " + bf.getIntitule() + "\n")); pm.add(new Text(s(bf.getJustification()) + "\n")); }
                    tf.addCell(new Cell().setBorder(b).setPadding(4).setHeight(26).add(pf)); tf.addCell(new Cell().setBorder(b).setPadding(4).add(pm));
                }
                doc.add(tf);
            }

            // III. Appréciations et commentaires
            doc.add(new Paragraph("III. APPRECIATIONS ET COMMENTAIRES").setFont(boldItal).setFontSize(9).setUnderline().setMargin(0).setMarginTop(6).setMarginBottom(8));
            // N+1
            doc.add(new Paragraph().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(6).add(new Text("APPRÉCIATION GÉNÉRALE DU SUPÉRIEUR HIÉRARCHIQUE DIRECT ").setFont(bold).setFontSize(9).setBackgroundColor(label)).add(new Text("(N+1)").setFont(bold).setFontSize(9)));
            Table tn1 = new Table(UnitValue.createPercentArray(new float[]{36, 64})).useAllAvailableWidth();
            for (String[] c : CASES_N1) {
                boolean on = c[0].equals(e.getNiveauAtteinte());
                tn1.addCell(caseCell(b, on, c[1], bold, reg));
                tn1.addCell(new Cell().setBorder(b).setPadding(4).setHeight(24).add(new Paragraph(on ? s(e.getCommentaireEvaluateur()) : "").setFontSize(8).setMargin(0).setMultipliedLeading(1.2f)));
            }
            tn1.addCell(new Cell().setBorder(b).setPadding(4).setHeight(24).add(new Paragraph("Propositions - recommandations").setFontSize(7.5f).setMargin(0).setMarginLeft(14)));
            tn1.addCell(new Cell().setBorder(b).setPadding(4).add(new Paragraph(s(e.getPropositions())).setFontSize(8).setMargin(0)));
            doc.add(tn1);
            if (e.getNiveauAtteinte() == null && !s(e.getCommentaireEvaluateur()).isBlank()) doc.add(new Paragraph(e.getCommentaireEvaluateur()).setFontSize(8).setMargin(0).setMarginTop(4));
            dateSignature(doc, signature(e.getEvaluateur() == null ? "" : e.getEvaluateur().getNomComplet(), e.getDateEvaluation()), bold, ital);
            if (e.getDateVisaN2() != null) doc.add(new Paragraph("Visa du N+2 : " + (s(e.getVisaN2()).isBlank() ? "visé sans observation" : e.getVisaN2()) + " : " + signature(e.getN2() == null ? "" : e.getN2().getNomComplet(), e.getDateVisaN2())).setFont(ital).setFontSize(7.5f).setMargin(0).setMarginBottom(8));

            // Grille
            Table gr = new Table(UnitValue.createPercentArray(new float[]{34, 22, 44})).useAllAvailableWidth().setMarginTop(10).setMarginBottom(14).setKeepTogether(true);
            gr.addCell(new Cell(1, 3).setBorder(b).setPadding(5).add(new Table(1).useAllAvailableWidth().addCell(new Cell().setBorder(Border.NO_BORDER).setBackgroundColor(rgb(CYAN)).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph("GRILLE DE NOTATION DU NIVEAU DE PERFORMANCE").setFont(bold).setFontSize(9).setFontColor(ColorConstants.WHITE).setMargin(0)))));
            for (String h : new String[]{"Niveau de performance", "Note sur 20", "Appréciation"}) gr.addCell(new Cell().setBorder(b).setBackgroundColor(rgb(GREYBLUE)).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(h).setFont(bold).setFontSize(8).setMargin(0)));
            for (String[] r : GRILLE) for (String v : r) gr.addCell(new Cell().setBorder(b).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(v).setFontSize(8).setMargin(0)));
            doc.add(gr);
            if (e.getNoteGlobale() != null) doc.add(new Paragraph("Résultat : taux de réalisation " + e.getScoreObjectifs() + " % - note " + e.getNoteGlobale() + " / 20 : " + e.getAppreciation()).setFont(bold).setFontSize(9).setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(14));

            // Salarié + directeur dans un encadré épais
            Table bloc = new Table(1).useAllAvailableWidth().setKeepTogether(true);
            Cell bs = new Cell().setBorder(new SolidBorder(frame, 2.5f)).setPadding(10);
            bs.add(new Paragraph().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(10).add(new Text("COMMENTAIRES ET PROPOSITIONS DU SALARIE ").setFont(bold).setFontSize(9).setBackgroundColor(label)).add(new Text("(N)").setFont(bold).setFontSize(9)));
            Table tn = new Table(UnitValue.createPercentArray(new float[]{34, 66})).useAllAvailableWidth();
            for (String[] c : CASES_N) {
                boolean on = c[0].equals(e.getSouhaitSalarie());
                tn.addCell(caseCell(b, on, c[1], bold, reg));
                tn.addCell(new Cell().setBorder(b).setPadding(5).setHeight(34).add(new Paragraph(on ? s(e.getCommentaireSalarie()) : "").setFontSize(8.5f).setMargin(0).setMultipliedLeading(1.25f)));
            }
            bs.add(tn);
            if (e.getSouhaitSalarie() == null && !s(e.getCommentaireSalarie()).isBlank()) bs.add(new Paragraph(e.getCommentaireSalarie()).setFontSize(8).setMargin(0).setMarginTop(4));
            bs.add(new Paragraph("……………………………………………………………………………………………………………………………………………………………………………").setFontSize(7).setMargin(0).setMarginTop(6));
            bs.add(new Paragraph("Date et signature").setFont(bold).setFontSize(8).setUnderline().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginTop(4));
            String sgs = signature(e.getSalarie().getNomComplet(), e.getDateSoumission());
            bs.add(new Paragraph(sgs.isBlank() ? " " : sgs).setFont(ital).setFontSize(7.5f).setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(14).setHeight(30));
            bs.add(new com.itextpdf.layout.element.Div().setHeight(1.5f).setBackgroundColor(frame).setMarginBottom(8));
            bs.add(new Paragraph().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(10).add(new Text("AVIS DU DIRECTEUR").setFont(bold).setFontSize(9).setBackgroundColor(label)));
            Table td = new Table(UnitValue.createPercentArray(new float[]{40, 60})).useAllAvailableWidth();
            String[][] dir = {{"Appréciation générale du travail du collaborateur", s(e.getAvisDirecteur())}, {"Demandes à valider", s(e.getDemandesAValider())}, {"Autres propositions", s(e.getAutresPropositions())}};
            for (String[] c : dir) {
                td.addCell(caseCell(b, !c[1].isBlank(), c[0], bold, reg));
                td.addCell(new Cell().setBorder(b).setPadding(5).setHeight(34).add(new Paragraph(c[1]).setFontSize(8.5f).setMargin(0).setMultipliedLeading(1.25f)));
            }
            bs.add(td);
            bs.add(new Paragraph("Date et signature").setFont(bold).setFontSize(8).setUnderline().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginTop(16));
            String sgd = signature(e.getDirecteur() == null ? "" : e.getDirecteur().getNomComplet(), e.getDateAvisDirecteur());
            bs.add(new Paragraph(sgd.isBlank() ? " " : sgd).setFont(ital).setFontSize(7.5f).setTextAlignment(TextAlignment.CENTER).setMargin(0).setHeight(30));
            bloc.addCell(bs);
            doc.add(bloc);
            if (e.isFinalisee()) doc.add(new Paragraph("Fiche validée par la DCHT le " + DT.format(e.getDateValidation()) + (s(e.getCommentaireDcht()).isBlank() ? "" : " : " + e.getCommentaireDcht())).setFont(ital).setFontSize(7.5f).setMarginTop(6));

            doc.close();
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PDF impossible : " + ex.getMessage(), ex); }
    }

    private static void competences(Document doc, Evaluation e, String sec, String titre, String[] niveaux, PdfFont bold, PdfFont reg, Color band, Color grey, SolidBorder b) {
        doc.add(titreSection(titre, bold));
        float[] w = new float[3 + niveaux.length]; w[0] = 12; w[1] = 38; for (int i = 0; i < niveaux.length; i++) w[2 + i] = 6; w[w.length - 1] = 50 - 6 * niveaux.length;
        Table t = new Table(UnitValue.createPercentArray(w)).useAllAvailableWidth().setMarginBottom(8);
        t.addHeaderCell(hc(b, band, 2, 2, "COMPETENCES", bold, 8));
        t.addHeaderCell(hc(b, band, niveaux.length, 1, sec.equals("II") ? "Niveau compétences" : "NOTATION", bold, 7.5f));
        t.addHeaderCell(hc(b, band, 1, 2, "AXES D'AMELIORATION", bold, 8));
        for (String n : niveaux) t.addHeaderCell(hc(b, band, 1, 1, n, bold, 7.5f));
        for (String fam : familles(e, sec)) {
            List<EvaluationCritere> l = e.getCriteres().stream().filter(c -> c.getCritere().getFamille().equals(fam)).toList();
            boolean first = true;
            for (EvaluationCritere c : l) {
                if (first) { t.addCell(new Cell(l.size(), 1).setBorder(b).setBackgroundColor(grey).setPadding(3).setVerticalAlignment(VerticalAlignment.MIDDLE).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(familleLabel(e, fam)).setFont(bold).setFontSize(7).setMargin(0))); first = false; }
                t.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph(c.getCritere().getLibelle()).setFontSize(8).setMargin(0)));
                for (String n : niveaux) t.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(n.equals(c.getNiveau()) ? "X" : (n.equals(c.getAutoNiveau()) ? "(x)" : "")).setFont(n.equals(c.getNiveau()) ? bold : reg).setFontSize(n.equals(c.getNiveau()) ? 8 : 6.5f).setMargin(0)));
                t.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph(s(c.getAxesAmelioration())).setFontSize(7).setMargin(0)));
            }
        }
        doc.add(t);
    }
    private static Paragraph titreSection(String t, PdfFont bold) { return new Paragraph(t).setFont(bold).setFontSize(9).setUnderline().setMargin(0).setMarginTop(6).setMarginBottom(4).setMarginLeft(26); }
    private static Cell encadre(String k, String v, PdfFont bold, Color label, Color black) {
        Cell c = new Cell().setBorder(Border.NO_BORDER).setPadding(3);
        Table in = new Table(1).useAllAvailableWidth();
        in.addCell(new Cell().setBorder(new SolidBorder(black, 1f)).setBorderRadius(new BorderRadius(6)).setPadding(5).setPaddingLeft(8).setHeight(22).setVerticalAlignment(VerticalAlignment.MIDDLE)
                .add(new Paragraph().setMargin(0).add(new Text(k).setFont(bold).setFontSize(7.5f).setBackgroundColor(label)).add(new Text(" : " + v).setFont(bold).setFontSize(7.5f))));
        c.add(in);
        return c;
    }
    private static Cell caseCell(SolidBorder b, boolean on, String libelle, PdfFont bold, PdfFont reg) {
        Cell c = new Cell().setBorder(b).setPadding(5);
        Paragraph p = new Paragraph().setMargin(0).setFontSize(7.5f);
        p.add(new Text(on ? "■" : "□").setFont(reg).setFontSize(10)).add(new Text("      " + libelle).setFont(on ? bold : reg));
        return c.add(p);
    }
    private static void dateSignature(Document doc, String sig, PdfFont bold, PdfFont ital) {
        doc.add(new Paragraph("……………………………………………………………………………………………………………………………………………………………………………………").setFontSize(7).setMargin(0).setMarginTop(6));
        doc.add(new Paragraph("Date et signature").setFont(bold).setFontSize(8).setUnderline().setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginTop(4));
        doc.add(new Paragraph(sig.isBlank() ? " " : sig).setFont(ital).setFontSize(7.5f).setTextAlignment(TextAlignment.CENTER).setMargin(0).setMarginBottom(6).setHeight(22));
    }
    private static Cell hc(SolidBorder b, Color bg, int colspan, int rowspan, String txt, PdfFont f, float size) {
        return new Cell(rowspan, colspan).setBorder(b).setBackgroundColor(bg).setPadding(4).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(txt).setFont(f).setFontSize(size).setMargin(0));
    }
    private static Cell nb() { return new Cell().setBorder(Border.NO_BORDER).setPadding(0); }
    private static Color rgb(String hex) { return new DeviceRgb(Integer.valueOf(hex.substring(0, 2), 16), Integer.valueOf(hex.substring(2, 4), 16), Integer.valueOf(hex.substring(4, 6), 16)); }

    /** Cadre de page + pied « MAF 159 DCHT 02 … » et numéro de page. */
    private static final class PageDecor implements IEventHandler {
        private final PdfFont bold, reg; private final Color frame;
        PageDecor(PdfFont bold, PdfFont reg, Color frame) { this.bold = bold; this.reg = reg; this.frame = frame; }
        @Override public void handleEvent(Event event) {
            PdfDocumentEvent ev = (PdfDocumentEvent) event; PdfPage page = ev.getPage(); PdfDocument pdf = ev.getDocument(); Rectangle ps = page.getPageSize();
            PdfCanvas pc = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdf);
            pc.saveState().setStrokeColor(frame).setLineWidth(0.8f).rectangle(ps.getLeft() + 18, ps.getBottom() + 18, ps.getWidth() - 36, ps.getHeight() - 36).stroke().restoreState();
            try (Canvas cv = new Canvas(pc, ps)) {
                cv.showTextAligned(new Paragraph(PIED).setFont(bold).setFontSize(8).setFontColor(ColorConstants.BLACK), ps.getLeft() + 60, ps.getBottom() + 34, TextAlignment.LEFT);
                cv.showTextAligned(new Paragraph(String.valueOf(pdf.getPageNumber(page))).setFont(reg).setFontSize(10).setFontColor(ColorConstants.BLACK), ps.getRight() - 60, ps.getBottom() + 34, TextAlignment.RIGHT);
            }
        }
    }

    // Word
    public byte[] docx(Evaluation e) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
            CTPageSz sz = sect.addNewPgSz(); sz.setW(BigInteger.valueOf(11906)); sz.setH(BigInteger.valueOf(16838));
            CTPageMar mar = sect.addNewPgMar(); mar.setLeft(BigInteger.valueOf(800)); mar.setRight(BigInteger.valueOf(800)); mar.setTop(BigInteger.valueOf(700)); mar.setBottom(BigInteger.valueOf(700)); mar.setFooter(BigInteger.valueOf(400));
            CTPageBorders pb = sect.addNewPgBorders(); pb.setOffsetFrom(STPageBorderOffset.PAGE);
            for (CTBorder x : new CTBorder[]{pb.addNewTop(), pb.addNewBottom(), pb.addNewLeft(), pb.addNewRight()}) { x.setVal(STBorder.SINGLE); x.setSz(BigInteger.valueOf(8)); x.setSpace(BigInteger.valueOf(24)); x.setColor(FRAME); }
            int W = 10306;
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph fp = footer.createParagraph(); run(fp, PIED, true, 8, BLACK); run(fp, "\t\t\t", false, 8, BLACK);
            CTSimpleField fld = fp.getCTP().addNewFldSimple(); fld.setInstr(" PAGE "); CTR fr = fld.addNewR(); fr.addNewRPr().addNewSz().setVal(BigInteger.valueOf(20)); fr.addNewT().setStringValue("1");

            // En-tête
            XWPFTable head = doc.createTable(1, 2); noBorders(head); widths(head, 3700, 6606);
            XWPFTableCell lc = head.getRow(0).getCell(0); byte[] lg = logo();
            if (lg != null) lc.getParagraphs().get(0).createRun().addPicture(new java.io.ByteArrayInputStream(lg), XWPFDocument.PICTURE_TYPE_PNG, "logo.png", Units.toEMU(120), Units.toEMU(40)); else run(lc.getParagraphs().get(0), "BGFIBank", true, 16, NAVY);
            run(lc.addParagraph(), "Direction", false, 7.5, NAVY); run(lc.addParagraph(), "du Capital Humain et de la Transformation", false, 7.5, NAVY);
            XWPFTableCell cart = head.getRow(0).getCell(1); cart.setColor(CARTOUCHE); borders(cart, 8, GREYBLUE); cart.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            XWPFParagraph c1 = cart.getParagraphs().get(0); c1.setAlignment(ParagraphAlignment.CENTER); c1.setSpacingBefore(120); run(c1, "FICHE D'EVALUATION INDIVIDUELLE", true, 13, NAVY);
            XWPFParagraph c2 = cart.addParagraph(); c2.setAlignment(ParagraphAlignment.CENTER); XWPFRun r2 = run(c2, "«  Collaborateurs BGFIBank RDC SA  »", true, 9.5, NAVY); r2.setItalic(true);
            XWPFParagraph c3 = cart.addParagraph(); c3.setAlignment(ParagraphAlignment.CENTER); c3.setSpacingAfter(120); XWPFRun r3 = run(c3, "Evaluation " + mp(e) + " " + e.getCampagne().getExercice(), true, 8.5, NAVY); r3.setItalic(true);
            spacer(doc, 120);

            // Encadrés
            String[][] rs = renseignements(e);
            XWPFTable id = doc.createTable(4, 2); noBorders(id); widths(id, 6600, 3706);
            for (int i = 0; i < 6; i++) encadreW(id.getRow(i / 2).getCell(i % 2), rs[i][0], rs[i][1]);
            mergeRange(id.getRow(3), 0, 1); encadreW(id.getRow(3).getCell(0), rs[6][0], rs[6][1]);
            spacer(doc, 80);

            // I
            titreW(doc, "I- APPRECIATION DE LA PERFORMANCE REALISEE " + mpLong(e) + " " + e.getCampagne().getExercice() + " (SUIVANT LES OBJECTIFS FIXES)");
            int[] w1 = {3600, 2060, 820, 820, 3006};
            XWPFTable t = doc.createTable(2, 5); thinBorders(t, FRAME); widths(t, w1);
            enteteW(t, new String[]{"OBJECTIFS", "INDICATEURS DE PERFORMANCE", "POURCENTAGE D'ATTEINTE " + (Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FIN D'ANNEE" : "MI-PARCOURS"), "COMMENTAIRES"});
            int ai = 1, num = 1;
            for (String act : activites(e.getObjectifs())) {
                if (!act.isBlank()) { XWPFTableRow ar = t.createRow(); for (XWPFTableCell c : ar.getTableCells()) c.setColor(GREY); run(ar.getCell(0).getParagraphs().get(0), "Activité " + ai++ + " :  " + act, true, 8, NAVY); }
                for (ObjectifEvaluation o : e.getObjectifs()) {
                    if (!s(o.getObjectif().getActivite()).trim().equals(act)) continue;
                    XWPFTableRow r = t.createRow();
                    XWPFParagraph p0 = r.getCell(0).getParagraphs().get(0); p0.setIndentationLeft(200); run(p0, num++ + ".  " + s(o.getObjectif().getLibelle()), false, 8, NAVY); vcenter(r.getCell(0));
                    vcenter(r.getCell(1)); run(r.getCell(1).getParagraphs().get(0), s(o.getObjectif().getIndicateur()) + (s(o.getObjectif().getCible()).isBlank() ? "" : " (cible " + o.getObjectif().getCible() + ")"), false, 7.5, NAVY);
                    vcenter(r.getCell(2)); centerRun(r.getCell(2), o.getAttendu() + "%", false, 8, NAVY);
                    vcenter(r.getCell(3)); centerRun(r.getCell(3), pct(o.getRealisationN1() != null ? o.getRealisationN1() : o.getRealisationSalarie()), false, 8, NAVY);
                    vcenter(r.getCell(4)); XWPFParagraph pc = r.getCell(4).getParagraphs().get(0);
                    if (!s(o.getCommentaireRetenu()).isBlank()) run(pc, o.getCommentaireRetenu(), false, 6.5, NAVY);
                }
            }
            spacer(doc, 160);

            // II
            titreW(doc, "II- APPRECIATION DES TRAVAUX REALISES EN " + mpLong(e) + " " + e.getCampagne().getExercice() + " (EN DEHORS DES OBJECTIFS ASSIGNES)");
            XWPFParagraph fac = doc.createParagraph(); fac.setAlignment(ParagraphAlignment.CENTER); XWPFRun fr2 = run(fac, "(Facultatif)", false, 8, NAVY); fr2.setItalic(true);
            XWPFTable tv = doc.createTable(2, 5); thinBorders(tv, FRAME); widths(tv, 3100, 2560, 820, 820, 3006);
            enteteW(tv, new String[]{"TYPE DE TRAVAUX", "INDICATEURS DE PERFORMANCE", "POURCENTAGE D'ATTEINTE " + (Campagne.FIN_ANNEE.equals(e.getCampagne().getType()) ? "FIN D'ANNEE" : "MI-PARCOURS"), "COMMENTAIRES"});
            List<String> tacts = new ArrayList<>(); for (TravailHorsObjectif x : e.getTravauxHorsObjectifs()) { String k = s(x.getActivite()).trim(); if (!tacts.contains(k)) tacts.add(k); }
            if (tacts.isEmpty()) tacts.add("Missions professionnels");
            int ta = 1;
            for (String act : tacts) {
                XWPFTableRow ar = tv.createRow(); for (XWPFTableCell c : ar.getTableCells()) c.setColor(GREY); run(ar.getCell(0).getParagraphs().get(0), "Activité " + ta++ + " : " + act, true, 8, NAVY);
                int n = 1;
                for (TravailHorsObjectif x : e.getTravauxHorsObjectifs()) {
                    if (!s(x.getActivite()).trim().equals(act)) continue;
                    XWPFTableRow r = tv.createRow();
                    run(r.getCell(0).getParagraphs().get(0), n++ + "-  " + x.getLibelle(), false, 8, NAVY); run(r.getCell(1).getParagraphs().get(0), s(x.getIndicateur()), false, 7.5, NAVY);
                    centerRun(r.getCell(2), x.getAttendu() + "%", false, 8, NAVY); centerRun(r.getCell(3), pct(x.getRealise()), false, 8, NAVY); run(r.getCell(4).getParagraphs().get(0), s(x.getCommentaire()), false, 6.5, NAVY);
                }
                for (; n <= 3; n++) { XWPFTableRow r = tv.createRow(); r.setHeight(340); run(r.getCell(0).getParagraphs().get(0), n + "-", false, 7.5, NAVY); }
            }
            spacer(doc, 200);

            if (e.isAvecCompetences()) {
                competencesW(doc, e, "II", "EVALUATION DES COMPETENCES", new String[]{"A", "EA", "NA", "NM"});
                competencesW(doc, e, "III", "EVALUATION DE L'ADHESION AUX VALEURS DU GROUPE", new String[]{"A", "EA", "NA"});
                XWPFParagraph leg = doc.createParagraph(); XWPFRun lr = run(leg, "A = acquis · EA = en cours d'acquisition · NA = non acquis · NM = non mesuré", false, 6.5, NAVY); lr.setItalic(true);
                titreW(doc, "SOUHAITS DE FORMATION");
                XWPFTable tf = doc.createTable(4, 3); thinBorders(tf, FRAME); widths(tf, 2500, 3700, 4106);
                String[] fh = {"Origine de la demande", "Formations demandées", "Motivation"};
                for (int k = 0; k < 3; k++) { tf.getRow(0).getCell(k).setColor(BAND); centerRun(tf.getRow(0).getCell(k), fh[k], true, 8, NAVY); }
                String[][] orgs = {{"EVALUATEUR", "EVALUATEUR"}, {"EVALUE", "EVALUE"}, {"DIRECTEUR", "DIRECTEUR"}};
                for (int i = 0; i < 3; i++) {
                    XWPFTableRow r = tf.getRow(1 + i); r.setHeight(500); vcenter(r.getCell(0)); run(r.getCell(0).getParagraphs().get(0), orgs[i][1], true, 8, NAVY);
                    boolean first = true;
                    final String orgCode = orgs[i][0];
                    for (BesoinFormation bf : e.getBesoinsFormation().stream().filter(x -> orgCode.equals(x.getOrigine())).toList()) {
                        run(first ? r.getCell(1).getParagraphs().get(0) : r.getCell(1).addParagraph(), "• " + bf.getIntitule(), false, 7.5, NAVY);
                        run(first ? r.getCell(2).getParagraphs().get(0) : r.getCell(2).addParagraph(), s(bf.getJustification()), false, 7.5, NAVY); first = false;
                    }
                }
                spacer(doc, 160);
            }

            // III
            XWPFParagraph t3 = doc.createParagraph(); t3.setSpacingBefore(120); t3.setSpacingAfter(120); XWPFRun r31 = run(t3, "III. APPRECIATIONS ET COMMENTAIRES", true, 9, NAVY); r31.setItalic(true); r31.setUnderline(UnderlinePatterns.SINGLE);
            titreCentre(doc, "APPRÉCIATION GÉNÉRALE DU SUPÉRIEUR HIÉRARCHIQUE DIRECT ", "(N+1)");
            XWPFTable tn1 = doc.createTable(5, 2); thinBorders(tn1, FRAME); widths(tn1, 3700, 6606);
            for (int i = 0; i < 4; i++) { boolean on = CASES_N1[i][0].equals(e.getNiveauAtteinte()); XWPFTableRow r = tn1.getRow(i); r.setHeight(560); caseW(r.getCell(0), on, CASES_N1[i][1]); run(r.getCell(1).getParagraphs().get(0), on ? s(e.getCommentaireEvaluateur()) : "", false, 8, NAVY); }
            XWPFTableRow pr = tn1.getRow(4); pr.setHeight(560); XWPFParagraph pp = pr.getCell(0).getParagraphs().get(0); pp.setIndentationLeft(600); run(pp, "Propositions - recommandations", false, 7.5, NAVY); run(pr.getCell(1).getParagraphs().get(0), s(e.getPropositions()), false, 8, NAVY);
            if (e.getNiveauAtteinte() == null && !s(e.getCommentaireEvaluateur()).isBlank()) run(doc.createParagraph(), e.getCommentaireEvaluateur(), false, 8, NAVY);
            dateSignatureW(doc, signature(e.getEvaluateur() == null ? "" : e.getEvaluateur().getNomComplet(), e.getDateEvaluation()));
            if (e.getDateVisaN2() != null) { XWPFRun v = run(doc.createParagraph(), "Visa du N+2 : " + (s(e.getVisaN2()).isBlank() ? "visé sans observation" : e.getVisaN2()) + " : " + signature(e.getN2() == null ? "" : e.getN2().getNomComplet(), e.getDateVisaN2()), false, 7.5, NAVY); v.setItalic(true); }
            spacer(doc, 160);

            // Grille
            XWPFTable gr = doc.createTable(7, 3); thinBorders(gr, FRAME); widths(gr, 3500, 2300, 4506);
            XWPFTableRow g0 = gr.getRow(0); mergeRange(g0, 0, 2); g0.getCell(0).setColor(CYAN); centerRun(g0.getCell(0), "GRILLE DE NOTATION DU NIVEAU DE PERFORMANCE", true, 9, "FFFFFF");
            String[] gh = {"Niveau de performance", "Note sur 20", "Appréciation"};
            for (int k = 0; k < 3; k++) { gr.getRow(1).getCell(k).setColor(GREYBLUE); centerRun(gr.getRow(1).getCell(k), gh[k], true, 8, NAVY); }
            for (int i = 0; i < GRILLE.length; i++) for (int k = 0; k < 3; k++) centerRun(gr.getRow(2 + i).getCell(k), GRILLE[i][k], false, 8, NAVY);
            if (e.getNoteGlobale() != null) { XWPFParagraph np = doc.createParagraph(); np.setAlignment(ParagraphAlignment.CENTER); np.setSpacingBefore(100); run(np, "Résultat : taux de réalisation " + e.getScoreObjectifs() + " % - note " + e.getNoteGlobale() + " / 20 : " + e.getAppreciation(), true, 9, NAVY); }
            spacer(doc, 240);

            // Salarié + directeur (encadré épais), tableau plat 2 colonnes, sans tableau imbriqué (POI insertNewTbl est instable).
            XWPFTable bloc = doc.createTable(11, 2); thinBorders(bloc, FRAME); widths(bloc, 3600, W - 3600);
            bloc.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 18, 0, FRAME); bloc.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 18, 0, FRAME); bloc.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 18, 0, FRAME); bloc.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 18, 0, FRAME);
            // En-tête salarié
            XWPFTableRow b0 = bloc.getRow(0); mergeRange(b0, 0, 1);
            XWPFParagraph bt = b0.getCell(0).getParagraphs().get(0); bt.setAlignment(ParagraphAlignment.CENTER); bt.setSpacingBefore(120); bt.setSpacingAfter(120);
            XWPFRun bt1 = run(bt, "COMMENTAIRES ET PROPOSITIONS DU SALARIE ", true, 9, NAVY); shade(bt1, LABEL); run(bt, "(N)", true, 9, NAVY);
            // Souhaits cochés + commentaire en regard
            for (int i = 0; i < 4; i++) {
                XWPFTableRow r = bloc.getRow(1 + i); r.setHeight(700);
                boolean on = CASES_N[i][0].equals(e.getSouhaitSalarie());
                caseW(r.getCell(0), on, CASES_N[i][1]);
                vcenter(r.getCell(1)); run(r.getCell(1).getParagraphs().get(0), on ? s(e.getCommentaireSalarie()) : "", false, 8.5, NAVY);
            }
            // Signature du salarié
            XWPFTableRow b5 = bloc.getRow(5); mergeRange(b5, 0, 1); XWPFTableCell c5 = b5.getCell(0);
            XWPFParagraph p5 = c5.getParagraphs().get(0); p5.setSpacingBefore(80);
            if (e.getSouhaitSalarie() == null && !s(e.getCommentaireSalarie()).isBlank()) { run(p5, e.getCommentaireSalarie(), false, 8, NAVY); p5 = c5.addParagraph(); }
            run(p5, "……………………………………………………………………………………………………………………………………………………………………………", false, 7, NAVY);
            XWPFParagraph ds = c5.addParagraph(); ds.setAlignment(ParagraphAlignment.CENTER); XWPFRun dsr = run(ds, "Date et signature", true, 8, NAVY); dsr.setUnderline(UnderlinePatterns.SINGLE);
            XWPFParagraph sgp = c5.addParagraph(); sgp.setAlignment(ParagraphAlignment.CENTER); sgp.setSpacingAfter(200); XWPFRun sgr = run(sgp, signature(e.getSalarie().getNomComplet(), e.getDateSoumission()), false, 7.5, NAVY); sgr.setItalic(true);
            // En-tête directeur
            XWPFTableRow b6 = bloc.getRow(6); mergeRange(b6, 0, 1);
            XWPFParagraph dt = b6.getCell(0).getParagraphs().get(0); dt.setAlignment(ParagraphAlignment.CENTER); dt.setSpacingBefore(120); dt.setSpacingAfter(120);
            XWPFRun dtr = run(dt, "AVIS DU DIRECTEUR", true, 9, NAVY); shade(dtr, LABEL);
            String[][] dir = {{"Appréciation générale du travail du collaborateur", s(e.getAvisDirecteur())}, {"Demandes à valider", s(e.getDemandesAValider())}, {"Autres propositions", s(e.getAutresPropositions())}};
            for (int i = 0; i < 3; i++) {
                XWPFTableRow r = bloc.getRow(7 + i); r.setHeight(800);
                caseW(r.getCell(0), !dir[i][1].isBlank(), dir[i][0]);
                vcenter(r.getCell(1)); run(r.getCell(1).getParagraphs().get(0), dir[i][1], false, 8.5, NAVY);
            }
            // Signature du directeur
            XWPFTableRow b10 = bloc.getRow(10); mergeRange(b10, 0, 1); XWPFTableCell c10 = b10.getCell(0);
            XWPFParagraph ds2 = c10.getParagraphs().get(0); ds2.setAlignment(ParagraphAlignment.CENTER); ds2.setSpacingBefore(200); XWPFRun ds2r = run(ds2, "Date et signature", true, 8, NAVY); ds2r.setUnderline(UnderlinePatterns.SINGLE);
            XWPFParagraph sgd = c10.addParagraph(); sgd.setAlignment(ParagraphAlignment.CENTER); sgd.setSpacingAfter(200); XWPFRun sgdr = run(sgd, signature(e.getDirecteur() == null ? "" : e.getDirecteur().getNomComplet(), e.getDateAvisDirecteur()), false, 7.5, NAVY); sgdr.setItalic(true);
            if (e.isFinalisee()) { XWPFRun vr = run(doc.createParagraph(), "Fiche validée par la DCHT le " + DT.format(e.getDateValidation()) + (s(e.getCommentaireDcht()).isBlank() ? "" : " : " + e.getCommentaireDcht()), false, 7.5, NAVY); vr.setItalic(true); }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export Word impossible : " + ex.getMessage(), ex); }
    }

    private static void enteteW(XWPFTable t, String[] h) {
        XWPFTableRow h0 = t.getRow(0), h1 = t.getRow(1);
        for (XWPFTableRow r : new XWPFTableRow[]{h0, h1}) { r.setRepeatHeader(true); for (XWPFTableCell c : r.getTableCells()) { c.setColor(BAND); vcenter(c); } }
        vmerge(h0.getCell(0), true); vmerge(h1.getCell(0), false); centerRun(h0.getCell(0), h[0], true, 8, NAVY);
        vmerge(h0.getCell(1), true); vmerge(h1.getCell(1), false); centerRun(h0.getCell(1), h[1], true, 8, NAVY);
        mergeRange(h0, 2, 3); centerRun(h0.getCell(2), h[2], true, 7.5, NAVY);
        centerRun(h1.getCell(2), "Attendu", false, 7.5, NAVY); centerRun(h1.getCell(3), "Réalisé", false, 7.5, NAVY);
        vmerge(h0.getCell(4), true); vmerge(h1.getCell(4), false); centerRun(h0.getCell(4), h[3], true, 8, NAVY);
    }
    private static void competencesW(XWPFDocument doc, Evaluation e, String sec, String titre, String[] niveaux) {
        titreW(doc, titre);
        int n = niveaux.length; int[] w = new int[3 + n]; w[0] = 1300; w[1] = 3800; for (int i = 0; i < n; i++) w[2 + i] = 560; w[w.length - 1] = 10306 - 1300 - 3800 - 560 * n;
        XWPFTable t = doc.createTable(2, 3 + n); thinBorders(t, FRAME); widths(t, w);
        XWPFTableRow h0 = t.getRow(0), h1 = t.getRow(1);
        for (XWPFTableRow r : new XWPFTableRow[]{h0, h1}) for (XWPFTableCell c : r.getTableCells()) { c.setColor(BAND); vcenter(c); }
        mergeRange(h0, 0, 1); mergeRange(h1, 0, 1); vmerge(h0.getCell(0), true); vmerge(h1.getCell(0), false); centerRun(h0.getCell(0), "COMPETENCES", true, 8, NAVY);
        mergeRange(h0, 2, 1 + n); centerRun(h0.getCell(2), sec.equals("II") ? "Niveau compétences" : "NOTATION", true, 7.5, NAVY);
        for (int i = 0; i < n; i++) centerRun(h1.getCell(2 + i), niveaux[i], true, 7.5, NAVY);
        vmerge(h0.getCell(2 + n), true); vmerge(h1.getCell(2 + n), false); centerRun(h0.getCell(2 + n), "AXES D'AMELIORATION", true, 8, NAVY);
        for (String fam : familles(e, sec)) {
            List<EvaluationCritere> l = e.getCriteres().stream().filter(c -> c.getCritere().getFamille().equals(fam)).toList(); boolean first = true;
            for (EvaluationCritere c : l) {
                XWPFTableRow r = t.createRow(); r.getCell(0).setColor(GREY); vmerge(r.getCell(0), first); vcenter(r.getCell(0));
                if (first) centerRun(r.getCell(0), familleLabel(e, fam), true, 7, NAVY); first = false;
                run(r.getCell(1).getParagraphs().get(0), c.getCritere().getLibelle(), false, 8, NAVY);
                for (int i = 0; i < n; i++) { boolean on = niveaux[i].equals(c.getNiveau()); centerRun(r.getCell(2 + i), on ? "X" : (niveaux[i].equals(c.getAutoNiveau()) ? "(x)" : ""), on, on ? 8 : 6.5, NAVY); }
                run(r.getCell(2 + n).getParagraphs().get(0), s(c.getAxesAmelioration()), false, 7, NAVY);
            }
        }
        spacer(doc, 120);
    }
    private static void titreW(XWPFDocument doc, String t) { XWPFParagraph p = doc.createParagraph(); p.setIndentationLeft(500); p.setSpacingBefore(120); p.setSpacingAfter(60); XWPFRun r = run(p, t, true, 9, NAVY); r.setUnderline(UnderlinePatterns.SINGLE); }
    private static void titreCentre(XWPFDocument doc, String surligne, String suite) { XWPFParagraph p = doc.createParagraph(); p.setAlignment(ParagraphAlignment.CENTER); p.setSpacingAfter(160); XWPFRun a = run(p, surligne, true, 9, NAVY); shade(a, LABEL); if (!suite.isEmpty()) run(p, suite, true, 9, NAVY); }
    private static void shade(XWPFRun r, String color) { CTRPr pr = r.getCTR().isSetRPr() ? r.getCTR().getRPr() : r.getCTR().addNewRPr(); CTShd sh = pr.addNewShd(); sh.setVal(STShd.CLEAR); sh.setFill(color); }
    private static void encadreW(XWPFTableCell c, String k, String v) {
        borders(c, 8, BLACK); c.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        CTTcPr pr = c.getCTTc().getTcPr(); CTTcMar m = pr.isSetTcMar() ? pr.getTcMar() : pr.addNewTcMar(); CTTblWidth lm = m.addNewLeft(); lm.setType(STTblWidth.DXA); lm.setW(BigInteger.valueOf(140));
        XWPFParagraph p = c.getParagraphs().get(0); p.setSpacingBefore(60); p.setSpacingAfter(60);
        XWPFRun a = run(p, k, true, 7.5, NAVY); shade(a, LABEL); run(p, " : " + v, true, 7.5, NAVY);
    }
    private static void caseW(XWPFTableCell c, boolean on, String libelle) { vcenter(c); XWPFParagraph p = c.getParagraphs().get(0); p.setIndentationLeft(120); run(p, (on ? "■" : "□") + "      ", false, 10, NAVY); run(p, libelle, on, 7.5, NAVY); }
    private static void dateSignatureW(XWPFDocument doc, String sig) {
        XWPFParagraph d = doc.createParagraph(); d.setSpacingBefore(80); run(d, "……………………………………………………………………………………………………………………………………………………………………………………", false, 7, NAVY);
        XWPFParagraph p = doc.createParagraph(); p.setAlignment(ParagraphAlignment.CENTER); XWPFRun r = run(p, "Date et signature", true, 8, NAVY); r.setUnderline(UnderlinePatterns.SINGLE);
        XWPFParagraph sp = doc.createParagraph(); sp.setAlignment(ParagraphAlignment.CENTER); sp.setSpacingAfter(160); XWPFRun sr = run(sp, sig.isBlank() ? " " : sig, false, 7.5, NAVY); sr.setItalic(true);
    }
    private static XWPFRun run(XWPFParagraph p, String text, boolean bold, double size, String color) {
        if (p.getSpacingAfter() <= 0) p.setSpacingAfter(0);
        XWPFRun r = p.createRun(); r.setText(text); r.setBold(bold); r.setFontSize(size); r.setColor(color); r.setFontFamily("Arial"); return r;
    }
    private static void centerRun(XWPFTableCell c, String text, boolean bold, double size, String color) { XWPFParagraph p = c.getParagraphs().get(0); p.setAlignment(ParagraphAlignment.CENTER); run(p, text, bold, size, color); }
    private static void vcenter(XWPFTableCell c) { c.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER); }
    private static void spacer(XWPFDocument doc, int twips) { XWPFParagraph p = doc.createParagraph(); p.setSpacingAfter(twips); p.setSpacingBefore(0); }
    private static void widths(XWPFTable t, int... w) {
        // Les tableaux imbriqués (insertNewTbl) n'ont ni tblPr ni tblW : on les crée avant de fixer la largeur à 100 %.
        CTTblPr pr = t.getCTTbl().getTblPr() == null ? t.getCTTbl().addNewTblPr() : t.getCTTbl().getTblPr();
        CTTblWidth tw = pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW();
        tw.setType(STTblWidth.PCT); tw.setW(BigInteger.valueOf(5000));
        CTTblGrid grid = t.getCTTbl().getTblGrid() == null ? t.getCTTbl().addNewTblGrid() : t.getCTTbl().getTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int x : w) grid.addNewGridCol().setW(BigInteger.valueOf(x));
        // Largeur de chaque cellule fixée au niveau XML (w:tcW) : les objets XWPFTableRow d'un tableau imbriqué peuvent être déconnectés.
        for (CTRow tr : t.getCTTbl().getTrList()) {
            List<CTTc> tcs = tr.getTcList();
            for (int i = 0; i < tcs.size() && i < w.length; i++) {
                CTTcPr tcPr = tcs.get(i).isSetTcPr() ? tcs.get(i).getTcPr() : tcs.get(i).addNewTcPr();
                CTTblWidth cw = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                cw.setType(STTblWidth.DXA); cw.setW(BigInteger.valueOf(w[i]));
            }
        }
    }
    private static void noBorders(XWPFTable t) {
        t.setTopBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setBottomBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setLeftBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setRightBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setInsideHBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setInsideVBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
    }
    private static void thinBorders(XWPFTable t, String color) {
        t.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color);
        t.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color);
    }
    private static void borders(XWPFTableCell c, int sz, String color) {
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        CTTcBorders b = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
        for (CTBorder x : new CTBorder[]{b.addNewTop(), b.addNewBottom(), b.addNewLeft(), b.addNewRight()}) { x.setVal(STBorder.SINGLE); x.setSz(BigInteger.valueOf(sz)); x.setColor(color); }
    }
    private static void mergeRange(XWPFTableRow r, int from, int to) {
        for (int i = from; i <= to; i++) {
            while (r.getTableCells().size() <= i) r.addNewTableCell();
            CTTcPr pr = r.getCell(i).getCTTc().isSetTcPr() ? r.getCell(i).getCTTc().getTcPr() : r.getCell(i).getCTTc().addNewTcPr();
            CTHMerge hm = pr.isSetHMerge() ? pr.getHMerge() : pr.addNewHMerge(); hm.setVal(i == from ? STMerge.RESTART : STMerge.CONTINUE);
        }
    }
    private static void vmerge(XWPFTableCell c, boolean restart) {
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        CTVMerge vm = pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge(); vm.setVal(restart ? STMerge.RESTART : STMerge.CONTINUE);
    }
}
