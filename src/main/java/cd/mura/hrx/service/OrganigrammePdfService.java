package cd.mura.hrx.service;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/** Organigramme en PDF paysage, sur une seule page : A4, ou A3 si l'arbre est trop large pour rester lisible. */
@ApplicationScoped
public class OrganigrammePdfService implements Serializable {

    /** Une case de l'organigramme et ses subordonnés. */
    public record Noeud(String nom, String fonction, String direction, boolean directeur, boolean racine, int effectif, List<Noeud> enfants) implements Serializable { }

    private static final float W = 150, H = 60, GAP_H = 14, GAP_V = 36, MARGE = 28, ENTETE = 58, PIED = 22;
    private static final Color BLEU = new DeviceRgb(30, 74, 122), OR = new DeviceRgb(143, 163, 116), TRAIT = new DeviceRgb(170, 180, 195),
            TEXTE = new DeviceRgb(25, 32, 45), GRIS = new DeviceRgb(110, 118, 130), BORD = new DeviceRgb(214, 220, 228), FOND = new DeviceRgb(247, 249, 252);

    public byte[] pdf(Noeud racine, String perimetre, int effectif) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Map<Noeud, Float> largeurs = new IdentityHashMap<>();
            float lt = largeur(racine, largeurs);
            int niveaux = profondeur(racine);
            float ht = niveaux * H + (niveaux - 1) * GAP_V;

