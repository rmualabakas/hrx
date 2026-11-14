package cd.mura.hrx.service;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.entity.eval.FicheObjectifs;
import cd.mura.hrx.entity.eval.NatureObjectif;
import cd.mura.hrx.entity.eval.Objectif;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Export de la fiche MAF 066 « Fiche de fixation des objectifs individuels », reproduction du modèle BGFIBank RDC */
@ApplicationScoped
public class FicheObjectifsExportService implements Serializable {

    // Palette du modèle Word BGFI
    private static final String NAVY = "17365D", RED = "C00000", ORANGE = "E36C09", HEAD = "D9D9D9", BAND = "C6D9F1", BAND2 = "B8CCE4",
            CYAN = "00B0F0", GREYBLUE = "95B3D7", FRAME = "808080", BLACK = "000000";
    private static final SimpleDateFormat DD = new SimpleDateFormat("dd/MM/yyyy");
    private static final String[][] GRILLE = {{"100%", "20", "Excellent"}, {"85 à 99%", "De 17 à 19", "Bon"}, {"70 à 84%", "De 15 à 16", "Moyennement Bon"}, {"51 à 69%", "De 10 à 14", "Insuffisant"}, {"Moins de 50%", "Moins de 10", "Mauvais"}};
    private static final String PIED = "MAF 066 DCHT 03 FICHE DE FIXATION DES OBJECTIFS INDIVIDUELS / DECEMBRE 2022";

    private static String s(String v) { return v == null ? "" : v; }
    private static String date(Date d) { return d == null ? "" : DD.format(d); }
    private static String anciennete(Users u) {
        if (u.getDateEmbauche() == null) return "";
        var d = u.getDateEmbauche().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        var p = java.time.Period.between(d, java.time.LocalDate.now());
        String a = p.getYears() > 0 ? p.getYears() + " année" + (p.getYears() > 1 ? "s" : "") : "", m = p.getMonths() > 0 ? p.getMonths() + " mois" : "";
        String r = a + (a.isEmpty() || m.isEmpty() ? "" : " et ") + m;
        return r.isEmpty() ? "moins d'un mois" : r;
    }
    static byte[] logo() {
        try {
            var ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Exception ignored) { }
        return null;
    }
    private static List<String> groupes(List<NatureObjectif> natures) { List<String> g = new ArrayList<>(); for (NatureObjectif n : natures) if (!g.contains(n.getGroupe())) g.add(n.getGroupe()); return g; }
    private static List<Objectif> de(List<Objectif> objectifs, NatureObjectif n) { return objectifs.stream().filter(o -> o.getNature() != null && o.getNature().getId() == n.getId() && !Objectif.REFUSE.equals(o.getStatut())).toList(); }
    /** Lignes de mission : une puce par ligne, comme sur le modèle. */
    private static List<String> missionLignes(FicheObjectifs f) {
        List<String> l = new ArrayList<>();
        for (String x : s(f.getMission()).split("\\R")) { String t = x.trim().replaceFirst("^[-•–]\\s*", ""); if (!t.isBlank()) l.add(t); }
        return l;
    }
    /** Regroupement des objectifs d'une rubrique par activité, dans l'ordre d'apparition. */
    private static List<String> activites(List<Objectif> l) { List<String> a = new ArrayList<>(); for (Objectif o : l) { String k = s(o.getActivite()).trim(); if (!a.contains(k)) a.add(k); } return a; }

