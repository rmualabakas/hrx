package cd.mura.hrx.service;

import cd.mura.hrx.bean.eval.ReportingView;
import cd.mura.hrx.entity.eval.Campagne;
import cd.mura.hrx.entity.eval.Evaluation;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Exports du reporting : classeur Excel détaillé (4 onglets) et synthèse PDF au format BGFI. */
@ApplicationScoped
public class ReportingExportService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final SimpleDateFormat DT = new SimpleDateFormat("dd/MM/yyyy");

    public record Donnees(Campagne campagne, String perimetre, long attendues, int tauxGlobal, ReportingView.Agregat total,
                          List<ReportingView.Agregat> directions, List<ReportingView.Repartition> niveaux,
                          List<ReportingView.Repartition> appreciations, List<ReportingView.Repartition> souhaits,
                          List<ReportingView.Formation> formations, List<Evaluation> fiches, Map<Integer, String> directionParSalarie,
                          int seuil, int pctSeuilGlobal) { }

    // Excel

    public byte[] excel(Donnees d) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);

            // --- Synthèse par direction
            Sheet s1 = wb.createSheet("Synthèse par direction");
            int r = entete(s1, st, d, "Taux de réalisation par direction", 12);
            String[] h1 = {"Direction", "Effectif évaluable", "Fiches initiées", "Intégration %", "Auto-éval. soumises", "Évaluées (N+1)", "Validées", "Taux de réalisation %", "Note moyenne /20", "Soumises à temps %", "Atteints", "Moyennement", "Faiblement", "Non atteints",
                    "Objectifs ≥ " + d.seuil() + " % (nb)", "≥ " + d.seuil() + " % / effectif %", "≥ " + d.seuil() + " % / évalués %"};
            r = ligneEntete(s1, st, r, h1);
            for (ReportingView.Agregat a : d.directions()) r = ligneAgregat(s1, st, r, a, false, a.getPctAtteinteSeuil());
            ligneAgregat(s1, st, r, d.total(), true, d.pctSeuilGlobal());
            largeurs(s1, 38, 14, 14, 12, 16, 14, 11, 16, 14, 16, 10, 12, 11, 12, 16, 18, 18);
            s1.createFreezePane(1, 5);

            // --- Répartitions
            Sheet s2 = wb.createSheet("Statistiques");
            r = entete(s2, st, d, "Statistiques consolidées", 3);
            r = bloc(s2, st, r, "Niveaux d'atteinte des objectifs", d.niveaux());
            r = bloc(s2, st, r + 1, "Appréciations (grille /20)", d.appreciations());
            bloc(s2, st, r + 1, "Souhaits exprimés par les collaborateurs", d.souhaits());
            largeurs(s2, 42, 14, 12);

            // --- Formations
            Sheet s3 = wb.createSheet("Besoins de formation");
            r = entete(s3, st, d, "Besoins de formation consolidés", 9);
            r = ligneEntete(s3, st, r, new String[]{"Formation", "Demandes", "Priorité haute", "Moyenne", "Basse", "Par l'évaluateur", "Par l'évalué", "Par le directeur", "Directions", "Collaborateurs"});
            for (ReportingView.Formation f : d.formations()) {
                Row row = s3.createRow(r++);
                txt(row, 0, f.getIntitule(), st.cell); num(row, 1, f.getDemandes(), st.num); num(row, 2, f.getHaute(), st.num); num(row, 3, f.getMoyenne(), st.num);
                num(row, 4, f.getBasse(), st.num); num(row, 5, f.getParEvaluateur(), st.num); num(row, 6, f.getParEvalue(), st.num); num(row, 7, f.getParDirecteur(), st.num);
                txt(row, 8, f.getDirectionsTexte(), st.cell); txt(row, 9, f.getCollaborateursTexte(), st.cell);
            }
            largeurs(s3, 40, 11, 13, 10, 10, 15, 13, 15, 26, 48);
            s3.createFreezePane(1, 5);

            // --- Détail des fiches
            Sheet s4 = wb.createSheet("Détail des évaluations");
            r = entete(s4, st, d, "Détail des fiches d'évaluation", 12);
            r = ligneEntete(s4, st, r, new String[]{"Matricule", "Collaborateur", "Fonction", "Direction", "Évaluateur (N+1)", "Statut", "Auto-évaluation", "À temps", "Évaluation N+1", "Taux objectifs %", "Note /20", "Appréciation", "Niveau d'atteinte", "Souhait du collaborateur", "Validation"});
            for (Evaluation e : d.fiches()) {
                Row row = s4.createRow(r++);
                txt(row, 0, e.getSalarie().getMatricule(), st.cell); txt(row, 1, e.getSalarie().getNomComplet(), st.cell); txt(row, 2, e.getSalarie().getFonction(), st.cell);
                txt(row, 3, d.directionParSalarie().getOrDefault(e.getSalarie().getId(), ""), st.cell);
                txt(row, 4, e.getEvaluateur() == null ? "" : e.getEvaluateur().getNomComplet(), st.cell); txt(row, 5, e.getStatutLabel(), st.cell);
                txt(row, 6, date(e.getDateSoumission()), st.cell); txt(row, 7, e.getDateSoumission() == null ? "" : e.isSoumiseATemps() ? "Oui" : "Non", st.cell);
                txt(row, 8, date(e.getDateEvaluation()), st.cell);
                boolean notee = Evaluation.EVALUEE.equals(e.getStatut()) || Evaluation.VALIDEE.equals(e.getStatut());
                if (notee && e.getScoreObjectifs() != null) dec(row, 9, e.getScoreObjectifs(), st.dec); else txt(row, 9, "", st.cell);
                if (notee && e.getNoteGlobale() != null) dec(row, 10, e.getNoteGlobale(), st.dec); else txt(row, 10, "", st.cell);
                txt(row, 11, notee ? e.getAppreciation() : "", st.cell); txt(row, 12, notee ? Evaluation.niveauAtteinteLabel(e.getNiveauAtteinte()) : "", st.cell);
                txt(row, 13, e.getSouhaitsLabel(), st.cell); txt(row, 14, date(e.getDateValidation()), st.cell);
            }
            largeurs(s4, 12, 30, 28, 30, 26, 24, 14, 9, 14, 13, 10, 16, 26, 30, 12);
            s4.createFreezePane(2, 5);
            if (r > 5) s4.setAutoFilter(new CellRangeAddress(4, r - 1, 0, 14));

            wb.write(out);
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export Excel impossible : " + ex.getMessage(), ex); }
    }

    private static final class Styles {
        final CellStyle titre, sous, head, cell, num, dec, totalTxt, totalNum, totalDec;
        Styles(Workbook wb) {
            Font fT = wb.createFont(); fT.setBold(true); fT.setFontHeightInPoints((short) 14); fT.setColor(IndexedColors.DARK_BLUE.getIndex());
            titre = wb.createCellStyle(); titre.setFont(fT);
            Font fS = wb.createFont(); fS.setItalic(true); fS.setFontHeightInPoints((short) 9); fS.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            sous = wb.createCellStyle(); sous.setFont(fS);
            Font fH = wb.createFont(); fH.setBold(true); fH.setColor(IndexedColors.WHITE.getIndex()); fH.setFontHeightInPoints((short) 10);
            head = wb.createCellStyle(); head.setFont(fH); head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setWrapText(true); head.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER); bord(head);
            cell = wb.createCellStyle(); bord(cell); cell.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP); cell.setWrapText(true);
            num = wb.createCellStyle(); bord(num); num.setAlignment(HorizontalAlignment.RIGHT);
            dec = wb.createCellStyle(); bord(dec); dec.setAlignment(HorizontalAlignment.RIGHT); dec.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            Font fB = wb.createFont(); fB.setBold(true);
            totalTxt = wb.createCellStyle(); totalTxt.cloneStyleFrom(cell); totalTxt.setFont(fB); totalTxt.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); totalTxt.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalNum = wb.createCellStyle(); totalNum.cloneStyleFrom(num); totalNum.setFont(fB); totalNum.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); totalNum.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalDec = wb.createCellStyle(); totalDec.cloneStyleFrom(dec); totalDec.setFont(fB); totalDec.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex()); totalDec.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        private static void bord(CellStyle c) { c.setBorderBottom(BorderStyle.THIN); c.setBorderTop(BorderStyle.THIN); c.setBorderLeft(BorderStyle.THIN); c.setBorderRight(BorderStyle.THIN);
            short g = IndexedColors.GREY_25_PERCENT.getIndex(); c.setBottomBorderColor(g); c.setTopBorderColor(g); c.setLeftBorderColor(g); c.setRightBorderColor(g); }
    }

    private int entete(Sheet s, Styles st, Donnees d, String titre, int largeur) {
        Row r0 = s.createRow(0); txt(r0, 0, "BGFIBank RDC - HRX · " + titre, st.titre);
        Row r1 = s.createRow(1); txt(r1, 0, "Campagne : " + d.campagne().getLibelle() + " (" + d.campagne().getTypeLabel() + " " + d.campagne().getExercice() + ")"
                + (d.campagne().getDateDebut() == null ? "" : " · du " + d.campagne().getDateDebut().format(D)) + (d.campagne().getDateFin() == null ? "" : " au " + d.campagne().getDateFin().format(D)), st.sous);
        Row r2 = s.createRow(2); txt(r2, 0, "Périmètre : " + d.perimetre() + " · édité le " + DT.format(new Date()) + " · document confidentiel", st.sous);
        return 4;
    }
    private int ligneEntete(Sheet s, Styles st, int r, String[] h) {
        Row row = s.createRow(r); row.setHeightInPoints(30);
        for (int i = 0; i < h.length; i++) txt(row, i, h[i], st.head);
        return r + 1;
    }
    private int ligneAgregat(Sheet s, Styles st, int r, ReportingView.Agregat a, boolean tot, int pctSeuil) {
        Row row = s.createRow(r);
        CellStyle t = tot ? st.totalTxt : st.cell, n = tot ? st.totalNum : st.num, dd = tot ? st.totalDec : st.dec;
        txt(row, 0, a.getNom(), t); num(row, 1, a.getEffectif(), n); num(row, 2, a.getInitiees(), n); num(row, 3, a.getTauxIntegration(), n);
        num(row, 4, a.getSoumises() + a.getEvaluees() + a.getValidees(), n); num(row, 5, a.getEvaluees() + a.getValidees(), n); num(row, 6, a.getValidees(), n);
        num(row, 7, a.getTaux(), n);
        if (a.getNoteMoyenne() != null) dec(row, 8, a.getNoteMoyenne(), dd); else txt(row, 8, "", t);
        num(row, 9, a.getPctATemps(), n); num(row, 10, a.getAtteints(), n); num(row, 11, a.getMoyennement(), n); num(row, 12, a.getFaiblement(), n); num(row, 13, a.getNonAtteints(), n);
        num(row, 14, a.getAtteintSeuil(), n); num(row, 15, pctSeuil, n); num(row, 16, a.getPctSeuilEvalues(), n);
        return r + 1;
    }
    private int bloc(Sheet s, Styles st, int r, String titre, List<ReportingView.Repartition> l) {
        r = ligneEntete(s, st, r, new String[]{titre, "Effectif", "%"});
        for (ReportingView.Repartition x : l) { Row row = s.createRow(r++); txt(row, 0, x.libelle(), st.cell); num(row, 1, x.nb(), st.num); num(row, 2, x.pct(), st.num); }
        return r;
    }
    private static void txt(Row r, int c, String v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v == null ? "" : v); x.setCellStyle(st); }
    private static void num(Row r, int c, long v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v); x.setCellStyle(st); }
    private static void dec(Row r, int c, double v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v); x.setCellStyle(st); }
    private static void largeurs(Sheet s, int... w) { for (int i = 0; i < w.length; i++) s.setColumnWidth(i, w[i] * 256); }
    private static String date(Date d) { return d == null ? "" : DT.format(d); }

    // PDF

    private static final Color NAVY = new DeviceRgb(7, 41, 74), GOLD = new DeviceRgb(143, 163, 116) /* vert sauge du logo BGFI (filets) */, GRIS = new DeviceRgb(107, 114, 128),
            LIGNE = new DeviceRgb(220, 226, 232), FOND = new DeviceRgb(244, 247, 250), VERT = new DeviceRgb(46, 125, 80), ROUGE = new DeviceRgb(185, 56, 56), BLEU = new DeviceRgb(10, 92, 143);

    public byte[] pdf(Donnees d) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4.rotate());
            doc.setMargins(30, 32, 34, 32);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            doc.setFont(reg).setFontSize(9);

            // En-tête
            Table head = new Table(UnitValue.createPercentArray(new float[]{18, 82})).useAllAvailableWidth();
            byte[] lg = logo();
            Cell cl = new Cell().setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE);
            if (lg != null) cl.add(new Image(com.itextpdf.io.image.ImageDataFactory.create(lg)).setHeight(34)); else cl.add(new Paragraph("BGFIBank").setFont(bold).setFontSize(14).setFontColor(NAVY));
            head.addCell(cl);
            head.addCell(new Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.RIGHT)
                    .add(new Paragraph("REPORTING DES ÉVALUATIONS").setFont(bold).setFontSize(15).setFontColor(NAVY).setMargin(0))
                    .add(new Paragraph(d.campagne().getLibelle() + " : " + d.campagne().getTypeLabel() + " " + d.campagne().getExercice()
                            + (d.campagne().getDateFin() == null ? "" : " · échéance " + d.campagne().getDateFin().format(D))).setFontSize(9.5f).setFontColor(GRIS).setMargin(0))
                    .add(new Paragraph("Périmètre : " + d.perimetre() + " · édité le " + DT.format(new Date())).setFontSize(8.5f).setFontColor(GRIS).setMargin(0)));
            doc.add(head);
            doc.add(new Paragraph().setBorderBottom(new SolidBorder(GOLD, 2)).setMarginTop(4).setMarginBottom(12));

            // Indicateurs clés
            ReportingView.Agregat t = d.total();
            Table k = new Table(UnitValue.createPercentArray(7)).useAllAvailableWidth().setMarginBottom(14);
            kpi(k, bold, String.valueOf(d.attendues()), "Évaluations attendues");
            kpi(k, bold, String.valueOf(t.getInitiees()), "Fiches initiées");
            kpi(k, bold, String.valueOf(t.getValidees()), "Fiches validées");
            kpi(k, bold, d.tauxGlobal() + " %", "Taux de réalisation");
            kpi(k, bold, t.getNoteMoyenne() == null ? "—" : String.format("%.1f /20", t.getNoteMoyenne()), "Note moyenne");
            kpi(k, bold, d.pctSeuilGlobal() + " %", "Objectifs ≥ " + d.seuil() + " % (" + t.getAtteintSeuil() + "/" + d.attendues() + ")");
            kpi(k, bold, t.getPctATemps() + " %", "Soumises à temps");
            doc.add(k);

            // Tableau par direction
            titre(doc, bold, "1. Taux de réalisation par direction");
            Table tb = new Table(UnitValue.createPercentArray(new float[]{26, 7, 7, 7, 7, 9, 11, 8, 6, 6, 6, 6})).useAllAvailableWidth();
            for (String h : new String[]{"Direction", "Effectif", "Initiées", "Évaluées", "Validées", "Réalisation", "≥ " + d.seuil() + " %", "Note moy.", "Atteints", "Moyen.", "Faibl.", "Non att."})
                tb.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(DeviceRgb.WHITE)).setBackgroundColor(NAVY).setBorder(new SolidBorder(NAVY, .5f)).setPadding(4));
            boolean alt = false;
            for (ReportingView.Agregat a : d.directions()) { ligneDir(tb, reg, bold, a, alt, false, a.getPctAtteinteSeuil(), a.getEffectif()); alt = !alt; }
            ligneDir(tb, reg, bold, t, false, true, d.pctSeuilGlobal(), d.attendues());
            doc.add(tb);

            // Répartitions côte à côte
            titre(doc, bold, "2. Statistiques consolidées");
            Table rep = new Table(UnitValue.createPercentArray(3)).useAllAvailableWidth();
            rep.addCell(repartition(reg, bold, "Niveaux d'atteinte", d.niveaux()));
            rep.addCell(repartition(reg, bold, "Appréciations (grille /20)", d.appreciations()));
            rep.addCell(repartition(reg, bold, "Souhaits des collaborateurs", d.souhaits()));
            doc.add(rep);

            // Formations
            titre(doc, bold, "3. Besoins de formation consolidés");
            if (d.formations().isEmpty()) doc.add(new Paragraph("Aucun besoin de formation exprimé sur les fiches de ce périmètre.").setFontColor(GRIS).setItalic());
            else {
                Table tf = new Table(UnitValue.createPercentArray(new float[]{34, 8, 8, 8, 8, 34})).useAllAvailableWidth();
                for (String h : new String[]{"Formation", "Demandes", "Haute", "Moyenne", "Basse", "Directions"})
                    tf.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(DeviceRgb.WHITE)).setBackgroundColor(NAVY).setBorder(new SolidBorder(NAVY, .5f)).setPadding(4));
                alt = false;
                for (ReportingView.Formation f : d.formations()) {
                    Color bg = alt ? FOND : DeviceRgb.WHITE; alt = !alt;
                    tf.addCell(c(f.getIntitule(), reg, bg, TextAlignment.LEFT)); tf.addCell(c(String.valueOf(f.getDemandes()), bold, bg, TextAlignment.RIGHT));
                    tf.addCell(c(String.valueOf(f.getHaute()), reg, bg, TextAlignment.RIGHT)); tf.addCell(c(String.valueOf(f.getMoyenne()), reg, bg, TextAlignment.RIGHT));
                    tf.addCell(c(String.valueOf(f.getBasse()), reg, bg, TextAlignment.RIGHT)); tf.addCell(c(f.getDirectionsTexte(), reg, bg, TextAlignment.LEFT));
                }
                doc.add(tf);
            }
            doc.add(new Paragraph("HRX - Direction du Capital Humain et de la Transformation · Document confidentiel, diffusion restreinte.").setFontSize(7.5f).setFontColor(GRIS).setMarginTop(14));
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PDF impossible : " + ex.getMessage(), ex); }
    }

    private static void kpi(Table k, PdfFont bold, String v, String l) {
        k.addCell(new Cell().setBorder(new SolidBorder(LIGNE, .8f)).setBackgroundColor(FOND).setPadding(8)
                .add(new Paragraph(v).setFont(bold).setFontSize(16).setFontColor(NAVY).setMargin(0))
                .add(new Paragraph(l).setFontSize(8).setFontColor(GRIS).setMargin(0)));
    }
    private static void titre(Document doc, PdfFont bold, String t) {
        doc.add(new Paragraph(t).setFont(bold).setFontSize(11).setFontColor(NAVY).setMarginTop(10).setMarginBottom(6));
    }
    private static Cell c(String v, PdfFont f, Color bg, TextAlignment al) {
        return new Cell().add(new Paragraph(v == null ? "" : v).setFont(f).setFontSize(8.5f).setMargin(0)).setBackgroundColor(bg).setTextAlignment(al)
                .setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LIGNE, .5f)).setPadding(4);
    }
    private static void ligneDir(Table tb, PdfFont reg, PdfFont bold, ReportingView.Agregat a, boolean alt, boolean tot, int pctSeuil, long base) {
        Color bg = tot ? new DeviceRgb(230, 236, 242) : alt ? FOND : DeviceRgb.WHITE;
        PdfFont f = tot ? bold : reg;
        tb.addCell(c(a.getNom(), f, bg, TextAlignment.LEFT));
        tb.addCell(c(String.valueOf(a.getEffectif()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getInitiees()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getEvaluees() + a.getValidees()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getValidees()), f, bg, TextAlignment.RIGHT));
        Cell taux = c(a.getTaux() + " %", bold, bg, TextAlignment.RIGHT);
        taux.setFontColor(a.getTaux() >= 80 ? VERT : a.getTaux() >= 50 ? BLEU : ROUGE);
        tb.addCell(taux);
        tb.addCell(c(pctSeuil + " % (" + a.getAtteintSeuil() + "/" + base + ")", bold, bg, TextAlignment.RIGHT));
        tb.addCell(c(a.getNoteMoyenne() == null ? "—" : String.format("%.1f", a.getNoteMoyenne()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getAtteints()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getMoyennement()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getFaiblement()), f, bg, TextAlignment.RIGHT));
        tb.addCell(c(String.valueOf(a.getNonAtteints()), f, bg, TextAlignment.RIGHT));
    }
    private static Cell repartition(PdfFont reg, PdfFont bold, String titre, List<ReportingView.Repartition> l) {
        Table t = new Table(UnitValue.createPercentArray(new float[]{60, 18, 22})).useAllAvailableWidth();
        t.addCell(new Cell(1, 3).add(new Paragraph(titre).setFont(bold).setFontSize(9).setFontColor(NAVY)).setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(GOLD, 1)).setPaddingBottom(3));
        for (ReportingView.Repartition r : l) {
            t.addCell(c(r.libelle(), reg, DeviceRgb.WHITE, TextAlignment.LEFT));
            t.addCell(c(String.valueOf(r.nb()), bold, DeviceRgb.WHITE, TextAlignment.RIGHT));
            t.addCell(c(r.pct() + " %", reg, DeviceRgb.WHITE, TextAlignment.RIGHT));
        }
        return new Cell().add(t).setBorder(Border.NO_BORDER).setPaddingRight(10);
    }
    private static byte[] logo() {
        try {
            FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Exception ignored) { }
        return null;
    }
}
