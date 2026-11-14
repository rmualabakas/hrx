package cd.mura.hrx.service;

import cd.mura.hrx.service.OrganigrammePdfService.Noeud;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Organigramme en Word (.docx) paysage, modifiable : chaque case est une zone de texte Word
 * (nom, fonction, direction à corriger directement, case déplaçable), reliée par des traits.
 * Même mise en page que le PDF : A4 paysage, ou A3 si l'arbre est trop large pour rester lisible.
 */
@ApplicationScoped
public class OrganigrammeWordService implements Serializable {

    // unités : points typographiques (1 pt = 12 700 EMU = 20 twips)
    private static final float W = 150, H = 60, GAP_H = 14, GAP_V = 36, MARGE = 28, ENTETE = 58, PIED = 22;
    private static final float A4_L = 841.89f, A4_H = 595.28f, A3_L = 1190.55f, A3_H = 841.89f;
    private static final String BLEU = "1E4A7A", OR = "B8872B", TRAIT = "AAB4C3", TEXTE = "19202D", GRIS = "6E7682", BORD = "D6DCE4", FOND = "F7F9FC";

    private static final String NS = "xmlns:wpc=\"http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas\" "
            + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
            + "xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" "
            + "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" mc:Ignorable=\"w14\"";

    /** État d'une génération (identifiants de formes uniques, échelle). */
    private static final class Ctx {
        final StringBuilder xml = new StringBuilder();
        final Map<Noeud, Float> largeurs = new IdentityHashMap<>();
        float s, ox, oy;
        int id = 10;
    }

    public byte[] docx(Noeud racine, String perimetre, int effectif) {
        try {
            Ctx c = new Ctx();
            float lt = largeur(racine, c.largeurs);
            int niveaux = profondeur(racine);
            float ht = niveaux * H + (niveaux - 1) * GAP_V;

            boolean a3 = false;
            float pl = A4_L, ph = A4_H;
            c.s = echelle(pl, ph, lt, ht);
            if (c.s < 0.6f) { a3 = true; pl = A3_L; ph = A3_H; c.s = echelle(pl, ph, lt, ht); }
            float dispoL = pl - 2 * MARGE, dispoH = ph - 2 * MARGE - ENTETE - PIED;
            c.ox = MARGE + (dispoL - lt * c.s) / 2;
            c.oy = MARGE + ENTETE + (dispoH - ht * c.s) / 2;

            dessiner(c, racine, 0, 0);

            String date = new SimpleDateFormat("dd/MM/yyyy").format(new Date());
            String pied = "Document établi par HRX le " + new SimpleDateFormat("dd/MM/yyyy 'à' HH:mm").format(new Date()) + " · usage interne";
            zoneTexte(c, MARGE, ph - MARGE - 4, pl - 2 * MARGE, 14, null, null, 0,
                    para(run(pied, 7.5f, GRIS, false), "left"));

            byte[] logo = logo();
            float[] tailleLogo = logo == null ? null : tailleLogo(logo);

            StringBuilder body = new StringBuilder();
            // en-tête : logo + titre + sous-titre, puis filet
            body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"40\"/></w:pPr>");
            if (tailleLogo != null) body.append(imageInline(tailleLogo[0], tailleLogo[1])).append(run("   ", 15, BLEU, true));
            body.append(run("Organigramme · " + perimetre, 15, BLEU, true)).append("</w:p>");
            body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"6\" w:color=\"").append(BORD).append("\"/></w:pBdr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>")
                .append(run(effectif + " collaborateur" + (effectif > 1 ? "s" : "") + " actif" + (effectif > 1 ? "s" : "") + " · situation au " + date, 9, GRIS, false));
            body.append(c.xml).append("</w:p>");

