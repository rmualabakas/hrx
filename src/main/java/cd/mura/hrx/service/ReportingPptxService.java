package cd.mura.hrx.service;

import cd.mura.hrx.bean.eval.ReportingView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.*;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/** Présentation PowerPoint (16:9) du reporting des évaluations, aux couleurs BGFI. */
@ApplicationScoped
public class ReportingPptxService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Color NAVY = new Color(7, 41, 74), BLEU = new Color(10, 92, 143), FILET = new Color(143, 163, 116),
            GRIS = new Color(107, 114, 128), TEXTE = new Color(25, 32, 45), LIGNE = new Color(220, 226, 232), FOND = new Color(244, 247, 250),
            PISTE = new Color(229, 233, 238), VERT = new Color(46, 125, 80), ORANGE = new Color(196, 132, 29), ROUGE = new Color(185, 56, 56),
            BLANC = Color.WHITE;
    private static final String POLICE = "Calibri";
    private static final double W = 960, H = 540, MX = 40;
    private static final int LIGNES_DIR = 10, LIGNES_FORM = 11;

    public byte[] pptx(ReportingExportService.Donnees d) { return pptx(d, logo()); }

    public byte[] pptx(ReportingExportService.Donnees d, byte[] logo) {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ppt.setPageSize(new Dimension((int) W, (int) H));
            XSLFPictureData pic = logo == null ? null : ppt.addPicture(logo, PictureData.PictureType.PNG);
            String sousTitre = intitule(d) + " · " + d.perimetre();
            int[] page = {1};

            couverture(ppt, d, pic);
            indicateurs(ppt, d, pic, sousTitre, ++page[0]);
            pointsCles(ppt, d, pic, sousTitre, ++page[0]);

            List<ReportingView.Agregat> dirs = d.directions();
            for (int i = 0; i < Math.max(1, dirs.size()); i += LIGNES_DIR) {
                boolean dernier = i + LIGNES_DIR >= dirs.size();
                tableauDirections(ppt, d, pic, sousTitre, ++page[0], dirs.subList(Math.min(i, dirs.size()), Math.min(i + LIGNES_DIR, dirs.size())), dernier,
                        dirs.size() > LIGNES_DIR ? " (" + (i / LIGNES_DIR + 1) + "/" + ((dirs.size() + LIGNES_DIR - 1) / LIGNES_DIR) + ")" : "");
            }
            for (int i = 0; i < Math.max(1, dirs.size()); i += LIGNES_DIR)
                seuilParDirection(ppt, d, pic, sousTitre, ++page[0], dirs.subList(Math.min(i, dirs.size()), Math.min(i + LIGNES_DIR, dirs.size())), i + LIGNES_DIR >= dirs.size());
            statistiques(ppt, d, pic, sousTitre, ++page[0]);
            List<ReportingView.Formation> fs = d.formations();
            for (int i = 0; i < Math.max(1, fs.size()); i += LIGNES_FORM)
                formations(ppt, d, pic, sousTitre, ++page[0], fs.subList(Math.min(i, fs.size()), Math.min(i + LIGNES_FORM, fs.size())),
                        fs.size() > LIGNES_FORM ? " (" + (i / LIGNES_FORM + 1) + "/" + ((fs.size() + LIGNES_FORM - 1) / LIGNES_FORM) + ")" : "");

            ppt.write(out);
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PowerPoint impossible : " + ex.getMessage(), ex); }
    }

    // --- diapositives

    private void couverture(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic) {
        XSLFSlide s = ppt.createSlide();
        rect(s, 0, 0, W, H, BLANC);
        rect(s, 0, 0, 330, H, NAVY);
        rect(s, 330, 0, 8, H, FILET);
        if (pic != null) { XSLFPictureShape p = s.createPicture(pic); p.setAnchor(new Rectangle2D.Double(395, 70, 150, 86)); }
        texte(s, 395, 190, 520, 50, "Reporting des évaluations", 32, true, NAVY, TextParagraph.TextAlign.LEFT);
        texte(s, 395, 245, 520, 30, intitule(d), 18, false, BLEU, TextParagraph.TextAlign.LEFT);
        rect(s, 395, 290, 80, 3, FILET);
        String ech = d.campagne().getDateFin() == null ? "" : "Échéance : " + d.campagne().getDateFin().format(D) + "\n";
        texte(s, 395, 305, 520, 80, ech + "Périmètre : " + d.perimetre() + "\nÉdité le " + new SimpleDateFormat("dd/MM/yyyy").format(new Date()), 13, false, GRIS, TextParagraph.TextAlign.LEFT);
        texte(s, 30, 70, 270, 40, "HRX", 30, true, BLANC, TextParagraph.TextAlign.LEFT);
        texte(s, 30, 112, 270, 60, "Plateforme collaborative RH", 13, false, new Color(190, 205, 220), TextParagraph.TextAlign.LEFT);
        texte(s, 30, H - 90, 270, 60, "Direction du Capital Humain\net de la Transformation", 12, false, new Color(190, 205, 220), TextParagraph.TextAlign.LEFT);
        texte(s, 395, H - 50, 520, 24, "Document confidentiel · diffusion restreinte", 10, false, GRIS, TextParagraph.TextAlign.LEFT);
    }

    private void indicateurs(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page) {
        XSLFSlide s = gabarit(ppt, pic, "Indicateurs clés", st, page);
        ReportingView.Agregat t = d.total();
        String[][] k = {
                {String.valueOf(d.attendues()), "Évaluations attendues", "effectif évaluable"},
                {String.valueOf(t.getInitiees()), "Fiches initiées", t.getTauxIntegration() + " % de l'effectif"},
                {String.valueOf(t.getEvaluees() + t.getValidees()), "Évaluées par le N+1", t.getValidees() + " validée(s) par la DCHT"},
                {d.tauxGlobal() + " %", "Taux de réalisation", "validées / attendues"},
                {d.pctSeuilGlobal() + " %", "Objectifs ≥ " + d.seuil() + " %", t.getAtteintSeuil() + " sur " + d.attendues() + " évaluables"},
                {t.getNoteMoyenne() == null ? "—" : String.format("%.1f /20", t.getNoteMoyenne()), "Note moyenne", t.getNotees() + " fiche(s) notée(s)"},
                {t.getNbDatees() == 0 ? "—" : t.getPctATemps() + " %", "Soumises à temps", t.getHorsDelai() + " hors délai"},
                {String.valueOf(d.formations().stream().mapToLong(ReportingView.Formation::getDemandes).sum()), "Besoins de formation", d.formations().size() + " formation(s) distincte(s)"}};
        double gw = (W - 2 * MX - 3 * 16) / 4, gh = 150;
        for (int i = 0; i < k.length; i++) {
            double x = MX + (i % 4) * (gw + 16), y = 110 + (i / 4) * (gh + 20);
            boolean fort = i == 3 || i == 4;
            XSLFAutoShape c = rect(s, x, y, gw, gh, fort ? NAVY : FOND);
            c.setLineColor(fort ? NAVY : LIGNE); c.setLineWidth(.75);
            rect(s, x, y, 5, gh, fort ? FILET : BLEU);
            texte(s, x + 18, y + 20, gw - 30, 50, k[i][0], 30, true, fort ? BLANC : NAVY, TextParagraph.TextAlign.LEFT);
            texte(s, x + 18, y + 78, gw - 30, 24, k[i][1], 13, true, fort ? BLANC : TEXTE, TextParagraph.TextAlign.LEFT);
            texte(s, x + 18, y + 104, gw - 30, 36, k[i][2], 10.5, false, fort ? new Color(190, 205, 220) : GRIS, TextParagraph.TextAlign.LEFT);
        }
    }

    private void tableauDirections(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page,
                                   List<ReportingView.Agregat> dirs, boolean avecTotal, String suite) {
        XSLFSlide s = gabarit(ppt, pic, "Taux de réalisation par direction" + suite, st, page);
        String[] h = {"Direction", "Effectif", "Initiées", "Évaluées", "Validées", "Réalisation", "≥ " + d.seuil() + " %", "Note moy.", "À temps"};
        double[] w = {270, 64, 64, 66, 66, 80, 104, 70, 70};
        int n = dirs.size() + 1 + (avecTotal ? 1 : 0);
        XSLFTable tb = s.createTable(n, h.length);
        tb.setAnchor(new Rectangle2D.Double(MX, 100, W - 2 * MX, 30.0 * n));
        for (int c = 0; c < h.length; c++) { tb.setColumnWidth(c, w[c]); entete(tb.getCell(0, c), h[c], c == 0); }
        int r = 1;
        for (ReportingView.Agregat a : dirs) ligneDir(tb, r++, a, a.getPctAtteinteSeuil(), a.getEffectif(), r % 2 == 0, false);
        if (avecTotal) ligneDir(tb, r, d.total(), d.pctSeuilGlobal(), d.attendues(), false, true);
        for (int i = 0; i < n; i++) tb.setRowHeight(i, 30);
    }

    private void ligneDir(XSLFTable tb, int r, ReportingView.Agregat a, int pctSeuil, long base, boolean alt, boolean tot) {
        Color bg = tot ? new Color(230, 236, 242) : alt ? FOND : BLANC;
        String[] v = {a.getNom(), String.valueOf(a.getEffectif()), String.valueOf(a.getInitiees()), String.valueOf(a.getEvaluees() + a.getValidees()),
                String.valueOf(a.getValidees()), a.getTaux() + " %", pctSeuil + " % (" + a.getAtteintSeuil() + "/" + base + ")",
                a.getNoteMoyenne() == null ? "—" : String.format("%.1f", a.getNoteMoyenne()), a.getNbDatees() == 0 ? "—" : a.getPctATemps() + " %"};
        for (int c = 0; c < v.length; c++) {
            Color col = c == 5 ? couleurTaux(a.getTaux()) : c == 6 ? couleurTaux(pctSeuil) : TEXTE;
            cellule(tb.getCell(r, c), v[c], bg, tot || c == 5 || c == 6, col, c == 0 ? TextParagraph.TextAlign.LEFT : TextParagraph.TextAlign.RIGHT, 11);
        }
    }

    private void seuilParDirection(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page,
                                   List<ReportingView.Agregat> dirs, boolean avecTotal) {
        XSLFSlide s = gabarit(ppt, pic, "Objectifs atteints à " + d.seuil() + " % ou plus", st, page);
        texte(s, MX, 92, W - 2 * MX, 22, "Part des collaborateurs évaluables dont le taux de réalisation des objectifs est d'au moins " + d.seuil()
                + " % (base : effectif à évaluer de chaque direction).", 11, false, GRIS, TextParagraph.TextAlign.LEFT);
        double y = 128, lh = 32, lx = MX, lw = 290, bx = lx + lw + 10, bw = W - 2 * MX - lw - 10 - 150;
        java.util.List<Object[]> lignes = new java.util.ArrayList<>();
        for (ReportingView.Agregat a : dirs) lignes.add(new Object[]{a.getNom(), a.getPctAtteinteSeuil(), a.getAtteintSeuil(), a.getEffectif(), a.getPctSeuilEvalues(), false});
        if (avecTotal) lignes.add(new Object[]{"Ensemble du périmètre", d.pctSeuilGlobal(), d.total().getAtteintSeuil(), d.attendues(), d.total().getPctSeuilEvalues(), true});
        for (Object[] l : lignes) {
            boolean tot = (Boolean) l[5]; int pct = (Integer) l[1];
            if (tot) { rect(s, MX, y - 4, W - 2 * MX, 1, LIGNE); y += 6; }
            texte(s, lx, y + 2, lw, lh - 6, (String) l[0], 11.5, tot, tot ? NAVY : TEXTE, TextParagraph.TextAlign.LEFT);
            rect(s, bx, y + 7, bw, 12, PISTE);
            if (pct > 0) rect(s, bx, y + 7, Math.max(3, bw * pct / 100.0), 12, tot ? NAVY : couleurTaux(pct));
            texte(s, bx + bw + 8, y + 2, 150, lh - 6, pct + " %  (" + l[2] + "/" + l[3] + ")", 11.5, true, tot ? NAVY : couleurTaux(pct), TextParagraph.TextAlign.LEFT);
            y += lh;
        }
        texte(s, MX, H - 72, W - 2 * MX, 20, "Repères : vert ≥ 80 % · bleu 50 à 79 % · rouge < 50 %. Entre parenthèses : collaborateurs ayant atteint le seuil / effectif à évaluer.",
                9.5, false, GRIS, TextParagraph.TextAlign.LEFT);
    }

    private void statistiques(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page) {
        XSLFSlide s = gabarit(ppt, pic, "Statistiques consolidées", st, page);
        double cw = (W - 2 * MX - 2 * 24) / 3;
        bloc(s, MX, 105, cw, "Niveaux d'atteinte", "cochés par les N+1", d.niveaux());
        bloc(s, MX + cw + 24, 105, cw, "Appréciations", "grille BGFI sur la note /20", d.appreciations());
        bloc(s, MX + 2 * (cw + 24), 105, cw, "Souhaits des collaborateurs", "exprimés par le salarié", d.souhaits());
    }

    private void bloc(XSLFSlide s, double x, double y, double w, String titre, String sous, List<ReportingView.Repartition> l) {
        XSLFAutoShape c = rect(s, x, y, w, 360, FOND); c.setLineColor(LIGNE); c.setLineWidth(.75);
        texte(s, x + 16, y + 14, w - 32, 24, titre, 15, true, NAVY, TextParagraph.TextAlign.LEFT);
        texte(s, x + 16, y + 38, w - 32, 18, sous, 10, false, GRIS, TextParagraph.TextAlign.LEFT);
        rect(s, x + 16, y + 62, w - 32, 2, FILET);
        double yy = y + 78;
        for (ReportingView.Repartition r : l) {
            texte(s, x + 16, yy, w - 100, 20, r.libelle() + (r.code() != null && r.code().matches(".*\\d.*") ? "  (" + r.code() + ")" : ""), 11, false, TEXTE, TextParagraph.TextAlign.LEFT);
            texte(s, x + w - 96, yy, 80, 20, r.nb() + "  ·  " + r.pct() + " %", 11, true, NAVY, TextParagraph.TextAlign.RIGHT);
            rect(s, x + 16, yy + 24, w - 32, 7, PISTE);
            if (r.pct() > 0) rect(s, x + 16, yy + 24, Math.max(3, (w - 32) * r.pct() / 100.0), 7, couleurClasse(r.classe()));
            yy += 52;
        }
    }

    private void formations(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page, List<ReportingView.Formation> fs, String suite) {
        XSLFSlide s = gabarit(ppt, pic, "Besoins de formation consolidés" + suite, st, page);
        if (fs.isEmpty()) { texte(s, MX, 120, W - 2 * MX, 30, "Aucun besoin de formation exprimé sur les fiches de ce périmètre.", 14, false, GRIS, TextParagraph.TextAlign.LEFT); return; }
        String[] h = {"Formation", "Demandes", "Priorité", "Demandé par", "Directions", "Collaborateurs"};
        double[] w = {230, 70, 90, 150, 110, 200};
        XSLFTable tb = s.createTable(fs.size() + 1, h.length);
        tb.setAnchor(new Rectangle2D.Double(MX, 100, W - 2 * MX, 32.0 * (fs.size() + 1)));
        for (int c = 0; c < h.length; c++) { tb.setColumnWidth(c, w[c]); entete(tb.getCell(0, c), h[c], c != 1); }
        int r = 1;
        for (ReportingView.Formation f : fs) {
            Color bg = r % 2 == 0 ? FOND : BLANC;
            String prio = switch (f.getPrioriteDominante()) { case "HAUTE" -> "Haute"; case "BASSE" -> "Basse"; default -> "Moyenne"; };
            if (!f.getDetailPriorites().isEmpty()) prio += "\n" + f.getDetailPriorites();
            String[] v = {f.getIntitule(), String.valueOf(f.getDemandes()), prio, f.getDetailOrigines(), f.getDirectionsTexte(), f.getCollaborateursTexte()};
            for (int c = 0; c < v.length; c++)
                cellule(tb.getCell(r, c), v[c], bg, c == 0 || c == 1, c == 2 ? ("HAUTE".equals(f.getPrioriteDominante()) ? ROUGE : "BASSE".equals(f.getPrioriteDominante()) ? GRIS : ORANGE) : TEXTE,
                        c == 1 ? TextParagraph.TextAlign.RIGHT : TextParagraph.TextAlign.LEFT, 10.5);
            r++;
        }
    }


    /** Libellé de campagne sans redite (« Fin d'année 2026 » contient déjà type et exercice). */
    private static String intitule(ReportingExportService.Donnees d) {
        String l = d.campagne().getLibelle() == null ? "" : d.campagne().getLibelle().trim();
        String ex = String.valueOf(d.campagne().getExercice());
        return l.contains(ex) ? l : (l.isEmpty() ? "" : l + " · ") + d.campagne().getTypeLabel() + " " + ex;
    }

    /** Messages clés calculés à partir des chiffres, pour lancer la discussion en réunion. */
    private void pointsCles(XMLSlideShow ppt, ReportingExportService.Donnees d, XSLFPictureData pic, String st, int page) {
        XSLFSlide s = gabarit(ppt, pic, "Points clés", st, page);
        ReportingView.Agregat t = d.total();
        java.util.List<Object[]> m = new java.util.ArrayList<>();
        m.add(new Object[]{couleurTaux(d.tauxGlobal()), "Avancement de la campagne",
                d.tauxGlobal() + " % des évaluations attendues sont validées (" + t.getValidees() + " sur " + d.attendues() + "), "
                        + t.getInitiees() + " fiche(s) initiée(s) et " + (t.getEvaluees() + t.getValidees()) + " évaluée(s) par le N+1."});
        m.add(new Object[]{couleurTaux(d.pctSeuilGlobal()), "Performance : objectifs atteints à " + d.seuil() + " % ou plus",
                d.pctSeuilGlobal() + " % des collaborateurs évaluables (" + t.getAtteintSeuil() + " sur " + d.attendues() + ")"
                        + (t.getEvaluees() + t.getValidees() > 0 ? ", soit " + t.getPctSeuilEvalues() + " % des collaborateurs déjà évalués." : ".")});
        java.util.List<String> sansFiche = d.directions().stream().filter(a -> a.getEffectif() > 0 && a.getInitiees() == 0).map(ReportingView.Agregat::getNom).toList();
        if (!sansFiche.isEmpty()) m.add(new Object[]{ROUGE, "Directions sans aucune fiche initiée (" + sansFiche.size() + ")", String.join(", ", sansFiche) + "."});
        java.util.List<String> faibles = d.directions().stream().filter(a -> a.getInitiees() > 0 && a.getTaux() < 50).map(a -> a.getNom() + " (" + a.getTaux() + " %)").toList();
        if (!faibles.isEmpty()) m.add(new Object[]{ORANGE, "Taux de réalisation inférieur à 50 %", String.join(", ", faibles) + "."});
        d.directions().stream().filter(a -> a.getAtteintSeuil() > 0).max(java.util.Comparator.comparingInt(ReportingView.Agregat::getPctAtteinteSeuil))
                .ifPresent(a -> m.add(new Object[]{VERT, "Meilleure performance", a.getNom() + " : " + a.getPctAtteinteSeuil() + " % de collaborateurs à " + d.seuil() + " % ou plus (" + a.getAtteintSeuil() + "/" + a.getEffectif() + ")."}));
        if (t.getRetard() > 0) m.add(new Object[]{ROUGE, "Fiches en retard", t.getRetard() + " fiche(s) au-delà de l'échéance de la campagne."});
        if (!d.formations().isEmpty()) {
            ReportingView.Formation f = d.formations().get(0);
            m.add(new Object[]{BLEU, "Besoins de formation", d.formations().size() + " formation(s) demandée(s) ; la plus citée : « " + f.getIntitule() + " » (" + f.getDemandes() + " demande(s))."});
        }
        double y = 104, w = W - 2 * MX;
        double hh = Math.min(62, (H - 60 - y) / Math.max(1, m.size()));
        for (Object[] x : m) {
            rect(s, MX, y, 6, hh - 12, (Color) x[0]);
            texte(s, MX + 20, y - 1, w - 20, 22, (String) x[1], 14, true, NAVY, TextParagraph.TextAlign.LEFT);
            texte(s, MX + 20, y + 21, w - 20, hh - 24, (String) x[2], 12, false, TEXTE, TextParagraph.TextAlign.LEFT);
            y += hh;
        }
    }

    // --- gabarit et primitives

    private XSLFSlide gabarit(XMLSlideShow ppt, XSLFPictureData pic, String titre, String sousTitre, int page) {
        XSLFSlide s = ppt.createSlide();
        rect(s, 0, 0, W, H, BLANC);
        rect(s, 0, 0, 8, H, NAVY);
        texte(s, MX, 22, W - 2 * MX - 120, 34, titre, 22, true, NAVY, TextParagraph.TextAlign.LEFT);
        texte(s, MX, 56, W - 2 * MX - 120, 20, sousTitre, 11, false, GRIS, TextParagraph.TextAlign.LEFT);
        if (pic != null) { XSLFPictureShape p = s.createPicture(pic); p.setAnchor(new Rectangle2D.Double(W - MX - 70, 20, 70, 40)); }
        rect(s, MX, 82, W - 2 * MX, 2, FILET);
        rect(s, MX, H - 34, W - 2 * MX, .75, LIGNE);
        texte(s, MX, H - 30, 600, 18, "HRX · BGFIBank RDC · Direction du Capital Humain et de la Transformation · Confidentiel", 8.5, false, GRIS, TextParagraph.TextAlign.LEFT);
        texte(s, W - MX - 60, H - 30, 60, 18, String.valueOf(page), 8.5, true, NAVY, TextParagraph.TextAlign.RIGHT);
        return s;
    }

    private static XSLFAutoShape rect(XSLFSlide s, double x, double y, double w, double h, Color fill) {
        XSLFAutoShape r = s.createAutoShape();
        r.setShapeType(ShapeType.RECT);
        r.setAnchor(new Rectangle2D.Double(x, y, w, h));
        r.setFillColor(fill);
        r.setLineColor(null);
        return r;
    }

    private static XSLFTextBox texte(XSLFSlide s, double x, double y, double w, double h, String v, double taille, boolean gras, Color col, TextParagraph.TextAlign al) {
        XSLFTextBox t = s.createTextBox();
        t.setAnchor(new Rectangle2D.Double(x, y, w, h));
        t.setInsets(new org.apache.poi.sl.usermodel.Insets2D(0, 0, 0, 0));
        t.setWordWrap(true);
        t.clearText();
        String[] lignes = v == null ? new String[]{""} : v.split("\n", -1);
        for (String l : lignes) {
            XSLFTextParagraph p = t.addNewTextParagraph();
            p.setTextAlign(al);
            XSLFTextRun r = p.addNewTextRun();
            r.setText(l); r.setFontSize(taille); r.setBold(gras); r.setFontColor(col); r.setFontFamily(POLICE);
        }
        return t;
    }

    private static void entete(XSLFTableCell c, String v, boolean gauche) {
        cellule(c, v, NAVY, true, BLANC, gauche ? TextParagraph.TextAlign.LEFT : TextParagraph.TextAlign.RIGHT, 10.5);
    }

    private static void cellule(XSLFTableCell c, String v, Color bg, boolean gras, Color col, TextParagraph.TextAlign al, double taille) {
        c.setFillColor(bg);
        c.setVerticalAlignment(VerticalAlignment.MIDDLE);
        c.setInsets(new org.apache.poi.sl.usermodel.Insets2D(4, 7, 4, 7));
        for (TableCell.BorderEdge e : TableCell.BorderEdge.values()) { c.setBorderColor(e, LIGNE); c.setBorderWidth(e, .5); }
        c.clearText();
        // PowerPoint exige <a:bodyPr/> en tête du texte d'une cellule (sinon il « répare » le fichier et supprime le tableau).
        org.openxmlformats.schemas.drawingml.x2006.main.CTTextBody corps = ((org.openxmlformats.schemas.drawingml.x2006.main.CTTableCell) c.getXmlObject()).getTxBody();
        if (corps != null && corps.getBodyPr() == null) corps.addNewBodyPr();
        String[] lignes = v == null ? new String[]{""} : v.split("\n", -1);
        for (int i = 0; i < lignes.length; i++) {
            XSLFTextParagraph p = c.addNewTextParagraph();
            p.setTextAlign(al);
            XSLFTextRun r = p.addNewTextRun();
            r.setText(lignes[i]); r.setFontFamily(POLICE);
            r.setFontSize(i == 0 ? taille : taille - 2); r.setBold(gras && i == 0); r.setFontColor(i == 0 ? col : GRIS);
        }
    }

    private static Color couleurTaux(int t) { return t >= 80 ? VERT : t >= 50 ? BLEU : t > 0 ? ROUGE : GRIS; }
    private static Color couleurClasse(String c) {
        return switch (c == null ? "" : c) { case "hx-att--4" -> VERT; case "hx-att--2" -> ORANGE; case "hx-att--1" -> ROUGE; default -> BLEU; };
    }

    private static byte[] logo() {
        try {
            FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Exception ignored) { }
        return null;
    }
}