    // PDF
    public byte[] pdf(FicheObjectifs fiche, Users u, List<Objectif> objectifs, List<NatureObjectif> natures) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(92, 40, 60, 40);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD),
                    ital = PdfFontFactory.createFont(StandardFonts.HELVETICA_OBLIQUE), boldItal = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLDOBLIQUE);
            Color navy = rgb(NAVY), red = rgb(RED), orange = rgb(ORANGE), head = rgb(HEAD), band = rgb(BAND), frame = rgb(FRAME), black = rgb(BLACK);
            doc.setFont(reg).setFontSize(8).setFontColor(navy);
            byte[] lg = logo();
            ImageData logoData = lg == null ? null : ImageDataFactory.create(lg);
            pdf.addEventHandler(PdfDocumentEvent.END_PAGE, new PageDecor(logoData, bold, reg, frame));

            // Cartouche de titre (à droite du logo, zone d'en-tête)
            Table tt = new Table(UnitValue.createPercentArray(new float[]{36, 64})).useAllAvailableWidth().setMarginTop(-48).setMarginBottom(16);
            tt.addCell(nb());
            Cell titre = new Cell().setBorder(new SolidBorder(black, 1.2f)).setBorderRadius(new com.itextpdf.layout.properties.BorderRadius(6)).setPadding(5).setTextAlignment(TextAlignment.CENTER);
            titre.add(new Paragraph("FICHE DE FIXATION DES OBJECTIFS INDIVIDUELS " + fiche.getExercice()).setFont(bold).setFontSize(10.5f).setFontColor(navy).setMargin(0));
            titre.add(new Paragraph("« Collaborateurs et Managers BGFIBank RDC SA »").setFont(bold).setFontSize(9.5f).setFontColor(navy).setMargin(0));
            tt.addCell(titre);
            doc.add(tt);

            // Identité
            Table id = new Table(UnitValue.createPercentArray(new float[]{52, 48})).useAllAvailableWidth().setMarginBottom(14);
            String[][] kv = {{"Nom et Prénom du salarié : ", s(u.getNom()).toUpperCase() + " " + s(u.getPrenom())}, {"Date d'embauche : ", date(u.getDateEmbauche())},
                    {"Poste occupé :  ", s(u.getFonction())}, {"Ancienneté dans le poste : ", anciennete(u)},
                    {"Direction : ", u.getDirection() == null ? "" : u.getDirection().getNom()}, {"Supérieur hiérarchique : ", u.getSuperieurHierarchique() == null ? "" : u.getSuperieurHierarchique().getNomComplet()}};
            for (String[] p : kv) id.addCell(new Cell().setBorder(new SolidBorder(frame, 0.75f)).setPadding(3).add(new Paragraph().setMargin(0).add(new Text(p[0]).setFont(bold).setFontSize(8)).add(new Text(p[1]).setFont(bold).setFontSize(8))));
            doc.add(id);

            // Mission
            Table mis = new Table(1).useAllAvailableWidth().setMarginBottom(8);
            mis.addCell(new Cell().setBorder(new SolidBorder(frame, 0.75f)).setBackgroundColor(head).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph("Mission :").setFont(bold).setFontSize(9).setMargin(0)));
            Cell mc = new Cell().setBorder(new SolidBorder(frame, 0.75f)).setPaddingLeft(26).setPaddingTop(6).setPaddingBottom(8);
            List<String> ml = missionLignes(fiche);
            if (ml.isEmpty()) mc.add(new Paragraph(" ").setMargin(0));
            for (String l : ml) mc.add(new Paragraph().setMargin(0).setMarginBottom(3).setFontSize(8.5f).add(new Text("-      ")).add(new Text(l)));
            mis.addCell(mc);
            doc.add(mis);

            doc.add(new Paragraph("T1= 1er trimestre, T2= 2e trimestre, T3 = 3e trimestre, T4 = 4e trimestre").setFont(ital).setFontSize(6.5f).setTextAlignment(TextAlignment.RIGHT).setMargin(0).setMarginBottom(2));

            // Tableau principal
            float[] w = {27, 23, 26, 4, 4, 4, 4};
            Table t = new Table(UnitValue.createPercentArray(w)).useAllAvailableWidth();
            SolidBorder b = new SolidBorder(frame, 0.75f);
            t.addHeaderCell(hcell(b, head, 1, 2).add(new Paragraph("OBJECTIFS").setFont(bold).setFontSize(8).setMargin(0)));
            t.addHeaderCell(hcell(b, head, 1, 2).add(new Paragraph("MOYENS").setFont(bold).setFontSize(8).setMargin(0)));
            t.addHeaderCell(hcell(b, head, 1, 2).add(new Paragraph("INDICATEURS").setFont(bold).setFontSize(8).setMargin(0)));
            t.addHeaderCell(hcell(b, head, 4, 1).add(new Paragraph("Période d'atteinte").setFont(bold).setFontSize(7.5f).setMargin(0)));
            for (String q : new String[]{"T1", "T2", "T3", "T4"}) t.addHeaderCell(hcell(b, head, 1, 1).add(new Paragraph(q).setFont(bold).setFontSize(7.5f).setMargin(0)));

            int gi = 0;
            String[] romains = {"I.", "II.", "III.", "IV.", "V.", "VI."};
            for (String g : groupes(natures)) {
                String gl = g.replaceFirst("^[IVX]+\\.\\s*", "");
                t.addCell(new Cell(1, 7).setBorder(b).setBackgroundColor(band).setPadding(6).setTextAlignment(TextAlignment.CENTER)
                        .add(new Paragraph().setMargin(0).add(new Text((gi < romains.length ? romains[gi] : "") + "          ").setFont(bold).setFontSize(9).setFontColor(navy)).add(new Text(gl.toUpperCase()).setFont(bold).setFontSize(9).setFontColor(red).setUnderline())));
                gi++;
                char lettre = 'A';
                for (NatureObjectif n : natures) {
                    if (!n.getGroupe().equals(g)) continue;
                    String nl = n.getLibelle().replaceFirst("^[A-Z]\\.\\s*", "");
                    Cell rc = new Cell(1, 7).setBorder(b).setBackgroundColor(band).setPadding(3).setTextAlignment(TextAlignment.CENTER);
                    rc.add(new Paragraph().setMargin(0).add(new Text((lettre++) + ".     ").setFont(bold).setFontSize(8).setFontColor(navy)).add(new Text(nl.toUpperCase()).setFont(bold).setFontSize(8).setFontColor(navy).setUnderline()));
                    String aide = s(n.getAide()).isBlank() ? s(n.getCondition()) : n.getAide();
                    if (!aide.isBlank()) rc.add(new Paragraph("(" + aide + ")").setFont(boldItal).setFontSize(7.5f).setFontColor(orange).setMargin(0));
                    t.addCell(rc);

                    List<Objectif> lst = de(objectifs, n);
                    if (lst.isEmpty()) {
                        // rubrique vide : lignes « Objectif 1- : » comme sur le modèle
                        for (int k = 1; k <= 2; k++) {
                            t.addCell(new Cell().setBorder(b).setPadding(4).setHeight(30).add(new Paragraph("Objectif " + k + "- :").setFont(bold).setFontSize(7.5f).setUnderline().setMargin(0)));
                            for (int c = 0; c < 6; c++) t.addCell(new Cell().setBorder(b));
                        }
                        continue;
                    }
                    int num = 1, ai = 1;
                    for (String act : activites(lst)) {
                        boolean first = true;
                        for (Objectif o : lst) {
                            if (!s(o.getActivite()).trim().equals(act)) continue;
                            Paragraph op = new Paragraph().setMargin(0).setMultipliedLeading(1.2f).setFontSize(7.5f);
                            if (first && !act.isBlank()) { op.add(new Text("Activité " + ai++ + " :  " + act + "\n\n").setFont(bold)); }
                            first = false;
                            op.add(new Text("Objectif " + num++ + " : ").setFont(bold)).add(new Text(s(o.getLibelle())));
                            if (o.isPropose()) op.add(new Text("\n(proposé par le collaborateur - à valider)").setFont(ital).setFontSize(6.5f).setFontColor(orange));
                            t.addCell(new Cell().setBorder(b).setPadding(4).add(op));
                            t.addCell(new Cell().setBorder(b).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(s(o.getMoyens())).setFontSize(7.5f).setMargin(0).setMultipliedLeading(1.2f)));
                            String ind = s(o.getIndicateur()) + (s(o.getCible()).isBlank() ? "" : (s(o.getIndicateur()).isBlank() ? "" : "\n") + "Cible : " + o.getCible());
                            t.addCell(new Cell().setBorder(b).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(ind).setFontSize(7.5f).setMargin(0).setMultipliedLeading(1.2f)));
                            for (boolean x : new boolean[]{o.isT1(), o.isT2(), o.isT3(), o.isT4()})
                                t.addCell(new Cell().setBorder(b).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE).add(new Paragraph(x ? "X" : "").setFontSize(8).setMargin(0)));
                        }
                    }
                }
            }
            doc.add(t);

            // Signatures
            Table sg = new Table(UnitValue.createPercentArray(new float[]{33, 25, 42})).useAllAvailableWidth().setMarginTop(22).setKeepTogether(true);
            String[] sh = {"Date", "Signature du salarié", "Signature du N+1"};
            String[] sv = {fiche.isSignee() ? date(fiche.getDateSignatureN1()) : "", fiche.isSigneeSalarie() ? "Signé électroniquement le " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fiche.getDateSignatureSalarie()) + "\n" + u.getNomComplet() : "",
                    fiche.isSigneeN1() ? "Signé électroniquement le " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fiche.getDateSignatureN1()) + "\n" + fiche.getSignataireN1Nom() + (fiche.isSigneeParAutreQueN1() ? "\n(pour le N+1 " + fiche.getN1().getNomComplet() + ")" : "") : ""};
            for (int i = 0; i < 3; i++) {
                Cell c = new Cell().setBorder(b).setHeight(110).setPadding(4).setTextAlignment(TextAlignment.CENTER);
                c.add(new Paragraph(sh[i]).setFont(bold).setFontSize(8).setUnderline().setMargin(0).setMarginBottom(14));
                if (!sv[i].isBlank()) c.add(new Paragraph(sv[i]).setFont(ital).setFontSize(7.5f).setMargin(0));
                sg.addCell(c);
            }
            doc.add(sg);

            // Grille de notation
            Table gr = new Table(UnitValue.createPercentArray(new float[]{25, 22, 53})).useAllAvailableWidth().setMarginTop(22).setKeepTogether(true);
            gr.addCell(new Cell(1, 3).setBorder(b).setPadding(4).add(new Table(1).useAllAvailableWidth().addCell(new Cell().setBorder(Border.NO_BORDER).setBackgroundColor(rgb(CYAN)).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph("GRILLE DE NOTATION DU NIVEAU DE PERFORMANCE").setFont(bold).setFontSize(9).setFontColor(ColorConstants.WHITE).setMargin(0)))));
            for (String h : new String[]{"Niveau de performance", "Note sur 20", "Appréciation"}) gr.addCell(new Cell().setBorder(b).setBackgroundColor(rgb(GREYBLUE)).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(h).setFont(bold).setFontSize(8).setMargin(0)));
            for (String[] r : GRILLE) for (String v : r) gr.addCell(new Cell().setBorder(b).setPadding(3).setTextAlignment(TextAlignment.CENTER).add(new Paragraph(v).setFontSize(8).setMargin(0)));
            doc.add(gr);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Export PDF impossible : " + e.getMessage(), e); }
    }

    /** Cadre de page, logo + direction en haut à gauche, pied MAF 066 + numéro de page, sur chaque page. */
    static final class PageDecor implements IEventHandler {
        private final ImageData logo; private final PdfFont bold, reg; private final Color frame; private final String pied, direction;
        PageDecor(ImageData logo, PdfFont bold, PdfFont reg, Color frame) { this(logo, bold, reg, frame, PIED, "Direction du Capital Humain et de la Transformation"); }
        PageDecor(ImageData logo, PdfFont bold, PdfFont reg, Color frame, String pied, String direction) { this.logo = logo; this.bold = bold; this.reg = reg; this.frame = frame; this.pied = pied; this.direction = direction; }
        @Override public void handleEvent(Event event) {
            PdfDocumentEvent ev = (PdfDocumentEvent) event; PdfPage page = ev.getPage(); PdfDocument pdf = ev.getDocument();
            Rectangle ps = page.getPageSize();
            PdfCanvas pc = new PdfCanvas(page.newContentStreamBefore(), page.getResources(), pdf);
            pc.saveState().setStrokeColor(frame).setLineWidth(0.8f).rectangle(ps.getLeft() + 18, ps.getBottom() + 18, ps.getWidth() - 36, ps.getHeight() - 36).stroke();
            pc.setStrokeColor(frame).setLineWidth(0.4f).rectangle(ps.getLeft() + 20, ps.getBottom() + 20, ps.getWidth() - 40, ps.getHeight() - 40).stroke().restoreState();
            try (Canvas cv = new Canvas(pc, ps)) {
                if (logo != null) { Image im = new Image(logo).setHeight(34); im.setFixedPosition(ps.getLeft() + 44, ps.getTop() - 72); cv.add(im); }
                else cv.showTextAligned(new Paragraph("BGFIBank").setFont(bold).setFontSize(14).setFontColor(rgb(NAVY)), ps.getLeft() + 44, ps.getTop() - 62, TextAlignment.LEFT);
                cv.showTextAligned(new Paragraph(direction).setFont(reg).setFontSize(6.5f).setFontColor(ColorConstants.BLACK), ps.getLeft() + 46, ps.getTop() - 82, TextAlignment.LEFT);
                cv.showTextAligned(new Paragraph(pied).setFont(bold).setFontSize(pied.length() > 80 ? 6.5f : 8).setFontColor(ColorConstants.BLACK), ps.getWidth() / 2 - 20, ps.getBottom() + 34, TextAlignment.CENTER);
                cv.showTextAligned(new Paragraph(String.valueOf(pdf.getPageNumber(page))).setFont(reg).setFontSize(10).setFontColor(rgb("0070C0")), ps.getRight() - 56, ps.getBottom() + 34, TextAlignment.RIGHT);
            }
        }
    }

    static Cell hcell(SolidBorder b, Color bg, int colspan, int rowspan) { return new Cell(rowspan, colspan).setBorder(b).setBackgroundColor(bg).setPadding(4).setTextAlignment(TextAlignment.CENTER).setVerticalAlignment(VerticalAlignment.MIDDLE); }
    static Cell nb() { return new Cell().setBorder(Border.NO_BORDER); }
    static Color rgb(String hex) { return new DeviceRgb(Integer.valueOf(hex.substring(0, 2), 16), Integer.valueOf(hex.substring(2, 4), 16), Integer.valueOf(hex.substring(4, 6), 16)); }

    // Word
    public byte[] docx(FicheObjectifs fiche, Users u, List<Objectif> objectifs, List<NatureObjectif> natures) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().addNewSectPr();
            CTPageSz sz = sect.addNewPgSz(); sz.setW(BigInteger.valueOf(11906)); sz.setH(BigInteger.valueOf(16838));
            CTPageMar mar = sect.addNewPgMar(); mar.setLeft(BigInteger.valueOf(800)); mar.setRight(BigInteger.valueOf(800)); mar.setTop(BigInteger.valueOf(700)); mar.setBottom(BigInteger.valueOf(700)); mar.setHeader(BigInteger.valueOf(400)); mar.setFooter(BigInteger.valueOf(400));
            // Bordure de page
            CTPageBorders pb = sect.addNewPgBorders(); pb.setOffsetFrom(STPageBorderOffset.PAGE);
            for (CTBorder x : new CTBorder[]{pb.addNewTop(), pb.addNewBottom(), pb.addNewLeft(), pb.addNewRight()}) { x.setVal(STBorder.DOUBLE); x.setSz(BigInteger.valueOf(6)); x.setSpace(BigInteger.valueOf(24)); x.setColor(FRAME); }
            int W = 10306;

            // En-tête : logo + direction (répété chaque page)
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFParagraph hp = header.createParagraph(); hp.setSpacingAfter(0);
            byte[] lg = logo();
            if (lg != null) hp.createRun().addPicture(new java.io.ByteArrayInputStream(lg), XWPFDocument.PICTURE_TYPE_PNG, "logo.png", Units.toEMU(95), Units.toEMU(32)); else run(hp, "BGFIBank", true, 14, NAVY);
            XWPFParagraph hp2 = header.createParagraph(); run(hp2, "Direction du Capital Humain et de la Transformation", false, 6.5, BLACK);
            // Pied : MAF 066 + page
            XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
            XWPFParagraph fp = footer.createParagraph(); fp.setAlignment(ParagraphAlignment.CENTER);
            run(fp, PIED + "      ", true, 8, BLACK);
            CTSimpleField fld = fp.getCTP().addNewFldSimple(); fld.setInstr(" PAGE ");
            CTR fr = fld.addNewR(); CTRPr frp = fr.addNewRPr(); frp.addNewColor().setVal("0070C0"); frp.addNewSz().setVal(BigInteger.valueOf(20)); fr.addNewT().setStringValue("1");

            // Cartouche titre à droite
            XWPFTable tt = doc.createTable(1, 2); noBorders(tt); widths(tt, 3700, 6606);
            XWPFTableCell tc = tt.getRow(0).getCell(1); borders(tc, 12, BLACK);
            XWPFParagraph t1 = tc.getParagraphs().get(0); t1.setAlignment(ParagraphAlignment.CENTER); t1.setSpacingBefore(80); run(t1, "FICHE DE FIXATION DES OBJECTIFS INDIVIDUELS " + fiche.getExercice(), true, 10.5, NAVY);
            XWPFParagraph t2 = tc.addParagraph(); t2.setAlignment(ParagraphAlignment.CENTER); t2.setSpacingAfter(80); run(t2, "« Collaborateurs et Managers BGFIBank RDC SA »", true, 9.5, NAVY);
            spacer(doc, 120);

            // Identité
            XWPFTable id = doc.createTable(3, 2); thinBorders(id, FRAME); widths(id, 5360, 4946);
            String[][] kv = {{"Nom et Prénom du salarié : ", s(u.getNom()).toUpperCase() + " " + s(u.getPrenom())}, {"Date d'embauche : ", date(u.getDateEmbauche())},
                    {"Poste occupé :  ", s(u.getFonction())}, {"Ancienneté dans le poste : ", anciennete(u)},
                    {"Direction : ", u.getDirection() == null ? "" : u.getDirection().getNom()}, {"Supérieur hiérarchique : ", u.getSuperieurHierarchique() == null ? "" : u.getSuperieurHierarchique().getNomComplet()}};
            for (int i = 0; i < 6; i++) { XWPFParagraph p = id.getRow(i / 2).getCell(i % 2).getParagraphs().get(0); run(p, kv[i][0], true, 8, NAVY); run(p, kv[i][1], true, 8, NAVY); }
            spacer(doc, 200);

            // Mission
            XWPFTable mis = doc.createTable(2, 1); thinBorders(mis, FRAME); widths(mis, W);
            mis.getRow(0).getCell(0).setColor(HEAD);
            XWPFParagraph mp = mis.getRow(0).getCell(0).getParagraphs().get(0); mp.setAlignment(ParagraphAlignment.CENTER); run(mp, "Mission :", true, 9, BLACK);
            XWPFTableCell mc = mis.getRow(1).getCell(0);
            List<String> ml = missionLignes(fiche);
            boolean firstM = true;
            for (String l : ml) { XWPFParagraph p = firstM ? mc.getParagraphs().get(0) : mc.addParagraph(); firstM = false; p.setIndentationLeft(560); p.setIndentationHanging(360); p.setSpacingBefore(40); p.setSpacingAfter(40); run(p, "-\t" + l, false, 8.5, NAVY); }
            if (ml.isEmpty()) run(mc.getParagraphs().get(0), " ", false, 8.5, NAVY);
            spacer(doc, 160);
            XWPFParagraph lp = doc.createParagraph(); lp.setAlignment(ParagraphAlignment.RIGHT); lp.setSpacingAfter(20);
            XWPFRun lr = run(lp, "T1= 1er trimestre, T2= 2e trimestre, T3 = 3e trimestre, T4 = 4e trimestre", false, 6.5, BLACK); lr.setItalic(true);

            // Tableau principal
            int[] w = {2780, 2370, 2680, 620, 620, 620, 616};
            XWPFTable t = doc.createTable(2, 7); thinBorders(t, FRAME); widths(t, w);
            XWPFTableRow h0 = t.getRow(0), h1 = t.getRow(1);
            for (XWPFTableRow r : new XWPFTableRow[]{h0, h1}) { r.setRepeatHeader(true); for (XWPFTableCell c : r.getTableCells()) c.setColor(HEAD); }
            String[] hd = {"OBJECTIFS", "MOYENS", "INDICATEURS"};
            for (int i = 0; i < 3; i++) { vmerge(h0.getCell(i), true); vmerge(h1.getCell(i), false); centerRun(h0.getCell(i), hd[i], true, 8, BLACK); }
            mergeRange(h0, 3, 6); centerRun(h0.getCell(3), "Période d'atteinte", true, 7.5, BLACK);
            String[] q = {"T1", "T2", "T3", "T4"}; for (int i = 0; i < 4; i++) centerRun(h1.getCell(3 + i), q[i], true, 7.5, BLACK);

            int gi = 0; String[] romains = {"I.", "II.", "III.", "IV.", "V.", "VI."};
            for (String g : groupes(natures)) {
                String gl = g.replaceFirst("^[IVX]+\\.\\s*", "");
                XWPFTableRow gr = t.createRow(); mergeRange(gr, 0, 6); gr.getCell(0).setColor(BAND); gr.setHeight(420);
                XWPFParagraph gp = gr.getCell(0).getParagraphs().get(0); gp.setAlignment(ParagraphAlignment.CENTER);
                run(gp, (gi < romains.length ? romains[gi] : "") + "          ", true, 9, NAVY); XWPFRun gu = run(gp, gl.toUpperCase(), true, 9, RED); gu.setUnderline(UnderlinePatterns.SINGLE);
                gi++;
                char lettre = 'A';
                for (NatureObjectif n : natures) {
                    if (!n.getGroupe().equals(g)) continue;
                    String nl = n.getLibelle().replaceFirst("^[A-Z]\\.\\s*", "");
                    XWPFTableRow nr = t.createRow(); mergeRange(nr, 0, 6); nr.getCell(0).setColor(BAND);
                    XWPFParagraph np = nr.getCell(0).getParagraphs().get(0); np.setAlignment(ParagraphAlignment.CENTER);
                    run(np, (lettre++) + ".     ", true, 8, NAVY); XWPFRun nu = run(np, nl.toUpperCase(), true, 8, NAVY); nu.setUnderline(UnderlinePatterns.SINGLE);
                    String aide = s(n.getAide()).isBlank() ? s(n.getCondition()) : n.getAide();
                    if (!aide.isBlank()) { XWPFParagraph ap = nr.getCell(0).addParagraph(); ap.setAlignment(ParagraphAlignment.CENTER); XWPFRun ar = run(ap, "(" + aide + ")", true, 7.5, ORANGE); ar.setItalic(true); }

                    List<Objectif> lst = de(objectifs, n);
                    if (lst.isEmpty()) {
                        for (int k = 1; k <= 2; k++) { XWPFTableRow er = t.createRow(); er.setHeight(600); XWPFRun eu = run(er.getCell(0).getParagraphs().get(0), "Objectif " + k + "- :", true, 7.5, NAVY); eu.setUnderline(UnderlinePatterns.SINGLE); }
                        continue;
                    }
                    int num = 1, ai = 1;
                    for (String act : activites(lst)) {
                        boolean first = true;
                        for (Objectif o : lst) {
                            if (!s(o.getActivite()).trim().equals(act)) continue;
                            XWPFTableRow r = t.createRow();
                            XWPFParagraph op = r.getCell(0).getParagraphs().get(0);
                            if (first && !act.isBlank()) { run(op, "Activité " + ai++ + " :  " + act, true, 7.5, NAVY); op = r.getCell(0).addParagraph(); op.setSpacingBefore(120); }
                            first = false;
                            run(op, "Objectif " + num++ + " : ", true, 7.5, NAVY); run(op, s(o.getLibelle()), false, 7.5, NAVY);
                            if (o.isPropose()) { XWPFRun pr = run(r.getCell(0).addParagraph(), "(proposé par le collaborateur - à valider)", false, 6.5, ORANGE); pr.setItalic(true); }
                            vcenter(r.getCell(1)); run(r.getCell(1).getParagraphs().get(0), s(o.getMoyens()), false, 7.5, NAVY);
                            vcenter(r.getCell(2)); run(r.getCell(2).getParagraphs().get(0), s(o.getIndicateur()), false, 7.5, NAVY);
                            if (!s(o.getCible()).isBlank()) run(r.getCell(2).addParagraph(), "Cible : " + o.getCible(), false, 7.5, NAVY);
                            boolean[] qs = {o.isT1(), o.isT2(), o.isT3(), o.isT4()};
                            for (int i = 0; i < 4; i++) { vcenter(r.getCell(3 + i)); centerRun(r.getCell(3 + i), qs[i] ? "X" : "", false, 8, NAVY); }
                        }
                    }
                }
            }
            spacer(doc, 360);

            // Signatures
            XWPFTable sg = doc.createTable(1, 3); thinBorders(sg, FRAME); widths(sg, 3400, 2600, 4306); sg.getRow(0).setHeight(2300);
            String[] sh = {"Date", "Signature du salarié", "Signature du N+1"};
            String[] sv = {fiche.isSignee() ? date(fiche.getDateSignatureN1()) : "", fiche.isSigneeSalarie() ? "Signé électroniquement le " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fiche.getDateSignatureSalarie()) + " : " + u.getNomComplet() : "",
                    fiche.isSigneeN1() ? "Signé électroniquement le " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(fiche.getDateSignatureN1()) + " : " + fiche.getSignataireN1Nom() + (fiche.isSigneeParAutreQueN1() ? " (pour le N+1 " + fiche.getN1().getNomComplet() + ")" : "") : ""};
            for (int i = 0; i < 3; i++) {
                XWPFTableCell c = sg.getRow(0).getCell(i);
                XWPFParagraph p = c.getParagraphs().get(0); p.setAlignment(ParagraphAlignment.CENTER); XWPFRun r = run(p, sh[i], true, 8, NAVY); r.setUnderline(UnderlinePatterns.SINGLE);
                if (!sv[i].isBlank()) { XWPFParagraph p2 = c.addParagraph(); p2.setAlignment(ParagraphAlignment.CENTER); p2.setSpacingBefore(300); XWPFRun r2 = run(p2, sv[i], false, 7.5, NAVY); r2.setItalic(true); }
            }
            spacer(doc, 360);

            // Grille
            XWPFTable gr = doc.createTable(7, 3); thinBorders(gr, FRAME); widths(gr, 2600, 2300, 5406);
            XWPFTableRow g0 = gr.getRow(0); mergeRange(g0, 0, 2); g0.getCell(0).setColor(CYAN);
            XWPFParagraph gp0 = g0.getCell(0).getParagraphs().get(0); gp0.setAlignment(ParagraphAlignment.CENTER); run(gp0, "GRILLE DE NOTATION DU NIVEAU DE PERFORMANCE", true, 9, "FFFFFF");
            String[] gh = {"Niveau de performance", "Note sur 20", "Appréciation"};
            for (int k = 0; k < 3; k++) { gr.getRow(1).getCell(k).setColor(GREYBLUE); centerRun(gr.getRow(1).getCell(k), gh[k], true, 8, NAVY); }
            for (int i = 0; i < GRILLE.length; i++) for (int k = 0; k < 3; k++) centerRun(gr.getRow(2 + i).getCell(k), GRILLE[i][k], false, 8, NAVY);

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Export Word impossible : " + e.getMessage(), e); }
    }

    static XWPFRun run(XWPFParagraph p, String text, boolean bold, double size, String color) {
        if (p.getSpacingAfter() < 0 || p.getSpacingAfter() == 0) p.setSpacingAfter(0);
        XWPFRun r = p.createRun(); r.setText(text); r.setBold(bold); r.setFontSize(size); r.setColor(color); r.setFontFamily("Arial"); return r;
    }
    static void centerRun(XWPFTableCell c, String text, boolean bold, double size, String color) { XWPFParagraph p = c.getParagraphs().get(0); p.setAlignment(ParagraphAlignment.CENTER); run(p, text, bold, size, color); }
    static void vcenter(XWPFTableCell c) { c.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER); }
    static void spacer(XWPFDocument doc, int twips) { XWPFParagraph p = doc.createParagraph(); p.setSpacingAfter(twips); p.setSpacingBefore(0); }
    static void widths(XWPFTable t, int... w) {
        t.setWidth("100%");
        CTTblGrid grid = t.getCTTbl().getTblGrid() == null ? t.getCTTbl().addNewTblGrid() : t.getCTTbl().getTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int x : w) grid.addNewGridCol().setW(BigInteger.valueOf(x));
        for (XWPFTableRow r : t.getRows()) for (int i = 0; i < r.getTableCells().size() && i < w.length; i++) r.getCell(i).setWidth(String.valueOf(w[i]));
    }
    static void noBorders(XWPFTable t) {
        t.setTopBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setBottomBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setLeftBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setRightBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setInsideHBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF"); t.setInsideVBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
    }
    static void thinBorders(XWPFTable t, String color) {
        t.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color);
        t.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color);
        t.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color); t.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 6, 0, color);
    }
    static void borders(XWPFTableCell c, int sz, String color) {
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        CTTcBorders b = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
        for (CTBorder x : new CTBorder[]{b.addNewTop(), b.addNewBottom(), b.addNewLeft(), b.addNewRight()}) { x.setVal(STBorder.SINGLE); x.setSz(BigInteger.valueOf(sz)); x.setColor(color); }
    }
    static void mergeRange(XWPFTableRow r, int from, int to) {
        for (int i = from; i <= to; i++) {
            while (r.getTableCells().size() <= i) r.addNewTableCell();
            CTTcPr pr = r.getCell(i).getCTTc().isSetTcPr() ? r.getCell(i).getCTTc().getTcPr() : r.getCell(i).getCTTc().addNewTcPr();
            CTHMerge hm = pr.isSetHMerge() ? pr.getHMerge() : pr.addNewHMerge(); hm.setVal(i == from ? STMerge.RESTART : STMerge.CONTINUE);
        }
    }
    static void vmerge(XWPFTableCell c, boolean restart) {
        CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
        CTVMerge vm = pr.isSetVMerge() ? pr.getVMerge() : pr.addNewVMerge(); vm.setVal(restart ? STMerge.RESTART : STMerge.CONTINUE);
    }
}
