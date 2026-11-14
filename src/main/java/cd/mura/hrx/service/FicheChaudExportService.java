package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.EvalChaudQuestion;
import cd.mura.hrx.entity.eval.EvalChaudReponse;
import cd.mura.hrx.entity.eval.EvalChaudValeur;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.events.PdfDocumentEvent;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.DashedBorder;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static cd.mura.hrx.service.FicheObjectifsExportService.*;

/**
 * Fiches d'évaluation des formations : MAF 064 DCHT 05 « Fiche d'évaluation à froid » (avec l'avis du supérieur hiérarchique) et
 * Fiche MAF 063 DCHT 02 « Fiche d'évaluation à chaud de la satisfaction des participants » : remplie (réponse d'un stagiaire)
 * ou vierge (collecte sur papier). Les questions sont celles du questionnaire de l'évaluation, quel que soit leur nombre.
 */
@ApplicationScoped
public class FicheChaudExportService implements Serializable {

    private static final String NAVY = "17365D", FRAME = "808080", HEAD = "D9D9D9", BAND = "DBE5F1";
    private static final String DIRECTION = "Direction du Capital Humain et de la Transformation";
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Texte sûr pour les polices standard du PDF. */
    private static String s(String v) { return v == null ? "" : v.replace("≥", ">=").replace("≤", "<=").replace("👋", ""); }