            PageSize format = PageSize.A4.rotate();
            float echelle = echelle(format, lt, ht);
            if (echelle < 0.6f) { format = PageSize.A3.rotate(); echelle = echelle(format, lt, ht); }

            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            PdfPage page = pdf.addNewPage(format);
            PdfCanvas c = new PdfCanvas(page);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), gras = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            Rectangle r = page.getPageSize();

            entete(pdf, c, r, reg, gras, perimetre, effectif);

            // zone de dessin centrée sous l'en-tête
            float dispoL = r.getWidth() - 2 * MARGE, dispoH = r.getHeight() - 2 * MARGE - ENTETE - PIED;
            float ox = MARGE + (dispoL - lt * echelle) / 2;
            float oy = r.getHeight() - MARGE - ENTETE - (dispoH - ht * echelle) / 2;   // haut de l'arbre
            c.saveState();
            c.concatMatrix(echelle, 0, 0, echelle, ox, oy);
            dessiner(c, racine, 0, 0, largeurs, reg, gras);
            c.restoreState();

            String pied = "Document établi par HRX le " + new SimpleDateFormat("dd/MM/yyyy 'à' HH:mm").format(new Date()) + " · usage interne";
            c.beginText().setFontAndSize(reg, 7.5f).setFillColor(GRIS).moveText(MARGE, MARGE - 8).showText(pied).endText();
            pdf.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Organigramme PDF impossible : " + e.getMessage(), e);
        }
    }

    private static float echelle(PageSize p, float lt, float ht) {
        float dispoL = p.getWidth() - 2 * MARGE, dispoH = p.getHeight() - 2 * MARGE - ENTETE - PIED;
        return Math.min(1f, Math.min(dispoL / lt, dispoH / ht));
    }

    private void entete(PdfDocument pdf, PdfCanvas c, Rectangle r, PdfFont reg, PdfFont gras, String perimetre, int effectif) {
        float haut = r.getHeight() - MARGE, x = MARGE;
        byte[] lg = logo();
        if (lg != null) {
            try { c.addImageFittedIntoRectangle(ImageDataFactory.create(lg), new Rectangle(MARGE, haut - 34, 60, 34), false); x += 72; } catch (Exception ignored) { }
        }
        c.beginText().setFontAndSize(gras, 15).setFillColor(BLEU).moveText(x, haut - 16).showText("Organigramme · " + perimetre).endText();
        c.beginText().setFontAndSize(reg, 9).setFillColor(GRIS).moveText(x, haut - 30)
                .showText(effectif + " collaborateur" + (effectif > 1 ? "s" : "") + " actif" + (effectif > 1 ? "s" : "") + " · situation au " + new SimpleDateFormat("dd/MM/yyyy").format(new Date())).endText();
        c.setStrokeColor(BORD).setLineWidth(0.8f).moveTo(MARGE, haut - 42).lineTo(r.getWidth() - MARGE, haut - 42).stroke();
    }

    // mise en page : chaque sous-arbre occupe la largeur de ses enfants, le parent est centré au-dessus

    private float largeur(Noeud n, Map<Noeud, Float> m) {
        float s = 0;
        for (Noeud e : n.enfants()) s += largeur(e, m);
        if (!n.enfants().isEmpty()) s += GAP_H * (n.enfants().size() - 1);
        float l = Math.max(W, s);
        m.put(n, l);
        return l;
    }

    private int profondeur(Noeud n) {
        int p = 0;
        for (Noeud e : n.enfants()) p = Math.max(p, profondeur(e));
        return p + 1;
    }

    /** Dessine le sous-arbre dont le coin haut-gauche est (x, -y) dans le repère de l'arbre (y vers le bas). */
    private void dessiner(PdfCanvas c, Noeud n, float x, float y, Map<Noeud, Float> m, PdfFont reg, PdfFont gras) {
        float l = m.get(n), cx = x + l / 2;
        caseNoeud(c, n, cx - W / 2, -y, reg, gras);
        if (n.enfants().isEmpty()) return;

        float basParent = -y - H, milieu = basParent - GAP_V / 2, hautEnfant = basParent - GAP_V;
        float sx = x + (l - sommeEnfants(n, m)) / 2;
        List<Float> centres = new ArrayList<>();
        float xx = sx;
        for (Noeud e : n.enfants()) { centres.add(xx + m.get(e) / 2); xx += m.get(e) + GAP_H; }

        c.setStrokeColor(TRAIT).setLineWidth(1f);
        c.moveTo(cx, basParent).lineTo(cx, milieu).stroke();
        if (centres.size() > 1) c.moveTo(centres.get(0), milieu).lineTo(centres.get(centres.size() - 1), milieu).stroke();
        for (float ce : centres) c.moveTo(ce, milieu).lineTo(ce, hautEnfant).stroke();

        xx = sx;
        for (Noeud e : n.enfants()) { dessiner(c, e, xx, y + H + GAP_V, m, reg, gras); xx += m.get(e) + GAP_H; }
    }

    private float sommeEnfants(Noeud n, Map<Noeud, Float> m) {
        float s = GAP_H * (n.enfants().size() - 1);
        for (Noeud e : n.enfants()) s += m.get(e);
        return s;
    }

    /** Case de W × H dont le coin haut-gauche est (x, top). */
    private void caseNoeud(PdfCanvas c, Noeud n, float x, float top, PdfFont reg, PdfFont gras) {
        float b = top - H;
        if (n.racine()) {
            c.setFillColor(BLEU).roundRectangle(x, b, W, H, 6).fill();
            texte(c, gras, 10, new DeviceRgb(255, 255, 255), x + 10, top - 22, W - 20, n.nom(), 2);
            c.beginText().setFontAndSize(reg, 8).setFillColor(new DeviceRgb(210, 222, 236)).moveText(x + 10, b + 10).showText(n.effectif() + " collaborateur" + (n.effectif() > 1 ? "s" : "")).endText();
            return;
        }
        c.setFillColor(FOND).setStrokeColor(BORD).setLineWidth(0.8f).roundRectangle(x, b, W, H, 5).fillStroke();
        if (n.directeur()) c.setFillColor(OR).rectangle(x + 3, top - 2.5f, W - 6, 2.5f).fill();

        float ty = top - 13;
        int lignesNom = texte(c, gras, 8.5f, TEXTE, x + 8, ty, W - 16, n.nom(), 2);
        ty -= lignesNom * 10;
        texte(c, reg, 7.2f, GRIS, x + 8, ty, W - 16, n.fonction() == null || n.fonction().isBlank() ? "Fonction non renseignée" : n.fonction(), 2);

        String bas = n.directeur() ? "Directeur" + (n.direction().isBlank() ? "" : " " + n.direction()) : n.direction();
        if (n.effectif() > 0) bas += (bas.isBlank() ? "" : " · ") + "encadre " + n.effectif();
        if (!bas.isBlank())
            c.beginText().setFontAndSize(n.directeur() ? gras : reg, 6.8f).setFillColor(n.directeur() ? OR : GRIS).moveText(x + 8, b + 6).showText(couper(n.directeur() ? gras : reg, 6.8f, bas, W - 16)).endText();
    }

    /** Écrit un texte sur au plus {@code max} lignes dans la largeur donnée ; retourne le nombre de lignes. */
    private int texte(PdfCanvas c, PdfFont f, float taille, Color couleur, float x, float y, float largeur, String txt, int max) {
        List<String> lignes = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String mot : (txt == null ? "" : txt).split("\\s+")) {
            String essai = cur.length() == 0 ? mot : cur + " " + mot;
            if (f.getWidth(essai, taille) <= largeur || cur.length() == 0) { cur = new StringBuilder(essai); }
            else { lignes.add(cur.toString()); cur = new StringBuilder(mot); }
        }
        if (cur.length() > 0) lignes.add(cur.toString());
        if (lignes.size() > max) { lignes = new ArrayList<>(lignes.subList(0, max)); lignes.set(max - 1, lignes.get(max - 1) + "…"); }
        for (int i = 0; i < lignes.size(); i++)
            c.beginText().setFontAndSize(f, taille).setFillColor(couleur).moveText(x, y - i * (taille + 1.6f)).showText(couper(f, taille, lignes.get(i), largeur)).endText();
        return lignes.size();
    }

    private static String couper(PdfFont f, float taille, String s, float largeur) {
        if (f.getWidth(s, taille) <= largeur) return s;
        while (s.length() > 1 && f.getWidth(s + "…", taille) > largeur) s = s.substring(0, s.length() - 1);
        return s.trim() + "…";
    }

    private static byte[] logo() {
        try {
            FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Exception ignored) { }
        return null;
    }
}