            int pw = Math.round(pl * 20), phh = Math.round(ph * 20), m = Math.round(MARGE * 20);
            String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + NS + "><w:body>" + body
                    + "<w:sectPr><w:pgSz w:w=\"" + pw + "\" w:h=\"" + phh + "\" w:orient=\"landscape\"" + (a3 ? " w:code=\"8\"" : " w:code=\"9\"") + "/>"
                    + "<w:pgMar w:top=\"" + m + "\" w:right=\"" + m + "\" w:bottom=\"" + m + "\" w:left=\"" + m + "\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>"
                    + "</w:body></w:document>";
            return zip(document, logo);
        } catch (Exception e) {
            throw new IllegalStateException("Organigramme Word impossible : " + e.getMessage(), e);
        }
    }

    private static float echelle(float pl, float ph, float lt, float ht) {
        float dispoL = pl - 2 * MARGE, dispoH = ph - 2 * MARGE - ENTETE - PIED;
        return Math.min(1f, Math.min(dispoL / lt, dispoH / ht));
    }

    // mise en page (identique au PDF)

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

    private float sommeEnfants(Noeud n, Map<Noeud, Float> m) {
        float s = GAP_H * (n.enfants().size() - 1);
        for (Noeud e : n.enfants()) s += m.get(e);
        return s;
    }

    /** Sous-arbre dont le coin haut-gauche est (x, y) dans le repère de l'arbre (y vers le bas). */
    private void dessiner(Ctx c, Noeud n, float x, float y) {
        float l = c.largeurs.get(n), cx = x + l / 2;
        if (!n.enfants().isEmpty()) {
            float basParent = y + H, milieu = basParent + GAP_V / 2, hautEnfant = basParent + GAP_V;
            float sx = x + (l - sommeEnfants(n, c.largeurs)) / 2;
            List<Float> centres = new ArrayList<>();
            float xx = sx;
            for (Noeud e : n.enfants()) { centres.add(xx + c.largeurs.get(e) / 2); xx += c.largeurs.get(e) + GAP_H; }
            trait(c, cx, basParent, cx, milieu);
            if (centres.size() > 1) trait(c, centres.get(0), milieu, centres.get(centres.size() - 1), milieu);
            for (float ce : centres) trait(c, ce, milieu, ce, hautEnfant);
            xx = sx;
            for (Noeud e : n.enfants()) { dessiner(c, e, xx, y + H + GAP_V); xx += c.largeurs.get(e) + GAP_H; }
        }
        caseNoeud(c, n, cx - W / 2, y);
    }

    private void caseNoeud(Ctx c, Noeud n, float x, float y) {
        float s = c.s, px = c.ox + x * s, py = c.oy + y * s;
        if (n.racine()) {
            String contenu = para(run(n.nom(), 10 * s, "FFFFFF", true), "left")
                    + para(run(n.effectif() + " collaborateur" + (n.effectif() > 1 ? "s" : ""), 8 * s, "D2DEEC", false), "left");
            zoneTexte(c, px, py, W * s, H * s, BLEU, BLEU, 0, contenu);
            return;
        }
        String fonction = n.fonction() == null || n.fonction().isBlank() ? "Fonction non renseignée" : n.fonction();
        String bas = n.directeur() ? "Directeur" + (n.direction().isBlank() ? "" : " " + n.direction()) : n.direction();
        if (n.effectif() > 0) bas += (bas.isBlank() ? "" : " · ") + "encadre " + n.effectif();
        String contenu = para(run(n.nom(), 8.5f * s, TEXTE, true), "left")
                + para(run(fonction, 7.2f * s, GRIS, false), "left")
                + (bas.isBlank() ? "" : para(run(bas, 6.8f * s, n.directeur() ? OR : GRIS, n.directeur()), "left"));
        zoneTexte(c, px, py, W * s, H * s, FOND, n.directeur() ? OR : BORD, n.directeur() ? 1.5f : 0.75f, contenu);
    }

    // formes DrawingML (Word 2010+)

    private static long emu(float pt) { return Math.round(pt * 12700); }

    private static String ancre(int id, float x, float y, float w, float h, String nom) {
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"" + (251658240 + id) + "\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(x) + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>" + emu(y) + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/><wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"" + id + "\" name=\"" + nom + " " + id + "\"/><wp:cNvGraphicFramePr/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">";
    }
    private static final String FIN_ANCRE = "</a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";

    /** Zone de texte (rectangle arrondi) positionnée sur la page ; fond / bordure null = aucun. */
    private void zoneTexte(Ctx c, float x, float y, float w, float h, String fond, String bord, float epaisseur, String contenu) {
        int id = c.id++;
        float pad = 7 * c.s;
        c.xml.append(ancre(id, x, y, w, h, "Case"))
             .append("<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(emu(w)).append("\" cy=\"").append(emu(h)).append("\"/></a:xfrm>")
             .append("<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val 9000\"/></a:avLst></a:prstGeom>")
             .append(fond == null ? "<a:noFill/>" : "<a:solidFill><a:srgbClr val=\"" + fond + "\"/></a:solidFill>")
             .append(bord == null || epaisseur <= 0 ? "<a:ln><a:noFill/></a:ln>" : "<a:ln w=\"" + emu(epaisseur) + "\"><a:solidFill><a:srgbClr val=\"" + bord + "\"/></a:solidFill></a:ln>")
             .append("</wps:spPr><wps:txbx><w:txbxContent>").append(contenu).append("</w:txbxContent></wps:txbx>")
             .append("<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"square\" lIns=\"").append(emu(pad)).append("\" tIns=\"").append(emu(pad * 0.6f))
             .append("\" rIns=\"").append(emu(pad)).append("\" bIns=\"").append(emu(pad * 0.4f)).append("\" anchor=\"t\" anchorCtr=\"0\"><a:noAutofit/></wps:bodyPr></wps:wsp>")
             .append(FIN_ANCRE);
    }

    /** Trait horizontal ou vertical entre deux points du repère de l'arbre. */
    private void trait(Ctx c, float x1, float y1, float x2, float y2) {
        int id = c.id++;
        float s = c.s;
        float x = c.ox + Math.min(x1, x2) * s, y = c.oy + Math.min(y1, y2) * s, w = Math.abs(x2 - x1) * s, h = Math.abs(y2 - y1) * s;
        c.xml.append(ancre(id, x, y, w, h, "Liaison"))
             .append("<wps:wsp><wps:cNvCnPr/><wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(emu(w)).append("\" cy=\"").append(emu(h)).append("\"/></a:xfrm>")
             .append("<a:prstGeom prst=\"line\"><a:avLst/></a:prstGeom><a:noFill/><a:ln w=\"").append(emu(1f)).append("\"><a:solidFill><a:srgbClr val=\"").append(TRAIT).append("\"/></a:solidFill></a:ln>")
             .append("</wps:spPr><wps:bodyPr/></wps:wsp>").append(FIN_ANCRE);
    }

    private static String para(String runs, String align) {
        return "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"228\" w:lineRule=\"auto\"/><w:jc w:val=\"" + align + "\"/></w:pPr>" + runs + "</w:p>";
    }

    private static String run(String texte, float taille, String couleur, boolean gras) {
        int demiPoints = Math.max(9, Math.round(taille * 2));
        return "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:cs=\"Arial\"/>" + (gras ? "<w:b/>" : "")
                + "<w:color w:val=\"" + couleur + "\"/><w:sz w:val=\"" + demiPoints + "\"/><w:szCs w:val=\"" + demiPoints + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + esc(texte) + "</w:t></w:r>";
    }

    private static String imageInline(float w, float h) {
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:docPr id=\"1\" name=\"Logo\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"logo.png\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rIdLogo\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> { if (ch >= 0x20 || ch == '\t') b.append(ch); }
            }
        }
        return b.toString();
    }

    // paquet .docx

    private static byte[] zip(String document, byte[] logo) throws Exception {
        String types = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>";
        String docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + (logo == null ? "" : "<Relationship Id=\"rIdLogo\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/logo.png\"/>")
                + "</Relationships>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Arial\" w:cs=\"Arial\"/><w:sz w:val=\"18\"/><w:szCs w:val=\"18\"/><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>";
        String core = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>Organigramme</dc:title><dc:creator>HRX</dc:creator></cp:coreProperties>";
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            entree(z, "[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8));
            entree(z, "_rels/.rels", rels.getBytes(StandardCharsets.UTF_8));
            entree(z, "docProps/core.xml", core.getBytes(StandardCharsets.UTF_8));
            entree(z, "word/document.xml", document.getBytes(StandardCharsets.UTF_8));
            entree(z, "word/styles.xml", styles.getBytes(StandardCharsets.UTF_8));
            entree(z, "word/_rels/document.xml.rels", docRels.getBytes(StandardCharsets.UTF_8));
            if (logo != null) entree(z, "word/media/logo.png", logo);
            z.finish();
            return out.toByteArray();
        }
    }

    private static void entree(ZipOutputStream z, String nom, byte[] data) throws Exception {
        z.putNextEntry(new ZipEntry(nom)); z.write(data); z.closeEntry();
    }

    /** Logo ramené à 34 pt de haut, proportions conservées. */
    private static float[] tailleLogo(byte[] png) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img == null) return null;
            float h = 30, w = h * img.getWidth() / img.getHeight();
            if (w > 90) { w = 90; h = w * img.getHeight() / img.getWidth(); }
            return new float[]{w, h};
        } catch (Exception e) { return null; }
    }

    private static byte[] logo() {
        try {
            FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Throwable ignored) { }
        return null;
    }
}