    public byte[] pdf(EvalChaud c, Users stagiaire, EvalChaudReponse r) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(92, 44, 60, 44);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD),
                    ital = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE);
            Color navy = rgb(NAVY), frame = rgb(FRAME), head = rgb(HEAD), band = rgb(BAND);
            doc.setFont(reg).setFontSize(9).setFontColor(navy);
            byte[] lg = logo();
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new FicheObjectifsExportService.PageDecor(lg == null ? null : ImageDataFactory.create(lg), bold, reg, frame,
                    c.isFroid() ? EvalChaudService.REF_MAF064 : EvalChaudService.REF_MAF063, DIRECTION));
            SolidBorder b = new SolidBorder(frame, 0.75f);

            Map<Integer, EvalChaudValeur> val = new HashMap<>();
            if (r != null) for (EvalChaudValeur v : r.getValeurs()) val.put(v.getQuestion().getId(), v);

            // Titre
            Table tt = new Table(UnitValue.createPercentArray(new float[]{36, 64})).useAllAvailableWidth().setMarginTop(-48).setMarginBottom(14);
            tt.addCell(nb());
            tt.addCell(new Cell().setBorder(new SolidBorder(ColorConstants.BLACK, 1.2f)).setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(6)).setPadding(6).setTextAlignment(TextAlignment.CENTER)
                    .add(new Paragraph(c.isFroid() ? "FICHE D'ÉVALUATION À FROID" : "FICHE D'ÉVALUATION À CHAUD").setFont(bold).setFontSize(12).setMargin(0))
                    .add(new Paragraph(c.isFroid() ? "Efficacité et impact de la formation" : "Satisfaction des participants").setFont(bold).setFontSize(9).setMargin(0)));
            doc.add(tt);

            // Accroche
            String intro = c.getConsignes() == null || c.getConsignes().isBlank() ? (c.isFroid() ? EvalChaudService.INTRO_MAF064 : EvalChaudService.INTRO_MAF063) : c.getConsignes();
            doc.add(new Paragraph(s(intro)).setFont(ital).setFontSize(8.5f).setBackgroundColor(band).setPadding(7).setMarginBottom(10));

            // Identification
            Table id = new Table(UnitValue.createPercentArray(new float[]{32, 68})).useAllAvailableWidth().setMarginBottom(12);
            String df = c.getDateFormationDebut() == null ? "" : c.getDateFormationDebut().format(D)
                    + (c.getDateFormationFin() != null && !c.getDateFormationFin().equals(c.getDateFormationDebut()) ? " au " + c.getDateFormationFin().format(D) : "");
            Users n1 = stagiaire == null ? null : stagiaire.getSuperieurHierarchique();
            String[][] kv = c.isFroid() ? new String[][]{{"Intitulé de la formation :", c.getIntitule()}, {"Date de la formation :", df},
                    {"Nom de l'organisme de formation :", s(c.getOrganisme())}, {"Nom du formateur :", s(c.getFormateur())},
                    {"Nom du collaborateur stagiaire :", stagiaire == null ? "" : stagiaire.getNomComplet()},
                    {"Fonction du collaborateur stagiaire :", stagiaire == null ? "" : s(stagiaire.getFonction())},
                    {"Nom du supérieur hiérarchique :", n1 == null ? "" : n1.getNomComplet()},
                    {"Fonction du supérieur hiérarchique :", n1 == null ? "" : s(n1.getFonction())},
                    {"Date de l'évaluation post formation :", r == null ? "" : new SimpleDateFormat("dd/MM/yyyy").format(r.getDateReponse())}}
                    : new String[][]{{"Intitulé de la formation :", c.getIntitule()}, {"Date de la formation :", df},
                    {"Nom de l'organisme de formation :", s(c.getOrganisme()) + (c.getFormateur() == null || c.getFormateur().isBlank() ? "" : " (formateur : " + c.getFormateur() + ")")},
                    {"Nom du stagiaire :", stagiaire == null ? "" : stagiaire.getNomComplet()},
                    {"Date du rapport :", r == null ? "" : new SimpleDateFormat("dd/MM/yyyy").format(r.getDateReponse())}};
            for (String[] p : kv) {
                id.addCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(4).add(new Paragraph(p[0]).setFont(bold).setMargin(0)));
                id.addCell(new Cell().setBorder(b).setPadding(4).setMinHeight(18).add(new Paragraph(s(p[1])).setMargin(0)));
            }
            doc.add(id);
            if (c.isFroid()) doc.add(new Paragraph("Nous vous invitons à bien vouloir répondre aux questions suivantes :").setFont(ital).setMarginBottom(8));

            // Questions
            int n = 1;
            for (EvalChaudQuestion q : c.getQuestions()) {
                EvalChaudValeur v = val.get(q.getId());
                Integer note = v == null ? null : v.getNote();
                String texte = v == null ? null : v.getTexte();
                Div bloc = new Div().setKeepTogether(true).setMarginBottom(10);
                if (!q.isPlan()) bloc.add(new Paragraph().setMargin(0).setMarginBottom(4).add(new Text("Q." + n + "  ").setFont(bold)).add(new Text(s(q.getLibelle())).setFont(bold)));
                if (q.isConditionnelle()) bloc.add(new Paragraph("(seulement si « non » à la question précédente)").setFont(ital).setFontSize(7.5f).setMargin(0).setMarginLeft(14).setMarginBottom(2));
                if (q.isNote() && q.isLibellee()) {
                    for (int k = 1; k <= 5; k++) bloc.add(caseCochee(note != null && note == k, q.niveau(k), bold));
                } else if (q.isMulti() || q.isObjectifs()) {
                    List<String> sel = EvalChaudService.lignes(texte);
                    List<String> opts = q.isObjectifs() ? c.getObjectifsListe() : q.getOptionsListe();
                    if (q.isObjectifs() && opts.isEmpty()) for (int k = 1; k <= 3; k++) bloc.add(caseCochee(false, "Objectif " + k + " : ____________________________________________", bold));
                    for (String o : opts) bloc.add(caseCochee(sel.contains(o), o, bold));
                    if (q.isAutre()) {
                        String autre = sel.stream().filter(x -> x.startsWith(EvalChaudQuestion.AUTRE + "\t")).map(x -> EvalChaudService.choix(x)[1]).findFirst().orElse(null);
                        bloc.add(caseCochee(autre != null, "Autre (veuillez préciser) : " + (autre == null ? "_______________________________" : autre), bold));
                    }
                } else if (q.isNiveaux()) {
                    List<String> l = EvalChaudService.lignes(texte);
                    int nb = r == null ? 3 : l.size();
                    if (r != null && l.isEmpty()) bloc.add(new Paragraph("—").setMargin(0).setMarginLeft(14));
                    for (int k = 0; k < nb; k++) {
                        String[] a = k < l.size() ? EvalChaudService.niveauLigne(l.get(k)) : new String[]{"", ""};
                        Table t = new Table(UnitValue.createPercentArray(new float[]{28, 72})).useAllAvailableWidth().setMarginLeft(14).setMarginBottom(4);
                        t.addCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE)
                                .add(new Paragraph("Compétence " + (k + 1) + " : " + s(a[0])).setFont(bold).setFontSize(8.5f).setMargin(0)));
                        Paragraph p = new Paragraph().setMargin(0).setFontSize(8);
                        for (int lv = 1; lv <= 5; lv++) p.add(new Text("[" + (a[1].equals(String.valueOf(lv)) ? "X" : "  ") + "] ").setFont(bold)).add(new Text(s(q.niveau(lv)) + "   "));
                        t.addCell(new Cell().setBorder(b).setPadding(4).add(p));
                        bloc.add(t);
                    }
                } else if (q.isNote()) {
                    Table e = new Table(UnitValue.createPercentArray(5)).setWidth(UnitValue.createPercentValue(60)).setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
                    for (int k = 1; k <= 5; k++) {
                        boolean on = note != null && note == k;
                        Cell cc = new Cell().setBorder(b).setPadding(4).setTextAlignment(TextAlignment.CENTER)
                                .add(new Paragraph(String.valueOf(k)).setFont(bold).setMargin(0).setFontColor(on ? ColorConstants.WHITE : navy));
                        if (on) cc.setBackgroundColor(navy);
                        e.addCell(cc);
                    }
                    bloc.add(e);
                    Table bornes = new Table(UnitValue.createPercentArray(new float[]{50, 50})).setWidth(UnitValue.createPercentValue(60)).setHorizontalAlignment(com.itextpdf.layout.properties.HorizontalAlignment.CENTER);
                    bornes.addCell(nb().add(new Paragraph(s(q.getBorneMin())).setFont(ital).setFontSize(7.5f).setMargin(0)));
                    bornes.addCell(nb().setTextAlignment(TextAlignment.RIGHT).add(new Paragraph(s(q.getBorneMax())).setFont(ital).setFontSize(7.5f).setMargin(0)));
                    bloc.add(bornes);
                } else if (q.isOuiNon()) {
                    Paragraph p = new Paragraph().setMargin(0).setMarginLeft(14);
                    p.add(new Text("[" + (Integer.valueOf(1).equals(note) ? "X" : "  ") + "]  ").setFont(bold)).add(new Text("Oui            "));
                    p.add(new Text("[" + (Integer.valueOf(0).equals(note) ? "X" : "  ") + "]  ").setFont(bold)).add(new Text("Non"));
                    bloc.add(p);
                    if (q.isAvecPrecision()) bloc.add(new Paragraph().setMargin(0).setMarginLeft(14).setMarginTop(3)
                            .add(new Text(s(q.getPrecisionOui()) + " : ").setFont(bold)).add(new Text(texte == null || texte.isBlank() ? "_______________________________________________" : s(texte))));
                } else if (q.isListe()) {
                    List<String> l = EvalChaudService.lignes(texte);
                    String pt = q.getLibelle().toLowerCase(Locale.ROOT).contains("difficult") ? "Difficulté " : "Compétence ";
                    if (l.isEmpty() && c.isFroid()) { for (int k = 0; k < Math.min(3, q.getMaxPoints()); k++) bloc.add(ligneVide(frame).add(new Text(" " + pt + (k + 1) + " :"))); }
                    else if (l.isEmpty()) for (int k = 0; k < 3; k++) bloc.add(ligneVide(frame));
                    else if (c.isFroid()) { int k = 1; for (String x : l) bloc.add(new Paragraph(pt + (k++) + " : " + s(x)).setMargin(0).setMarginLeft(14).setMarginBottom(2)); }
                    else for (String x : l) bloc.add(new Paragraph("-   " + s(x)).setMargin(0).setMarginLeft(14).setMarginBottom(2));
                } else if (q.isTexte()) {
                    if (texte == null || texte.isBlank()) for (int k = 0; k < 3; k++) bloc.add(ligneVide(frame));
                    else bloc.add(new Paragraph(s(texte)).setMargin(0).setMarginLeft(14));
                } else if (q.isPlan()) {
                    String[][] hz = EvalChaudQuestion.HORIZONS;
                    Table t = new Table(UnitValue.createPercentArray(new float[]{48, 13, 13, 13, 13})).useAllAvailableWidth();
                    t.addHeaderCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(4).add(new Paragraph().setMargin(0).add(new Text("Q." + n + "  ").setFont(bold)).add(new Text(s(q.getLibelle())).setFont(bold))));
                    for (String[] h : hz) t.addHeaderCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(4).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(h[1]).setFont(bold).setFontSize(8).setMargin(0)));
                    t.addCell(new Cell(1, 5).setBorder(b).setPadding(4).add(new Paragraph("Au terme de la formation, le participant sera capable de :").setFont(ital).setMargin(0)));
                    List<String> l = EvalChaudService.lignes(texte);
                    int lignes = Math.max(3, l.size());
                    for (int k = 0; k < lignes; k++) {
                        String[] a = k < l.size() ? EvalChaudService.action(l.get(k)) : new String[]{"", ""};
                        t.addCell(new Cell().setBorder(b).setPadding(4).setMinHeight(18).add(new Paragraph((k + 1) + ".  " + s(a[1])).setMargin(0)));
                        for (String[] h : hz) t.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(h[0].equals(a[0]) ? "X" : "").setFont(bold).setMargin(0)));
                    }
                    bloc.add(t);
                }
                doc.add(bloc);
                n++;
            }

            if (c.isFroid()) {
                ficheAvis(doc, r, stagiaire, b, bold, reg, ital, navy, head, frame);
                doc.add(new Paragraph("Merci d'avoir pris le temps de répondre à ce questionnaire.").setFont(ital).setFontSize(8.5f).setTextAlignment(TextAlignment.CENTER).setMarginTop(10));
                doc.add(new Paragraph("Document interne, ne pas diffuser à l'extérieur  -  @ BGFIBank RDC SA").setFontSize(7).setTextAlignment(TextAlignment.CENTER).setFontColor(frame).setMargin(0));
                doc.close();
                return out.toByteArray();
            }

            // Signatures
            Table sg = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth().setMarginTop(8).setKeepTogether(true);
            String[][] sig = {{"Signature du stagiaire", r == null ? "" : "Réponse envoyée électroniquement le " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(r.getDateReponse()) + "\n" + (stagiaire == null ? "" : stagiaire.getNomComplet())},
                    {"Signature du formateur", c.getFormateur() == null ? "" : c.getFormateur()}};
            for (String[] x : sig) {
                Cell cc = new Cell().setBorder(b).setHeight(60).setPadding(4).setTextAlignment(TextAlignment.CENTER)
                        .add(new Paragraph(x[0]).setFont(bold).setUnderline().setMargin(0).setMarginBottom(8));
                if (!x[1].isBlank()) cc.add(new Paragraph(s(x[1])).setFont(ital).setFontSize(8).setMargin(0));
                sg.addCell(cc);
            }
            doc.add(sg);
            doc.add(new Paragraph("Merci à vous d'avoir pris le temps de compléter cette enquête de satisfaction. Vos réponses nous aideront à offrir des formations futures d'exception.")
                    .setFont(ital).setFontSize(8.5f).setTextAlignment(TextAlignment.CENTER).setMarginTop(10));
            doc.add(new Paragraph("Document interne, ne pas diffuser à l'extérieur  -  @ BGFIBank RDC SA").setFontSize(7).setTextAlignment(TextAlignment.CENTER).setFontColor(frame).setMargin(0));
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PDF impossible : " + ex.getMessage(), ex); }
    }

    private static Paragraph caseCochee(boolean on, String libelle, PdfFont bold) {
        return new Paragraph().setMargin(0).setMarginLeft(14).setMarginBottom(2).add(new Text("[" + (on ? "X" : "  ") + "]  ").setFont(bold)).add(new Text(s(libelle)));
    }

    /** Avis général de l'évaluateur, commentaire, barème de notation et signatures (fiche MAF 064). */
    private static void ficheAvis(Document doc, EvalChaudReponse r, Users stagiaire, SolidBorder b, PdfFont bold, PdfFont reg, PdfFont ital, Color navy, Color head, Color frame) {
        Integer avis = r == null ? null : r.getAvisNote();
        Div bloc = new Div().setKeepTogether(true).setMarginTop(6);
        bloc.add(new Paragraph("Avis général de l'évaluateur à l'issue de l'entretien sur l'efficacité de la formation").setFont(bold).setMargin(0).setMarginBottom(2));
        bloc.add(new Paragraph("Encerclez la bonne réponse").setFont(ital).setFontSize(7.5f).setMargin(0).setMarginBottom(4));
        Table t = new Table(UnitValue.createPercentArray(5)).useAllAvailableWidth();
        for (int k = 1; k <= 5; k++) {
            boolean on = avis != null && avis == k;
            Cell cc = new Cell().setBorder(b).setPadding(4).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .add(new Paragraph(EvalChaudReponse.AVIS_LIBELLES[k]).setFont(bold).setFontSize(8).setMargin(0).setFontColor(on ? ColorConstants.WHITE : navy))
                    .add(new Paragraph("(" + k + ")").setFontSize(8).setMargin(0).setFontColor(on ? ColorConstants.WHITE : navy));
            if (on) cc.setBackgroundColor(navy);
            t.addCell(cc);
        }
        bloc.add(t);
        Cell com = new Cell().setBorder(b).setPadding(5).setMinHeight(70)
                .add(new Paragraph("Commentaire général de l'évaluateur (application des compétences acquises, amélioration des performances globales, moyens mis à disposition du collaborateur, etc.)")
                        .setFont(bold).setFontSize(8).setMargin(0).setMarginBottom(4));
        if (r != null && r.getAvisCommentaire() != null) com.add(new Paragraph(s(r.getAvisCommentaire())).setMargin(0));
        bloc.add(new Table(1).useAllAvailableWidth().setMarginTop(6).addCell(com));
        doc.add(bloc);

        Div bar = new Div().setKeepTogether(true).setMarginTop(10);
        bar.add(new Paragraph("Barème de notation :").setFont(bold).setMargin(0).setMarginBottom(3));
        Table bt = new Table(UnitValue.createPercentArray(new float[]{16, 34, 25, 25})).useAllAvailableWidth();
        for (String h : new String[]{"Note sur 5", "Libellé", "Taux de satisfaction", "Niveau efficacité"})
            bt.addHeaderCell(new Cell().setBorder(b).setBackgroundColor(head).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(h).setFont(bold).setFontSize(8).setMargin(0)));
        for (int k = 1; k <= 5; k++) {
            boolean on = avis != null && avis == k;
            String[] v = {String.valueOf(k), EvalChaudReponse.AVIS_LIBELLES[k], EvalChaudReponse.AVIS_TAUX[k] + "%", k >= 4 ? "Efficace" : "Pas efficace"};
            for (String x : v) {
                Cell cc = new Cell().setBorder(b).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(x).setFontSize(8).setMargin(0).setFont(on ? bold : reg));
                if (on) cc.setBackgroundColor(head);
                bt.addCell(cc);
            }
        }
        bar.add(bt);
        doc.add(bar);

        Users n1 = r != null && r.getEvaluateur() != null ? r.getEvaluateur() : stagiaire == null ? null : stagiaire.getSuperieurHierarchique();
        SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        Table sg = new Table(UnitValue.createPercentArray(new float[]{50, 50})).useAllAvailableWidth().setMarginTop(12).setKeepTogether(true);
        String[][] sig = {{"Signature du collaborateur stagiaire", r == null ? "" : "Fiche envoyée électroniquement le " + f.format(r.getDateReponse()) + "\n" + (stagiaire == null ? "" : stagiaire.getNomComplet())},
                {"Signature du supérieur hiérarchique", r == null || r.getDateAvis() == null ? (n1 == null ? "" : n1.getNomComplet()) : "Avis signé électroniquement le " + f.format(r.getDateAvis()) + "\n" + (n1 == null ? "" : n1.getNomComplet())}};
        for (String[] x : sig) {
            Cell cc = new Cell().setBorder(b).setHeight(60).setPadding(4).setTextAlignment(TextAlignment.CENTER)
                    .add(new Paragraph(x[0]).setFont(bold).setUnderline().setMargin(0).setMarginBottom(8));
            if (!x[1].isBlank()) cc.add(new Paragraph(s(x[1])).setFont(ital).setFontSize(8).setMargin(0));
            sg.addCell(cc);
        }
        doc.add(sg);
    }

    private static Paragraph ligneVide(Color frame) {
        return new Paragraph("-").setMargin(0).setMarginLeft(14).setMarginBottom(2).setBorderBottom(new DashedBorder(frame, 0.5f)).setPaddingBottom(4);
    }
}
