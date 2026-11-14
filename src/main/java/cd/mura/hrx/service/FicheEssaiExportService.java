package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.EvaluationEssai;
import cd.mura.hrx.entity.eval.EvaluationEssaiCritere;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static cd.mura.hrx.service.FicheObjectifsExportService.*;

/** Export de la fiche MAF 070 DRH 02 « Fiche d'évaluation individuelle (personnel en CDD ou en période d'essai) ». */
@ApplicationScoped
public class FicheEssaiExportService implements Serializable {

    private static final String NAVY = "17365D", HEAD = "D9D9D9", BAND = "DBE5F1", FRAME = "808080", BLACK = "000000";
    private static final String PIED = "MAF 070 DRH 02 / FICHE D'EVALUATION INDIVIDUELLE (PERSONNEL EN CDD OU PERIODE D'ESSAI) / AVRIL.18";
    private static final String DIRECTION = "Département des Ressources Humaines";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] NIVEAUX = {"TB", "B", "P", "I", "PC"};
    private static final String[] NIVEAUX_ANCIENS = {"A", "EA", "NA", "NM"};

    /** Texte sûr pour les polices standard du PDF (≥ et ≤ absents). */
    private static String s(String v) { return v == null ? "" : v.replace("≥", ">=").replace("≤", "<="); }
    private static String d(LocalDate x) { return x == null ? "" : x.format(D); }
    private static String dh(Date x) { return x == null ? "" : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(x); }
    private static String nom(Users u) { return u == null ? "" : u.getNomComplet(); }
    private static String[] niveaux(EvaluationEssai e) { return e.isMaf070() ? NIVEAUX : NIVEAUX_ANCIENS; }

    /** Cases « Type de contrat » de l'en-tête. */
    private static String[][] cases(EvaluationEssai e) {
        String t = e.getTypeEvaluation() == null ? EvaluationEssai.FIN_ESSAI_CDI : e.getTypeEvaluation();
        return new String[][]{{t.equals(EvaluationEssai.FIN_ESSAI_CDD) ? "X" : "", "CDD Fin Période d'essai"},
                {t.equals(EvaluationEssai.FIN_CDD) ? "X" : "", "CDD Fin de contrat"},
                {t.equals(EvaluationEssai.FIN_ESSAI_CDI) ? "X" : "", "CDI Fin Période d'essai"}};
    }
    /** Cases « Décision » de la section III, selon le type de contrat. */
    private static String[][] decisions(EvaluationEssai e) {
        List<String[]> l = new ArrayList<>();
        for (String code : e.isFinCdd() ? List.of(EvaluationEssai.RENOUVELER, EvaluationEssai.FIN_CONTRAT)
                : List.of(EvaluationEssai.CONFIRMER_CDD, EvaluationEssai.FIN_CONTRAT, EvaluationEssai.CONFIRMER_CDI))
            l.add(new String[]{code.equals(e.getDecision()) ? "X" : "", EvaluationEssai.decisionLabel(code)});
        if (e.getDecision() != null && l.stream().noneMatch(x -> "X".equals(x[0]))) l.add(new String[]{"X", e.getDecisionLabel()});
        return l.toArray(new String[0][]);
    }
    /** Lignes « libellé : valeur » de la section III. */
    private static String[][] contrat(EvaluationEssai e) {
        if (e.isFinCdd()) return new String[][]{{"Date de début du contrat : ", d(e.getDateDebutContrat())}, {"Echéance du présent contrat : ", d(e.getDateFinContrat())},
                {"Date limite légale du contrat : ", d(e.getDateLimiteContrat())}, {"Nombre de prorogations effectuées : ", e.getNbProrogations() == null ? "0" : String.valueOf(e.getNbProrogations())}};
        return new String[][]{{"Date de début du contrat : ", d(e.getDateDebutContrat())}, {"Date de fin de période d'essai : ", d(e.getDateFinEssai())}};
    }
    private static String signN1(EvaluationEssai e) { return e.getDateTransmission() == null ? "" : "Signé électroniquement le " + dh(e.getDateTransmission()) + "\n" + nom(e.getEvaluateur()); }
    private static String signSalarie(EvaluationEssai e) { return e.getDateSignatureSalarie() == null ? "" : "Signé électroniquement le " + dh(e.getDateSignatureSalarie()) + "\n" + nom(e.getSalarie()); }
    private static String signDirecteur(EvaluationEssai e) { return e.getDateAvisDirecteur() == null ? "" : "Signé électroniquement le " + dh(e.getDateAvisDirecteur()) + "\n" + nom(e.getDirecteur()); }
    private static String suiteDecision(EvaluationEssai e) {
        String x = "";
        if (e.getNouvelleEcheance() != null) x += "Nouvelle échéance proposée : " + d(e.getNouvelleEcheance());
        if (e.getProlongationMois() != null) x += "Prolongation : " + e.getProlongationMois() + " mois";
        return x;
    }
    /** Mentions de circuit (hors modèle papier) : visa du N+2, avis du directeur, validation DCHT. */
    private static List<String[]> circuit(EvaluationEssai e) {
        List<String[]> l = new ArrayList<>();
        if (e.getDateVisaN2() != null) l.add(new String[]{"Visa du N+2 (" + nom(e.getN2()) + ", " + dh(e.getDateVisaN2()) + ") : ", s(e.getVisaN2()).isBlank() ? "visé sans observation" : e.getVisaN2()});
        if (e.getDateAvisDirecteur() != null) l.add(new String[]{"Avis du directeur : ", s(e.getAvisDirecteur())});
        if (e.isValidee()) l.add(new String[]{"Validation DCHT (" + dh(e.getDateValidation()) + ") : ", e.getDecisionLabel() + (s(e.getCommentaireDcht()).isBlank() ? "" : " - " + e.getCommentaireDcht())});
        return l;
    }

    // PDF

    public byte[] pdf(EvaluationEssai e) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(92, 40, 60, 40);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD),
                    ital = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE);
            Color navy = rgb(NAVY), head = rgb(HEAD), band = rgb(BAND), frame = rgb(FRAME), black = rgb(BLACK);
            doc.setFont(reg).setFontSize(8).setFontColor(navy);
            byte[] lg = logo();
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new FicheObjectifsExportService.PageDecor(lg == null ? null : ImageDataFactory.create(lg), bold, reg, frame, PIED, DIRECTION));
            SolidBorder b = new SolidBorder(frame, 0.75f);

            // Titre
            Table tt = new Table(UnitValue.createPercentArray(new float[]{36, 64})).useAllAvailableWidth().setMarginTop(-48).setMarginBottom(16);
            tt.addCell(nb());
            Cell titre = new Cell().setBorder(new SolidBorder(black, 1.2f)).setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(6)).setPadding(5).setTextAlignment(TextAlignment.CENTER);
            titre.add(new Paragraph("FICHE D'EVALUATION INDIVIDUELLE").setFont(bold).setFontSize(10.5f).setMargin(0));
            titre.add(new Paragraph("(Personnel en CDD ou en Période d'essai)").setFont(bold).setFontSize(9).setMargin(0));
            tt.addCell(titre);
            doc.add(tt);

            // En-tête
            Users u = e.getSalarie();
            Table id = new Table(UnitValue.createPercentArray(new float[]{55, 45})).useAllAvailableWidth();
            String[][] kv = {{"Nom et Prénom du salarié : ", s(u.getNom()).toUpperCase() + " " + s(u.getPrenom()) + (s(u.getPostnom()).isBlank() ? "" : " " + u.getPostnom())},
                    {"Date d'embauche : ", d(e.getDateEmbauche())}, {"Poste occupé : ", s(u.getFonction())}, {"", ""},
                    {"Direction/Département/service/Agence : ", u.getDirection() == null ? "" : u.getDirection().getNom()}, {"Supérieur hiérarchique : ", nom(e.getEvaluateur())}};
            for (String[] p : kv) id.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph().setMargin(0).add(new Text(p[0]).setFont(bold)).add(new Text(p[1]))));
            Cell tc = new Cell(1, 2).setBorder(b).setPadding(3);
            Paragraph tp = new Paragraph().setMargin(0).add(new Text("Type de contrat :   ").setFont(bold));
            for (String[] c : cases(e)) tp.add(new Text("[" + (c[0].isEmpty() ? "  " : "X") + "] ").setFont(bold)).add(new Text(c[1] + "        "));
            tc.add(tp);
            id.addCell(tc);
            id.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph().setMargin(0).add(new Text("Date de début présent contrat : ").setFont(bold)).add(new Text(d(e.getDateDebutContrat())))));
            id.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph().setMargin(0).add(new Text("Date de fin présent contrat : ").setFont(bold)).add(new Text(e.getDateFinContrat() == null ? (e.isFinCdd() ? "" : "durée indéterminée") : d(e.getDateFinContrat())))));
            doc.add(id);

            // I. Rappel des objectifs
            doc.add(titreSection("I- RAPPEL DES OBJECTIFS", bold));
            doc.add(new Paragraph("T1= 1er trimestre, T2= 2e trimestre, T3 = 3e trimestre, T4 = 4e trimestre    O= Objectif, R= réalisé").setFont(ital).setFontSize(6.5f).setMargin(0).setMarginBottom(2));
            Table t = new Table(UnitValue.createPercentArray(new float[]{30, 22, 6, 6, 6, 6, 6, 6, 6, 6})).useAllAvailableWidth();
            t.addHeaderCell(hcell(b, head, 1, 3).add(new Paragraph("OBJECTIFS").setFont(bold).setMargin(0)));
            t.addHeaderCell(hcell(b, head, 1, 3).add(new Paragraph("INDICATEURS").setFont(bold).setMargin(0)));
            t.addHeaderCell(hcell(b, head, 8, 1).add(new Paragraph("Période d'atteinte").setFont(bold).setMargin(0)));
            for (String q : new String[]{"T1", "T2", "T3", "T4"}) t.addHeaderCell(hcell(b, head, 2, 1).add(new Paragraph(q).setFont(bold).setMargin(0)));
            for (int i = 0; i < 4; i++) for (String q : new String[]{"O", "R"}) t.addHeaderCell(hcell(b, head, 1, 1).add(new Paragraph(q).setFont(bold).setFontSize(7).setMargin(0)));
            List<EvaluationEssaiCritere> objs = e.getCriteresObjectifs();
            if (objs.isEmpty()) {
                for (int k = 1; k <= 4; k++) {
                    t.addCell(new Cell().setBorder(b).setPadding(4).setHeight(26).add(new Paragraph("Activité " + k + " :").setFont(bold).setMargin(0)));
                    for (int c = 0; c < 9; c++) t.addCell(new Cell().setBorder(b));
                }
            }
            int num = 1;
            for (EvaluationEssaiCritere k : objs) {
                if (e.debutFamille(k)) { num = 1; if (!s(k.getActivite()).isBlank()) t.addCell(new Cell(1, 10).setBorder(b).setBackgroundColor(band).setPadding(3).add(new Paragraph(k.getActivite()).setFont(bold).setMargin(0))); }
                Paragraph op = new Paragraph().setMargin(0).setFontSize(7.5f).add(new Text(num++ + "- ").setFont(bold)).add(new Text(s(k.getLibelle())));
                if (!s(k.getCommentaire()).isBlank()) op.add(new Text("\n" + k.getCommentaire()).setFont(ital).setFontSize(6.5f));
                t.addCell(new Cell().setBorder(b).setPadding(3).add(op));
                t.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph(s(k.getIndicateur())).setFontSize(7).setMargin(0)));
                for (String v : new String[]{k.getO1(), k.getR1(), k.getO2(), k.getR2(), k.getO3(), k.getR3(), k.getO4(), k.getR4()})
                    t.addCell(new Cell().setBorder(b).setPadding(2).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(s(v)).setFontSize(6.5f).setMargin(0)));
            }
            doc.add(t);

            // II. Intégration
            doc.add(titreSection("II- INTEGRATION DU SALARIE DANS L'ENVIRONNEMENT PROFESSIONNEL", bold));
            String[] nv = niveaux(e);
            doc.add(new Paragraph(e.isMaf070() ? "TB= Très Bien,  B= Bien, P= Passable, I = Insuffisant, PC= Pas Concerné" : "A= Acquis, EA= En cours d'acquisition, NA= Non acquis, NM= Non mesuré").setFont(ital).setFontSize(6.5f).setMargin(0).setMarginBottom(2));
            float[] wg = new float[2 + nv.length]; wg[0] = 42; for (int i = 0; i < nv.length; i++) wg[1 + i] = 5; wg[wg.length - 1] = 58 - 5 * nv.length;
            Table g = new Table(UnitValue.createPercentArray(wg)).useAllAvailableWidth();
            g.addHeaderCell(hcell(b, head, 1, 2).add(new Paragraph("COMPETENCES").setFont(bold).setMargin(0)));
            g.addHeaderCell(hcell(b, head, nv.length, 1).add(new Paragraph("NOTATION").setFont(bold).setMargin(0)));
            g.addHeaderCell(hcell(b, head, 1, 2).add(new Paragraph("AXES D'AMELIORATION").setFont(bold).setMargin(0)));
            for (String n : nv) g.addHeaderCell(hcell(b, head, 1, 1).add(new Paragraph(n).setFont(bold).setFontSize(7).setMargin(0)));
            for (EvaluationEssaiCritere k : e.getCriteresCompetences()) {
                if (e.debutFamille(k)) g.addCell(new Cell(1, wg.length).setBorder(b).setBackgroundColor(band).setPadding(3).add(new Paragraph(k.getFamilleLabel().toUpperCase()).setFont(bold).setMargin(0)));
                if (e.isMaf070() && e.debutGroupe(k)) g.addCell(new Cell(1, wg.length).setBorder(b).setPaddingLeft(12).setPadding(2).add(new Paragraph(k.getDescription()).setFont(bold).setFontSize(7.5f).setMargin(0)));
                g.addCell(new Cell().setBorder(b).setPadding(2).setPaddingLeft(e.isMaf070() && !s(k.getDescription()).isBlank() ? 22 : 6).add(new Paragraph(s(k.getLibelle())).setFontSize(7.5f).setMargin(0)));
                for (String n : nv) g.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(n.equals(k.getNiveau()) ? "X" : "").setFont(bold).setMargin(0)));
                g.addCell(new Cell().setBorder(b).setPadding(2).add(new Paragraph(s(k.getCommentaire())).setFontSize(7).setMargin(0)));
            }
            doc.add(g);

            // Appréciation globale + signatures
            Table ap = new Table(1).useAllAvailableWidth().setMarginTop(10).setKeepTogether(true);
            ap.addCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph("APPRECIATION GLOBALE").setFont(bold).setMargin(0)));
            Cell apc = new Cell().setBorder(b).setPadding(5).setMinHeight(60).add(new Paragraph(s(e.getCommentaireEvaluateur())).setMargin(0));
            if (!s(e.getCommentaireSalarie()).isBlank()) apc.add(new Paragraph().setMarginTop(6).setMarginBottom(0).add(new Text("Commentaire du collaborateur : ").setFont(bold)).add(new Text(e.getCommentaireSalarie())));
            ap.addCell(apc);
            doc.add(ap);
            doc.add(signatures(b, bold, ital, "Signature du Collaborateur", signSalarie(e), "Signature du Supérieur hiérarchique direct", signN1(e)));

            // III. Suite à donner
            Table su = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth().setMarginTop(12).setKeepTogether(true);
            su.addCell(new Cell(1, 2).setBorder(Border.NO_BORDER).setPadding(0).add(titreSection("III- SUITE A DONNER AU CONTRAT DE TRAVAIL", bold)));
            su.addCell(new Cell(1, 2).setBorder(b).setBackgroundColor(head).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(e.isFinCdd() ? "CONTRAT A DUREE DETERMINEE" : "PERIODE D'ESSAI (CDD ou CDI)").setFont(bold).setMargin(0)));
            for (String[] p : contrat(e)) su.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph().setMargin(0).add(new Text(p[0]).setFont(bold)).add(new Text(p[1]))));
            Paragraph dp = new Paragraph().setMargin(0).add(new Text("Décision :   ").setFont(bold));
            for (String[] c : decisions(e)) dp.add(new Text("[" + (c[0].isEmpty() ? "  " : "X") + "] ").setFont(bold)).add(new Text(c[1] + "        "));
            Cell dc = new Cell(1, 2).setBorder(b).setPadding(3).add(dp);
            if (!suiteDecision(e).isBlank()) dc.add(new Paragraph(suiteDecision(e)).setMargin(0).setMarginTop(3));
            su.addCell(dc);
            su.addCell(new Cell(1, 2).setBorder(b).setPadding(3).setMinHeight(30).add(new Paragraph().setMargin(0).add(new Text("Motif : ").setFont(bold)).add(new Text(s(e.getMotifDecision())))));
            doc.add(su);
            doc.add(signatures(b, bold, ital, "Date et Signature du Supérieur Hiérarchique", signN1(e), "Date et Signature du Directeur", signDirecteur(e)));

            List<String[]> ci = circuit(e);
            if (!ci.isEmpty()) {
                Table c = new Table(1).useAllAvailableWidth().setMarginTop(10).setKeepTogether(true);
                for (String[] x : ci) c.addCell(new Cell().setBorder(b).setPadding(3).add(new Paragraph().setMargin(0).setFontSize(7.5f).add(new Text(x[0]).setFont(bold)).add(new Text(x[1]))));
                doc.add(c);
            }
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PDF impossible : " + ex.getMessage(), ex); }
    }

    private static Paragraph titreSection(String t, PdfFont bold) {
        return new Paragraph(t).setFont(bold).setFontSize(9).setUnderline().setMarginTop(12).setMarginBottom(3);
    }
    private static Table signatures(SolidBorder b, PdfFont bold, PdfFont ital, String h1, String v1, String h2, String v2) {
        Table sg = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth().setKeepTogether(true);
        for (String[] x : new String[][]{{h1, v1}, {h2, v2}}) {
            Cell c = new Cell().setBorder(b).setHeight(62).setPadding(4).setTextAlignment(TextAlignment.CENTER);
            c.add(new Paragraph(x[0]).setFont(bold).setUnderline().setMargin(0).setMarginBottom(8));
            if (!x[1].isBlank()) c.add(new Paragraph(x[1]).setFont(ital).setFontSize(7.5f).setMargin(0));
            sg.addCell(c);
        }
        return sg;
    }

    // Word

    public byte[] docx(EvaluationEssai e) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
            CTPageSz sz = sect.addNewPgSz(); sz.setW(BigInteger.valueOf(11906)); sz.setH(BigInteger.valueOf(16838));
            CTPageMar mar = sect.addNewPgMar(); mar.setLeft(BigInteger.valueOf(800)); mar.setRight(BigInteger.valueOf(800)); mar.setTop(BigInteger.valueOf(700)); mar.setBottom(BigInteger.valueOf(700)); mar.setHeader(BigInteger.valueOf(400)); mar.setFooter(BigInteger.valueOf(400));
            CTPageBorders pb = sect.addNewPgBorders(); pb.setOffsetFrom(STPageBorderOffset.PAGE);
            for (CTBorder x : new CTBorder[]{pb.addNewTop(), pb.addNewBottom(), pb.addNewLeft(), pb.addNewRight()}) { x.setVal(STBorder.DOUBLE); x.setSz(BigInteger.valueOf(6)); x.setSpace(BigInteger.valueOf(24)); x.setColor(FRAME); }

            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph hp = header.createParagraph(); hp.setSpacingAfter(0);
            byte[] lg = logo();
            if (lg != null) hp.createRun().addPicture(new java.io.ByteArrayInputStream(lg), XWPFDocument.PICTURE_TYPE_PNG, "logo.png", Units.toEMU(95), Units.toEMU(32)); else run(hp, "BGFIBank", true, 14, NAVY);
            run(header.createParagraph(), DIRECTION, false, 6.5, BLACK);
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph fp = footer.createParagraph(); fp.setAlignment(ParagraphAlignment.CENTER);
            run(fp, PIED + "      ", true, 6.5, BLACK);
            CTSimpleField fld = fp.getCTP().addNewFldSimple(); fld.setInstr(" PAGE ");
            CTR fr = fld.addNewR(); CTRPr frp = fr.addNewRPr(); frp.addNewColor().setVal("0070C0"); frp.addNewSz().setVal(BigInteger.valueOf(20)); fr.addNewT().setStringValue("1");

            // Titre
            XWPFTable tt = doc.createTable(1, 2); noBorders(tt); widths(tt, 3700, 6606);
            XWPFTableCell tc = tt.getRow(0).getCell(1); borders(tc, 12, BLACK);
            XWPFParagraph t1 = tc.getParagraphs().get(0); t1.setAlignment(ParagraphAlignment.CENTER); t1.setSpacingBefore(80); run(t1, "FICHE D'EVALUATION INDIVIDUELLE", true, 10.5, NAVY);
            XWPFParagraph t2 = tc.addParagraph(); t2.setAlignment(ParagraphAlignment.CENTER); t2.setSpacingAfter(80); run(t2, "(Personnel en CDD ou en Période d'essai)", true, 9, NAVY);
            spacer(doc, 120);

            // En-tête
            Users u = e.getSalarie();
            XWPFTable id = doc.createTable(5, 2); thinBorders(id, FRAME); widths(id, 5600, 4706);
            String[][] kv = {{"Nom et Prénom du salarié : ", s(u.getNom()).toUpperCase() + " " + s(u.getPrenom()) + (s(u.getPostnom()).isBlank() ? "" : " " + u.getPostnom())},
                    {"Date d'embauche : ", d(e.getDateEmbauche())}, {"Poste occupé : ", s(u.getFonction())}, {"", ""},
                    {"Direction/Département/service/Agence : ", u.getDirection() == null ? "" : u.getDirection().getNom()}, {"Supérieur hiérarchique : ", nom(e.getEvaluateur())}};
            for (int i = 0; i < 6; i++) { XWPFParagraph p = id.getRow(i / 2).getCell(i % 2).getParagraphs().get(0); run(p, kv[i][0], true, 8, NAVY); run(p, kv[i][1], false, 8, NAVY); }
            mergeRange(id.getRow(3), 0, 1);
            XWPFParagraph tp = id.getRow(3).getCell(0).getParagraphs().get(0); run(tp, "Type de contrat :   ", true, 8, NAVY);
            for (String[] c : cases(e)) { run(tp, c[0].isEmpty() ? "☐ " : "☒ ", true, 9, NAVY); run(tp, c[1] + "        ", false, 8, NAVY); }
            XWPFParagraph dd1 = id.getRow(4).getCell(0).getParagraphs().get(0); run(dd1, "Date de début présent contrat : ", true, 8, NAVY); run(dd1, d(e.getDateDebutContrat()), false, 8, NAVY);
            XWPFParagraph dd2 = id.getRow(4).getCell(1).getParagraphs().get(0); run(dd2, "Date de fin présent contrat : ", true, 8, NAVY);
            run(dd2, e.getDateFinContrat() == null ? (e.isFinCdd() ? "" : "durée indéterminée") : d(e.getDateFinContrat()), false, 8, NAVY);

            // I. Objectifs
            titreW(doc, "I- RAPPEL DES OBJECTIFS");
            legendeW(doc, "T1= 1er trimestre, T2= 2e trimestre, T3 = 3e trimestre, T4 = 4e trimestre    O= Objectif, R= réalisé");
            int[] w = {3100, 2326, 610, 610, 610, 610, 610, 610, 610, 610};
            XWPFTable t = doc.createTable(3, 10); thinBorders(t, FRAME); widths(t, w);
            XWPFTableRow h0 = t.getRow(0), h1 = t.getRow(1), h2 = t.getRow(2);
            for (XWPFTableRow r : new XWPFTableRow[]{h0, h1, h2}) { r.setRepeatHeader(true); for (XWPFTableCell c : r.getTableCells()) c.setColor(HEAD); }
            for (int i = 0; i < 2; i++) { vmerge(h0.getCell(i), true); vmerge(h1.getCell(i), false); vmerge(h2.getCell(i), false); centerRun(h0.getCell(i), i == 0 ? "OBJECTIFS" : "INDICATEURS", true, 8, BLACK); }
            centerRun(h0.getCell(2), "Période d'atteinte", true, 7.5, BLACK);
            String[] tq = {"T1", "T2", "T3", "T4"};
            for (int i = 0; i < 4; i++) { centerRun(h1.getCell(2 + 2 * i), tq[i], true, 7.5, BLACK); centerRun(h2.getCell(2 + 2 * i), "O", true, 7, BLACK); centerRun(h2.getCell(3 + 2 * i), "R", true, 7, BLACK); }
            List<EvaluationEssaiCritere> objs = e.getCriteresObjectifs();
            if (objs.isEmpty()) for (int k = 1; k <= 4; k++) { XWPFTableRow r = t.createRow(); r.setHeight(480); run(r.getCell(0).getParagraphs().get(0), "Activité " + k + " :", true, 7.5, NAVY); }
            int num = 1;
            for (EvaluationEssaiCritere k : objs) {
                if (e.debutFamille(k)) {
                    num = 1;
                    if (!s(k.getActivite()).isBlank()) { XWPFTableRow ar = t.createRow(); ar.getCell(0).setColor(BAND); run(ar.getCell(0).getParagraphs().get(0), k.getActivite(), true, 7.5, NAVY); mergeCells(ar, 0, 9); }
                }
                XWPFTableRow r = t.createRow();
                XWPFParagraph op = r.getCell(0).getParagraphs().get(0); run(op, num++ + "- ", true, 7.5, NAVY); run(op, s(k.getLibelle()), false, 7.5, NAVY);
                if (!s(k.getCommentaire()).isBlank()) run(r.getCell(0).addParagraph(), k.getCommentaire(), false, 6.5, NAVY).setItalic(true);
                run(r.getCell(1).getParagraphs().get(0), s(k.getIndicateur()), false, 7, NAVY);
                String[] v = {k.getO1(), k.getR1(), k.getO2(), k.getR2(), k.getO3(), k.getR3(), k.getO4(), k.getR4()};
                for (int i = 0; i < 8; i++) { vcenter(r.getCell(2 + i)); centerRun(r.getCell(2 + i), s(v[i]), false, 6.5, NAVY); }
            }
            // fusions de l'en-tête en dernier : createRow() reprend le nombre de cellules de la première ligne
            for (int i = 3; i >= 0; i--) mergeCells(h1, 2 + 2 * i, 3 + 2 * i);
            mergeCells(h0, 2, 9);

            // II. Intégration
            titreW(doc, "II- INTEGRATION DU SALARIE DANS L'ENVIRONNEMENT PROFESSIONNEL");
            String[] nv = niveaux(e);
            legendeW(doc, e.isMaf070() ? "TB= Très Bien,  B= Bien, P= Passable, I = Insuffisant, PC= Pas Concerné" : "A= Acquis, EA= En cours d'acquisition, NA= Non acquis, NM= Non mesuré");
            int cols = 2 + nv.length;
            int[] wg = new int[cols]; wg[0] = 4300; for (int i = 0; i < nv.length; i++) wg[1 + i] = 520; wg[cols - 1] = 10306 - 4300 - 520 * nv.length;
            XWPFTable g = doc.createTable(2, cols); thinBorders(g, FRAME); widths(g, wg);
            XWPFTableRow g0 = g.getRow(0), g1 = g.getRow(1);
            for (XWPFTableRow r : new XWPFTableRow[]{g0, g1}) { r.setRepeatHeader(true); for (XWPFTableCell c : r.getTableCells()) c.setColor(HEAD); }
            vmerge(g0.getCell(0), true); vmerge(g1.getCell(0), false); centerRun(g0.getCell(0), "COMPETENCES", true, 8, BLACK);
            vmerge(g0.getCell(cols - 1), true); vmerge(g1.getCell(cols - 1), false); centerRun(g0.getCell(cols - 1), "AXES D'AMELIORATION", true, 8, BLACK);
            centerRun(g0.getCell(1), "NOTATION", true, 8, BLACK);
            for (int i = 0; i < nv.length; i++) centerRun(g1.getCell(1 + i), nv[i], true, 7, BLACK);
            for (EvaluationEssaiCritere k : e.getCriteresCompetences()) {
                if (e.debutFamille(k)) { XWPFTableRow fr2 = g.createRow(); fr2.getCell(0).setColor(BAND); run(fr2.getCell(0).getParagraphs().get(0), k.getFamilleLabel().toUpperCase(), true, 7.5, NAVY); mergeCells(fr2, 0, cols - 1); }
                if (e.isMaf070() && e.debutGroupe(k)) { XWPFTableRow sr = g.createRow(); XWPFParagraph sp = sr.getCell(0).getParagraphs().get(0); sp.setIndentationLeft(200); run(sp, k.getDescription(), true, 7.5, NAVY); mergeCells(sr, 0, cols - 1); }
                XWPFTableRow r = g.createRow();
                XWPFParagraph lp = r.getCell(0).getParagraphs().get(0); if (e.isMaf070() && !s(k.getDescription()).isBlank()) lp.setIndentationLeft(440);
                run(lp, s(k.getLibelle()), false, 7.5, NAVY);
                for (int i = 0; i < nv.length; i++) { vcenter(r.getCell(1 + i)); centerRun(r.getCell(1 + i), nv[i].equals(k.getNiveau()) ? "X" : "", true, 8, NAVY); }
                run(r.getCell(cols - 1).getParagraphs().get(0), s(k.getCommentaire()), false, 7, NAVY);
            }
            mergeCells(g0, 1, nv.length);

            // Appréciation globale + signatures
            spacer(doc, 160);
            XWPFTable ap = doc.createTable(2, 1); thinBorders(ap, FRAME); widths(ap, 10306);
            ap.getRow(0).getCell(0).setColor(HEAD); centerRun(ap.getRow(0).getCell(0), "APPRECIATION GLOBALE", true, 8, BLACK);
            ap.getRow(1).setHeight(1100);
            run(ap.getRow(1).getCell(0).getParagraphs().get(0), s(e.getCommentaireEvaluateur()), false, 8, NAVY);
            if (!s(e.getCommentaireSalarie()).isBlank()) { XWPFParagraph cp = ap.getRow(1).getCell(0).addParagraph(); cp.setSpacingBefore(100); run(cp, "Commentaire du collaborateur : ", true, 8, NAVY); run(cp, e.getCommentaireSalarie(), false, 8, NAVY); }
            signaturesW(doc, "Signature du Collaborateur", signSalarie(e), "Signature du Supérieur hiérarchique direct", signN1(e));

            // III. Suite à donner
            titreW(doc, "III- SUITE A DONNER AU CONTRAT DE TRAVAIL");
            String[][] ct = contrat(e);
            XWPFTable su = doc.createTable(1 + (ct.length + 1) / 2 + 2, 2); thinBorders(su, FRAME); widths(su, 5153, 5153);
            su.getRow(0).getCell(0).setColor(HEAD); centerRun(su.getRow(0).getCell(0), e.isFinCdd() ? "CONTRAT A DUREE DETERMINEE" : "PERIODE D'ESSAI (CDD ou CDI)", true, 8, BLACK); mergeCells(su.getRow(0), 0, 1);
            for (int i = 0; i < ct.length; i++) { XWPFParagraph p = su.getRow(1 + i / 2).getCell(i % 2).getParagraphs().get(0); run(p, ct[i][0], true, 8, NAVY); run(p, ct[i][1], false, 8, NAVY); }
            int rd = 1 + (ct.length + 1) / 2;
            XWPFParagraph dp = su.getRow(rd).getCell(0).getParagraphs().get(0); run(dp, "Décision :   ", true, 8, NAVY);
            for (String[] c : decisions(e)) { run(dp, c[0].isEmpty() ? "☐ " : "☒ ", true, 9, NAVY); run(dp, c[1] + "        ", false, 8, NAVY); }
            if (!suiteDecision(e).isBlank()) run(su.getRow(rd).getCell(0).addParagraph(), suiteDecision(e), false, 8, NAVY);
            mergeCells(su.getRow(rd), 0, 1);
            su.getRow(rd + 1).setHeight(600);
            XWPFParagraph mp = su.getRow(rd + 1).getCell(0).getParagraphs().get(0); run(mp, "Motif : ", true, 8, NAVY); run(mp, s(e.getMotifDecision()), false, 8, NAVY);
            mergeCells(su.getRow(rd + 1), 0, 1);
            signaturesW(doc, "Date et Signature du Supérieur Hiérarchique", signN1(e), "Date et Signature du Directeur", signDirecteur(e));

            List<String[]> ci = circuit(e);
            if (!ci.isEmpty()) {
                spacer(doc, 160);
                XWPFTable c = doc.createTable(ci.size(), 1); thinBorders(c, FRAME); widths(c, 10306);
                for (int i = 0; i < ci.size(); i++) { XWPFParagraph p = c.getRow(i).getCell(0).getParagraphs().get(0); run(p, ci.get(i)[0], true, 7.5, NAVY); run(p, ci.get(i)[1], false, 7.5, NAVY); }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export Word impossible : " + ex.getMessage(), ex); }
    }

    private static void titreW(XWPFDocument doc, String t) {
        XWPFParagraph p = doc.createParagraph(); p.setSpacingBefore(240); p.setSpacingAfter(60);
        run(p, t, true, 9, NAVY).setUnderline(UnderlinePatterns.SINGLE);
    }
    private static void legendeW(XWPFDocument doc, String t) {
        XWPFParagraph p = doc.createParagraph(); p.setSpacingAfter(40); run(p, t, false, 6.5, BLACK).setItalic(true);
    }
    private static void signaturesW(XWPFDocument doc, String h1, String v1, String h2, String v2) {
        XWPFTable sg = doc.createTable(1, 2); thinBorders(sg, FRAME); widths(sg, 5153, 5153); sg.getRow(0).setHeight(1300);
        String[][] x = {{h1, v1}, {h2, v2}};
        for (int i = 0; i < 2; i++) {
            XWPFTableCell c = sg.getRow(0).getCell(i);
            XWPFParagraph p = c.getParagraphs().get(0); p.setAlignment(ParagraphAlignment.CENTER); run(p, x[i][0], true, 8, NAVY).setUnderline(UnderlinePatterns.SINGLE);
            if (!x[i][1].isBlank()) for (String l : x[i][1].split("\n")) { XWPFParagraph p2 = c.addParagraph(); p2.setAlignment(ParagraphAlignment.CENTER); p2.setSpacingBefore(60); run(p2, l, false, 7.5, NAVY).setItalic(true); }
        }
    }
    /** Fusion horizontale réelle (gridSpan) : la première cellule s'étend, les suivantes sont retirées. */
    private static void mergeCells(XWPFTableRow r, int from, int to) {
        XWPFTableCell c = r.getCell(from);
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        (pr.isSetGridSpan() ? pr.getGridSpan() : pr.addNewGridSpan()).setVal(BigInteger.valueOf(to - from + 1));
        for (int i = to; i > from; i--) r.removeCell(i);
    }
}
