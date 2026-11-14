package cd.mura.hrx.service;

import cd.mura.hrx.entity.eval.EvalChaud;
import cd.mura.hrx.entity.eval.EvalChaudQuestion;
import cd.mura.hrx.service.EvalChaudService.Depouillement;
import cd.mura.hrx.service.EvalChaudService.DirectionAnalyse;
import cd.mura.hrx.service.EvalChaudService.Participant;
import cd.mura.hrx.service.EvalChaudService.QuestionAnalyse;
import cd.mura.hrx.entity.eval.EvalChaudReponse;
import cd.mura.hrx.service.recrutement.ClaudeCvService.AnalyseFormation;
import cd.mura.hrx.service.recrutement.ClaudeCvService.ConstatChiffre;
import cd.mura.hrx.service.recrutement.ClaudeCvService.Recommandation;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Dépouillement d'une évaluation à chaud : classeur Excel analytique et rapport PDF. Les réponses restent anonymes. */
@ApplicationScoped
public class EvalChaudExportService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String ANONYMAT = "Réponses anonymes : la satisfaction d'une direction n'est publiée qu'à partir de "
            + EvalChaudService.SEUIL_ANONYMAT + " répondants.";

    // ------------------------------------------------------------------ données pour l'analyse de Claude

    /**
     * Dépouillement mis en texte pour Claude : indicateurs, statistiques par question, directions, réponses libres et,
     * à froid, avis des supérieurs. Aucun nom ni matricule n'est transmis.
     */
    public static String donneesAnalyse(Depouillement d, List<EvalChaudReponse> avis) {
        EvalChaud c = d.evalChaud();
        StringBuilder sb = new StringBuilder();
        sb.append("Type : ").append(c.isFroid() ? "évaluation à froid (efficacité et impact, MAF 064)" : "évaluation à chaud (satisfaction, MAF 063)").append('\n');
        sb.append("Formation : ").append(c.getIntitule()).append(infosFormation(c)).append('\n');
        sb.append("Questionnaire : ").append(c.getStatutLabel().toLowerCase()).append(periode(c)).append('\n');
        sb.append("Participants : ").append(d.nbParticipants()).append(" ; répondants : ").append(d.nbRepondants()).append(" (").append(d.taux()).append(" %)\n");
        if (d.satisfaction() != null) sb.append("Satisfaction moyenne (questions de satisfaction) : ").append(fmt(d.satisfaction())).append("/5\n");
        if (d.pctSatisfaits() != null) sb.append("Notes favorables (4-5) toutes questions notées : ").append(d.pctSatisfaits()).append(" %\n");
        if (d.pctRecommandation() != null) sb.append("Réponses « oui » aux questions oui/non : ").append(d.pctRecommandation()).append(" %\n");

        sb.append("\nQUESTIONS\n");
        int i = 1;
        for (QuestionAnalyse q : d.questions()) {
            EvalChaudService.Stat st = q.stat(); EvalChaudQuestion qu = st.question();
            sb.append("Q.").append(i++).append(" [").append(EvalChaudQuestion.typeLabel(qu.getType())).append(qu.isCentree() ? ", échelle centrée" : "").append("] ")
              .append(qu.getLibelle()).append(" — ").append(st.nb()).append(" réponse(s)");
            if (qu.isNote() && st.nb() > 0) {
                sb.append(" ; moyenne ").append(fmt(st.moyenne())).append(" ; médiane ").append(fmt(q.mediane()))
                  .append(" ; écart-type ").append(q.ecartType() == null ? "—" : String.format("%.2f", q.ecartType()))
                  .append(" ; favorables ").append(q.pctFavorable()).append(" % ; neutres ").append(q.pctNeutre()).append(" % ; défavorables ").append(q.pctDefavorable()).append(" %")
                  .append(" ; répartition 5→1 : ");
                for (int n = 5; n >= 1; n--) sb.append(st.repartition()[n]).append(n > 1 ? "/" : "");
            } else if (qu.isOuiNon()) {
                sb.append(" ; oui ").append(st.oui()).append(", non ").append(st.non()).append(" (").append(st.getPctOui()).append(" % oui)");
            }
            if (st.choix() != null && !st.choix().isEmpty()) {
                sb.append(" ; choix cochés : ");
                for (Object[] o : st.choix()) sb.append(o[0]).append(" = ").append(o[1]).append(" (").append(o[2]).append(" %) ; ");
            }
            sb.append('\n');
            int k = 0;
            for (String t : st.textes()) {
                if (k++ >= 40) { sb.append("   … ").append(st.textes().size() - 40).append(" autre(s) réponse(s)\n"); break; }
                String x = t.replaceAll("\\s+", " ").trim();
                sb.append("   - « ").append(x.length() > 400 ? x.substring(0, 400) + "…" : x).append(" »\n");
            }
        }

        if (!d.directions().isEmpty()) {
            sb.append("\nDIRECTIONS (satisfaction publiée à partir de ").append(EvalChaudService.SEUIL_ANONYMAT).append(" répondants)\n");
            for (DirectionAnalyse a : d.directions())
                sb.append("- ").append(a.direction()).append(" : ").append(a.repondants()).append("/").append(a.participants()).append(" répondants (").append(a.taux()).append(" %)")
                  .append(a.satisfaction() != null ? " ; satisfaction " + fmt(a.satisfaction()) + "/5" : a.masquee() ? " ; satisfaction masquée (anonymat)" : "").append('\n');
        }

        if (c.isFroid()) {
            List<EvalChaudReponse> donnes = avis == null ? List.of() : avis.stream().filter(EvalChaudReponse::isAvisDonne).toList();
            sb.append("\nAVIS DES SUPÉRIEURS HIÉRARCHIQUES : ").append(donnes.size()).append(" avis donné(s) sur ").append(avis == null ? 0 : avis.size()).append(" réponse(s)\n");
            if (!donnes.isEmpty()) {
                double m = donnes.stream().mapToInt(EvalChaudReponse::getAvisNote).average().orElse(0);
                long eff = donnes.stream().filter(EvalChaudReponse::isEfficace).count();
                sb.append("Note moyenne ").append(String.format("%.1f", m)).append("/5 ; jugée efficace (4-5) dans ").append(Math.round(100.0 * eff / donnes.size())).append(" % des cas\n");
                for (EvalChaudReponse r : donnes) {
                    sb.append("- ").append(r.getAvisNote()).append("/5 (").append(r.getAvisLibelle()).append(")");
                    String cm = r.getAvisCommentaire();
                    if (cm != null && !cm.isBlank()) { String x = cm.replaceAll("\\s+", " ").trim(); sb.append(" : « ").append(x.length() > 400 ? x.substring(0, 400) + "…" : x).append(" »"); }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Excel

    public byte[] excel(Depouillement d) { return excel(d, null, null); }

    /** Classeur analytique ; avec l'analyse de Claude (ou la raison de son absence) dans une feuille dédiée. */
    public byte[] excel(Depouillement d, AnalyseFormation ia, String iaIndispo) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            St st = new St(wb);
            EvalChaud c = d.evalChaud();

            // Synthèse
            Sheet s1 = wb.createSheet("Synthèse");
            int r = entete(s1, st, c, c.isFroid() ? "Dépouillement de l'évaluation à froid" : "Dépouillement de l'évaluation à chaud");
            r = ligneEntete(s1, st, r, "Indicateur", "Valeur");
            r = kv(s1, st, r, "Participants", String.valueOf(d.nbParticipants()));
            r = kv(s1, st, r, "Réponses reçues", String.valueOf(d.nbRepondants()));
            r = kv(s1, st, r, "Taux de réponse", d.taux() + " %");
            r = kv(s1, st, r, "Satisfaction moyenne (/5)", d.satisfaction() == null ? "—" : fmt(d.satisfaction()));
            r = kv(s1, st, r, "Notes favorables (4 ou 5)", d.pctSatisfaits() == null ? "—" : d.pctSatisfaits() + " %");
            r = kv(s1, st, r, "Réponses « oui » (questions oui/non)", d.pctRecommandation() == null ? "—" : d.pctRecommandation() + " %");
            r++;
            List<QuestionAnalyse> cl = d.classement();
            if (!cl.isEmpty()) {
                r = ligneEntete(s1, st, r, "Points forts (questions les mieux notées)", "Moyenne /5");
                for (QuestionAnalyse q : cl.subList(0, Math.min(3, cl.size()))) r = kv(s1, st, r, q.stat().question().getLibelle(), fmt(q.stat().moyenne()));
                r++;
                r = ligneEntete(s1, st, r, "Axes d'amélioration (questions les moins bien notées)", "Moyenne /5");
                List<QuestionAnalyse> inv = new java.util.ArrayList<>(cl); java.util.Collections.reverse(inv);
                for (QuestionAnalyse q : inv.subList(0, Math.min(3, inv.size()))) r = kv(s1, st, r, q.stat().question().getLibelle(), fmt(q.stat().moyenne()));
            }
            Row an = s1.createRow(r + 1); txt(an, 0, ANONYMAT, st.sous);
            largeurs(s1, 62, 18);

            // Analyse par question
            Sheet s2 = wb.createSheet("Analyse par question");
            r = entete(s2, st, c, "Analyse statistique par question");
            String[] h = {"N°", "Question", "Type", "Réponses", "Moyenne /5", "Médiane", "Écart-type", "% favorables (4-5)", "% neutres (3)", "% défavorables (1-2)",
                    "Note 5", "Note 4", "Note 3", "Note 2", "Note 1", "Oui", "Non", "% Oui"};
            r = ligneEntete(s2, st, r, h);
            int i = 1;
            for (QuestionAnalyse q : d.questions()) {
                EvalChaudService.Stat s = q.stat(); EvalChaudQuestion qu = s.question();
                Row row = s2.createRow(r++);
                num(row, 0, i++, st.num); txt(row, 1, qu.getLibelle(), st.cell); txt(row, 2, EvalChaudQuestion.typeLabel(qu.getType()) + (qu.isCentree() ? " (centrée : 3 = idéal)" : ""), st.cell); num(row, 3, s.nb(), st.num);
                if (qu.isNote()) {
                    dec(row, 4, s.moyenne(), st.dec); dec(row, 5, q.mediane(), st.dec); dec(row, 6, q.ecartType(), st.dec2);
                    pct(row, 7, s.nb() == 0 ? null : q.pctFavorable(), st); pct(row, 8, s.nb() == 0 ? null : q.pctNeutre(), st); pct(row, 9, s.nb() == 0 ? null : q.pctDefavorable(), st);
                    for (int n = 5; n >= 1; n--) num(row, 10 + (5 - n), s.repartition()[n], st.num);
                } else for (int k = 4; k <= 14; k++) txt(row, k, "", st.cell);
                if (qu.isOuiNon()) { num(row, 15, s.oui(), st.num); num(row, 16, s.non(), st.num); pct(row, 17, s.oui() + s.non() == 0 ? null : s.getPctOui(), st); }
                else for (int k = 15; k <= 17; k++) txt(row, k, "", st.cell);
            }
            largeurs(s2, 5, 52, 14, 10, 11, 9, 10, 13, 11, 14, 8, 8, 8, 8, 8, 7, 7, 8);
            s2.createFreezePane(2, 5);

            // Analyse de Claude
            if (ia != null || iaIndispo != null) {
                Sheet sa = wb.createSheet("Analyse Claude");
                int ra = entete(sa, st, c, "Analyse des résultats (rédigée par Claude, IA)");
                if (ia == null) { txt(sa.createRow(ra), 0, iaIndispo, st.sous); }
                else {
                    ra = blocXls(sa, st, ra, "Synthèse", ia.synthese(), null);
                    ra = blocXls(sa, st, ra, "Fiabilité des résultats", ia.fiabilite() + " — " + ia.fiabiliteJustification(), null);
                    if (ia.efficaciteTransfert() != null && !ia.efficaciteTransfert().isBlank()) ra = blocXls(sa, st, ra, "Efficacité et transfert au poste", ia.efficaciteTransfert(), null);
                    ra = constatsXls(sa, st, ra, "Points forts", ia.pointsForts());
                    ra = constatsXls(sa, st, ra, "Points de vigilance", ia.pointsVigilance());
                    ra = constatsXls(sa, st, ra, "Écarts significatifs", ia.ecartsSignificatifs());
                    ra = constatsXls(sa, st, ra, "Thèmes des réponses libres", ia.themesVerbatims());
                    if (ia.recommandations() != null && !ia.recommandations().isEmpty()) {
                        ra = ligneEntete(sa, st, ra + 1, "Recommandation", "Justification", "Priorité");
                        for (Recommandation rc : ia.recommandations()) { Row row = sa.createRow(ra++); txt(row, 0, rc.action(), st.cell); txt(row, 1, rc.justification(), st.cell); txt(row, 2, rc.priorite(), st.cell); }
                    }
                    txt(sa.createRow(ra + 1), 0, MENTION_IA, st.sous);
                }
                largeurs(sa, 60, 70, 12);
            }

            // Par direction
            Sheet s3 = wb.createSheet("Par direction");
            r = entete(s3, st, c, "Participation et satisfaction par direction");
            r = ligneEntete(s3, st, r, "Direction", "Participants", "Répondants", "Taux de réponse", "Satisfaction /5");
            for (DirectionAnalyse a : d.directions()) {
                Row row = s3.createRow(r++);
                txt(row, 0, a.direction(), st.cell); num(row, 1, a.participants(), st.num); num(row, 2, a.repondants(), st.num); pct(row, 3, a.taux(), st);
                if (a.satisfaction() != null) dec(row, 4, a.satisfaction(), st.dec); else txt(row, 4, a.masquee() ? "masquée (anonymat)" : "—", st.cell);
            }
            Row an3 = s3.createRow(r + 1); txt(an3, 0, ANONYMAT, st.sous);
            largeurs(s3, 46, 13, 13, 15, 20);

            // Verbatims
            Sheet s4 = wb.createSheet("Verbatims");
            r = entete(s4, st, c, "Réponses libres (verbatims)");
            r = ligneEntete(s4, st, r, "Question", "Réponse");
            for (QuestionAnalyse q : d.questions())
                for (String t : q.stat().textes()) { Row row = s4.createRow(r++); txt(row, 0, q.stat().question().getLibelle(), st.cell); txt(row, 1, t, st.cell); }
            largeurs(s4, 46, 90);

            // Réponses brutes anonymisées
            Sheet s5 = wb.createSheet("Réponses brutes");
            r = entete(s5, st, c, "Réponses individuelles anonymisées (ordre aléatoire)");
            List<QuestionAnalyse> qs = d.questions();
            String[] hb = new String[qs.size() + 1]; hb[0] = "Répondant";
            for (int k = 0; k < qs.size(); k++) hb[k + 1] = (k + 1) + ". " + qs.get(k).stat().question().getLibelle();
            int debut = r;
            r = ligneEntete(s5, st, r, hb);
            int n = 1;
            for (Map<Integer, String> m : d.brut()) {
                Row row = s5.createRow(r++);
                txt(row, 0, "R" + n++, st.cell);
                for (int k = 0; k < qs.size(); k++) {
                    EvalChaudQuestion qu = qs.get(k).stat().question(); String v = m.get(qu.getId());
                    Long nv = qu.isNote() && v != null ? noteDe(v) : null;
                    if (nv != null) num(row, k + 1, nv, st.num); else txt(row, k + 1, v, st.cell);
                }
            }
            s5.setColumnWidth(0, 11 * 256);
            for (int k = 0; k < qs.size(); k++) s5.setColumnWidth(k + 1, (qs.get(k).stat().question().isRedigee() ? 48 : 18) * 256);
            s5.createFreezePane(1, debut + 1);
            if (r > debut + 1) s5.setAutoFilter(new CellRangeAddress(debut, r - 1, 0, qs.size()));

            // Participation (qui a répondu, pas ce qu'il a répondu)
            Sheet s6 = wb.createSheet("Participation");
            r = entete(s6, st, c, "Participation (le contenu des réponses reste anonyme)");
            debut = r;
            r = ligneEntete(s6, st, r, "Matricule", "Participant", "Direction", "A répondu");
            for (Participant p : d.participants()) {
                Row row = s6.createRow(r++);
                txt(row, 0, p.matricule(), st.cell); txt(row, 1, p.nom(), st.cell); txt(row, 2, p.direction(), st.cell); txt(row, 3, p.repondu() ? "Oui" : "Non", st.cell);
            }
            largeurs(s6, 13, 32, 46, 12);
            if (r > debut + 1) s6.setAutoFilter(new CellRangeAddress(debut, r - 1, 0, 3));

            wb.write(out);
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export Excel impossible : " + ex.getMessage(), ex); }
    }

    private static final class St {
        final CellStyle titre, sous, head, cell, num, dec, dec2, pct;
        St(Workbook wb) {
            Font fT = wb.createFont(); fT.setBold(true); fT.setFontHeightInPoints((short) 14); fT.setColor(IndexedColors.DARK_BLUE.getIndex());
            titre = wb.createCellStyle(); titre.setFont(fT);
            Font fS = wb.createFont(); fS.setItalic(true); fS.setFontHeightInPoints((short) 9); fS.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            sous = wb.createCellStyle(); sous.setFont(fS);
            Font fH = wb.createFont(); fH.setBold(true); fH.setColor(IndexedColors.WHITE.getIndex()); fH.setFontHeightInPoints((short) 10);
            head = wb.createCellStyle(); head.setFont(fH); head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setWrapText(true); head.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER); bord(head);
            cell = wb.createCellStyle(); bord(cell); cell.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP); cell.setWrapText(true);
            num = wb.createCellStyle(); bord(num); num.setAlignment(HorizontalAlignment.RIGHT);
            DataFormat f = wb.createDataFormat();
            dec = wb.createCellStyle(); dec.cloneStyleFrom(num); dec.setDataFormat(f.getFormat("0.0"));
            dec2 = wb.createCellStyle(); dec2.cloneStyleFrom(num); dec2.setDataFormat(f.getFormat("0.00"));
            pct = wb.createCellStyle(); pct.cloneStyleFrom(num); pct.setDataFormat(f.getFormat("0%"));
        }
        private static void bord(CellStyle c) {
            c.setBorderBottom(BorderStyle.THIN); c.setBorderTop(BorderStyle.THIN); c.setBorderLeft(BorderStyle.THIN); c.setBorderRight(BorderStyle.THIN);
            short g = IndexedColors.GREY_25_PERCENT.getIndex(); c.setBottomBorderColor(g); c.setTopBorderColor(g); c.setLeftBorderColor(g); c.setRightBorderColor(g);
        }
    }

    /** Note d'une réponse brute, écrite « 3 » ou « 3 - Modérément » ; null si illisible. */
    private static Long noteDe(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)").matcher(v);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }
    private int blocXls(Sheet s, St st, int r, String titre, String texte, CellStyle style) {
        Row h = s.createRow(r++); txt(h, 0, titre, st.head);
        Row b = s.createRow(r++); txt(b, 0, texte, style == null ? st.cell : style);
        s.addMergedRegion(new CellRangeAddress(b.getRowNum(), b.getRowNum(), 0, 2));
        b.setHeightInPoints(Math.max(15, 15 * (1 + (texte == null ? 0 : texte.length()) / 140)));
        return r + 1;
    }
    private int constatsXls(Sheet s, St st, int r, String titre, List<ConstatChiffre> l) {
        if (l == null || l.isEmpty()) return r;
        r = ligneEntete(s, st, r, titre, "Chiffres à l'appui");
        for (ConstatChiffre x : l) { Row row = s.createRow(r++); txt(row, 0, x.constat(), st.cell); txt(row, 1, x.chiffres(), st.cell); }
        return r + 1;
    }

    private int entete(Sheet s, St st, EvalChaud c, String titre) {
        txt(s.createRow(0), 0, "BGFIBank RDC - HRX · " + titre, st.titre);
        txt(s.createRow(1), 0, "Formation : " + c.getIntitule() + infosFormation(c), st.sous);
        txt(s.createRow(2), 0, "Questionnaire " + c.getStatutLabel().toLowerCase() + periode(c) + " · édité le " + new SimpleDateFormat("dd/MM/yyyy").format(new Date()) + " · document confidentiel", st.sous);
        return 4;
    }
    private int ligneEntete(Sheet s, St st, int r, String... h) {
        Row row = s.createRow(r); row.setHeightInPoints(30);
        for (int i = 0; i < h.length; i++) txt(row, i, h[i], st.head);
        return r + 1;
    }
    private int kv(Sheet s, St st, int r, String k, String v) { Row row = s.createRow(r); txt(row, 0, k, st.cell); txt(row, 1, v, st.num); return r + 1; }
    private static void txt(Row r, int c, String v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v == null ? "" : v); x.setCellStyle(st); }
    private static void num(Row r, int c, long v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v); x.setCellStyle(st); }
    private static void dec(Row r, int c, Double v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); if (v != null) x.setCellValue(v); x.setCellStyle(st); }
    private static void pct(Row r, int c, Integer v, St st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); if (v != null) x.setCellValue(v / 100.0); x.setCellStyle(st.pct); }
    private static void largeurs(Sheet s, int... w) { for (int i = 0; i < w.length; i++) s.setColumnWidth(i, w[i] * 256); }

    // ------------------------------------------------------------------ PDF

    private static final Color NAVY = new DeviceRgb(7, 41, 74), GOLD = new DeviceRgb(143, 163, 116) /* vert sauge du logo BGFI (filets) */, GRIS = new DeviceRgb(107, 114, 128),
            LIGNE = new DeviceRgb(220, 226, 232), FOND = new DeviceRgb(244, 247, 250), VERT = new DeviceRgb(46, 125, 80),
            ORANGE = new DeviceRgb(196, 132, 29), ROUGE = new DeviceRgb(185, 56, 56), PISTE = new DeviceRgb(229, 233, 238);

    public static final String MENTION_IA = "Analyse rédigée par Claude (IA, Anthropic) à partir des seuls chiffres et réponses anonymes de ce rapport. À relire : elle éclaire la décision sans la remplacer.";

    public byte[] pdf(Depouillement d) { return pdf(d, null, null); }

    /** Rapport PDF ; avec l'analyse de Claude (ou la raison de son absence) après les indicateurs clés. */
    public byte[] pdf(Depouillement d, AnalyseFormation ia, String iaIndispo) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            Document doc = new Document(pdf, PageSize.A4);
            doc.setMargins(30, 32, 34, 32);
            PdfFont reg = PdfFontFactory.createFont(StandardFonts.HELVETICA), bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            doc.setFont(reg).setFontSize(9);
            EvalChaud c = d.evalChaud();

            // En-tête
            Table head = new Table(UnitValue.createPercentArray(new float[]{22, 78})).useAllAvailableWidth();
            byte[] lg = logo();
            Cell cl = new Cell().setBorder(Border.NO_BORDER).setVerticalAlignment(VerticalAlignment.MIDDLE);
            if (lg != null) cl.add(new Image(com.itextpdf.io.image.ImageDataFactory.create(lg)).setHeight(32)); else cl.add(new Paragraph("BGFIBank").setFont(bold).setFontSize(14).setFontColor(NAVY));
            head.addCell(cl);
            head.addCell(new Cell().setBorder(Border.NO_BORDER).setTextAlignment(TextAlignment.RIGHT)
                    .add(new Paragraph(c.isFroid() ? "DÉPOUILLEMENT · ÉVALUATION À FROID" : "DÉPOUILLEMENT · ÉVALUATION À CHAUD").setFont(bold).setFontSize(14).setFontColor(NAVY).setMargin(0))
                    .add(new Paragraph(c.getIntitule()).setFont(bold).setFontSize(10.5f).setMargin(0))
                    .add(new Paragraph(infosFormation(c).replaceFirst("^ · ", "")).setFontSize(8.5f).setFontColor(GRIS).setMargin(0))
                    .add(new Paragraph("Questionnaire " + c.getStatutLabel().toLowerCase() + periode(c) + " · édité le " + new SimpleDateFormat("dd/MM/yyyy").format(new Date())).setFontSize(8.5f).setFontColor(GRIS).setMargin(0)));
            doc.add(head);
            doc.add(new Paragraph().setBorderBottom(new SolidBorder(GOLD, 2)).setMarginTop(4).setMarginBottom(12));

            // Indicateurs clés
            Table k = new Table(UnitValue.createPercentArray(5)).useAllAvailableWidth().setMarginBottom(6);
            kpi(k, bold, d.nbRepondants() + " / " + d.nbParticipants(), "Réponses / participants", NAVY);
            kpi(k, bold, d.taux() + " %", "Taux de réponse", couleurPct(d.taux()));
            kpi(k, bold, d.satisfaction() == null ? "—" : fmt(d.satisfaction()) + " /5", "Satisfaction moyenne", couleurNote(d.satisfaction()));
            kpi(k, bold, d.pctSatisfaits() == null ? "—" : d.pctSatisfaits() + " %", "Notes favorables (4-5)", d.pctSatisfaits() == null ? NAVY : couleurPct(d.pctSatisfaits()));
            kpi(k, bold, d.pctRecommandation() == null ? "—" : d.pctRecommandation() + " %", "Réponses « oui »", d.pctRecommandation() == null ? NAVY : couleurPct(d.pctRecommandation()));
            doc.add(k);
            doc.add(new Paragraph(lecture(d)).setFontSize(9).setFontColor(NAVY).setBackgroundColor(FOND).setPadding(7).setMarginBottom(4));

            // Analyse de Claude
            if (ia != null) analysePdf(doc, reg, bold, ia);
            else if (iaIndispo != null) {
                titre(doc, bold, "Analyse des résultats par Claude (IA)");
                doc.add(new Paragraph(iaIndispo).setFontSize(8.5f).setFontColor(GRIS).setItalic());
            }

            // 1. Synthèse par question
            titre(doc, bold, "1. Synthèse par question");
            Table t1 = new Table(UnitValue.createPercentArray(new float[]{4, 40, 9, 9, 24, 14})).useAllAvailableWidth();
            for (String h : new String[]{"N°", "Question", "Réponses", "Moyenne", "Répartition défavorable · neutre · favorable", "Indicateur"}) t1.addHeaderCell(th(h, bold));
            int i = 1; boolean alt = false;
            for (QuestionAnalyse q : d.questions()) {
                EvalChaudService.Stat s = q.stat(); EvalChaudQuestion qu = s.question();
                Color bg = alt ? FOND : DeviceRgb.WHITE; alt = !alt;
                t1.addCell(c(String.valueOf(i++), reg, bg, TextAlignment.CENTER));
                t1.addCell(c(qu.getLibelle(), reg, bg, TextAlignment.LEFT));
                t1.addCell(c(String.valueOf(s.nb()), reg, bg, TextAlignment.RIGHT));
                if (qu.isNote()) {
                    t1.addCell(c(s.moyenne() == null ? "—" : fmt(s.moyenne()), bold, bg, TextAlignment.RIGHT).setFontColor(couleurNote(s.moyenne())));
                    t1.addCell(new Cell().add(s.nb() == 0 ? new Paragraph("—").setFontColor(GRIS) : barre3(q.pctDefavorable(), q.pctNeutre(), q.pctFavorable()))
                            .setBackgroundColor(bg).setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LIGNE, .5f)).setPadding(5).setVerticalAlignment(VerticalAlignment.MIDDLE));
                    t1.addCell(c(s.nb() == 0 ? "" : q.pctFavorable() + (qu.isCentree() ? " % adapté (3)" : " % favorables"), reg, bg, TextAlignment.RIGHT));
                } else if (qu.isOuiNon()) {
                    t1.addCell(c("—", reg, bg, TextAlignment.RIGHT));
                    long b = s.oui() + s.non();
                    t1.addCell(new Cell().add(b == 0 ? new Paragraph("—").setFontColor(GRIS) : barre3((int) (100 - s.getPctOui()), 0, s.getPctOui()))
                            .setBackgroundColor(bg).setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LIGNE, .5f)).setPadding(5).setVerticalAlignment(VerticalAlignment.MIDDLE));
                    t1.addCell(c(b == 0 ? "" : s.getPctOui() + " % oui", reg, bg, TextAlignment.RIGHT));
                } else {
                    t1.addCell(c("—", reg, bg, TextAlignment.RIGHT));
                    t1.addCell(c(EvalChaudQuestion.typeLabel(qu.getType()), reg, bg, TextAlignment.LEFT).setFontColor(GRIS));
                    t1.addCell(c(s.textes().size() + (qu.isPlan() ? " action(s)" : qu.isListe() ? " point(s)" : qu.isNiveaux() ? " niveau(x)" : qu.isMulti() || qu.isObjectifs() ? " précision(s)" : " commentaire(s)"), reg, bg, TextAlignment.RIGHT));
                }
            }
            doc.add(t1);
            doc.add(new Paragraph("Légende : rouge = notes 1-2, gris = note 3, vert = notes 4-5 (pour les questions oui/non : rouge = non, vert = oui).").setFontSize(7.5f).setFontColor(GRIS).setMarginTop(3));

            // 2. Points forts / axes d'amélioration
            List<QuestionAnalyse> cls = d.classement();
            if (cls.size() >= 2) {
                titre(doc, bold, "2. Points forts et axes d'amélioration");
                Table t2 = new Table(UnitValue.createPercentArray(2)).useAllAvailableWidth();
                t2.addCell(liste(reg, bold, "Points forts", cls.subList(0, Math.min(3, cls.size())), VERT));
                List<QuestionAnalyse> inv = new java.util.ArrayList<>(cls); java.util.Collections.reverse(inv);
                t2.addCell(liste(reg, bold, "Axes d'amélioration", inv.subList(0, Math.min(3, inv.size())), ROUGE));
                doc.add(t2);
            }

            // 3. Distribution détaillée des notes
            titre(doc, bold, "3. Distribution détaillée des notes");
            Table t3 = new Table(UnitValue.createPercentArray(new float[]{31, 9, 9, 9, 9, 9, 8, 8, 8})).useAllAvailableWidth();
            for (String h : new String[]{"Question", "5", "4", "3", "2", "1", "Moyenne", "Médiane", "Écart-type"}) t3.addHeaderCell(th(h, bold));
            alt = false; boolean aucune = true;
            for (QuestionAnalyse q : d.questions()) {
                EvalChaudService.Stat s = q.stat();
                if (!s.question().isNote()) continue;
                aucune = false;
                Color bg = alt ? FOND : DeviceRgb.WHITE; alt = !alt;
                t3.addCell(c(s.question().getLibelle(), reg, bg, TextAlignment.LEFT));
                for (int n = 5; n >= 1; n--) t3.addCell(c(s.repartition()[n] + (s.nb() == 0 ? "" : " (" + s.pct(s.repartition()[n]) + "%)"), reg, bg, TextAlignment.CENTER));
                t3.addCell(c(s.moyenne() == null ? "—" : fmt(s.moyenne()), bold, bg, TextAlignment.RIGHT));
                t3.addCell(c(q.mediane() == null ? "—" : fmt(q.mediane()), reg, bg, TextAlignment.RIGHT));
                t3.addCell(c(q.ecartType() == null ? "—" : String.format("%.2f", q.ecartType()), reg, bg, TextAlignment.RIGHT));
            }
            if (aucune) doc.add(new Paragraph("Aucune question notée dans ce questionnaire.").setFontColor(GRIS).setItalic()); else doc.add(t3);

            // 4. Par direction
            titre(doc, bold, "4. Participation et satisfaction par direction");
            Table t4 = new Table(UnitValue.createPercentArray(new float[]{46, 12, 12, 14, 16})).useAllAvailableWidth();
            for (String h : new String[]{"Direction", "Participants", "Répondants", "Taux", "Satisfaction /5"}) t4.addHeaderCell(th(h, bold));
            alt = false;
            for (DirectionAnalyse a : d.directions()) {
                Color bg = alt ? FOND : DeviceRgb.WHITE; alt = !alt;
                t4.addCell(c(a.direction(), reg, bg, TextAlignment.LEFT));
                t4.addCell(c(String.valueOf(a.participants()), reg, bg, TextAlignment.RIGHT));
                t4.addCell(c(String.valueOf(a.repondants()), reg, bg, TextAlignment.RIGHT));
                t4.addCell(c(a.taux() + " %", bold, bg, TextAlignment.RIGHT).setFontColor(couleurPct(a.taux())));
                t4.addCell(a.satisfaction() != null ? c(fmt(a.satisfaction()), bold, bg, TextAlignment.RIGHT).setFontColor(couleurNote(a.satisfaction()))
                        : c(a.masquee() ? "masquée*" : "—", reg, bg, TextAlignment.RIGHT).setFontColor(GRIS));
            }
            doc.add(t4);
            doc.add(new Paragraph("* " + ANONYMAT).setFontSize(7.5f).setFontColor(GRIS).setMarginTop(3));

            // 5. Verbatims
            titre(doc, bold, "5. Verbatims");
            boolean verb = false;
            for (QuestionAnalyse q : d.questions()) {
                if (q.stat().textes().isEmpty()) continue;
                verb = true;
                doc.add(new Paragraph(q.stat().question().getLibelle() + " (" + q.stat().textes().size() + ")").setFont(bold).setFontSize(9).setFontColor(NAVY).setMarginTop(6).setMarginBottom(3));
                for (String t : q.stat().textes())
                    doc.add(new Paragraph("« " + t + " »").setFontSize(8.5f).setMarginLeft(8).setPaddingLeft(6).setBorderLeft(new SolidBorder(GOLD, 1.5f)).setMarginBottom(3));
            }
            if (!verb) doc.add(new Paragraph("Aucune réponse libre.").setFontColor(GRIS).setItalic());

            doc.add(new Paragraph("HRX - Direction du Capital Humain et de la Transformation · Réponses anonymes · Document confidentiel, diffusion restreinte.")
                    .setFontSize(7.5f).setFontColor(GRIS).setMarginTop(14));
            doc.close();
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Export PDF impossible : " + ex.getMessage(), ex); }
    }

    private static void analysePdf(Document doc, PdfFont reg, PdfFont bold, AnalyseFormation ia) {
        titre(doc, bold, "Analyse des résultats par Claude (IA)");
        Color cf = "FIABLE".equalsIgnoreCase(ia.fiabilite()) ? VERT : "INSUFFISANTE".equalsIgnoreCase(ia.fiabilite()) ? ROUGE : ORANGE;
        doc.add(new Paragraph().add(new com.itextpdf.layout.element.Text("Fiabilité : " + (ia.fiabilite() == null ? "—" : ia.fiabilite().toLowerCase()) + "  ").setFont(bold).setFontColor(cf))
                .add(new com.itextpdf.layout.element.Text(ia.fiabiliteJustification() == null ? "" : ia.fiabiliteJustification()).setFontColor(GRIS)).setFontSize(8.5f).setMarginBottom(4));
        doc.add(new Paragraph(ia.synthese() == null ? "" : ia.synthese()).setFontSize(9).setMarginBottom(6).setPaddingLeft(8).setBorderLeft(new SolidBorder(NAVY, 2)));
        if (ia.efficaciteTransfert() != null && !ia.efficaciteTransfert().isBlank()) {
            doc.add(new Paragraph("Efficacité et transfert au poste").setFont(bold).setFontSize(9).setFontColor(NAVY).setMarginTop(4).setMarginBottom(2));
            doc.add(new Paragraph(ia.efficaciteTransfert()).setFontSize(9).setMarginBottom(6));
        }
        Table t = new Table(UnitValue.createPercentArray(2)).useAllAvailableWidth().setMarginBottom(4);
        t.addCell(constatsPdf(reg, bold, "Points forts", ia.pointsForts(), VERT));
        t.addCell(constatsPdf(reg, bold, "Points de vigilance", ia.pointsVigilance(), ROUGE));
        doc.add(t);
        if (ia.ecartsSignificatifs() != null && !ia.ecartsSignificatifs().isEmpty() || ia.themesVerbatims() != null && !ia.themesVerbatims().isEmpty()) {
            Table t2 = new Table(UnitValue.createPercentArray(2)).useAllAvailableWidth().setMarginBottom(4);
            t2.addCell(constatsPdf(reg, bold, "Écarts significatifs", ia.ecartsSignificatifs(), NAVY));
            t2.addCell(constatsPdf(reg, bold, "Thèmes des réponses libres", ia.themesVerbatims(), GOLD));
            doc.add(t2);
        }
        if (ia.recommandations() != null && !ia.recommandations().isEmpty()) {
            Table r = new Table(UnitValue.createPercentArray(new float[]{12, 44, 44})).useAllAvailableWidth().setMarginTop(4);
            for (String h : new String[]{"Priorité", "Recommandation", "Justification"}) r.addHeaderCell(th(h, bold));
            boolean alt = false;
            for (Recommandation rc : ia.recommandations()) {
                Color bg = alt ? FOND : DeviceRgb.WHITE; alt = !alt;
                String p = rc.priorite() == null ? "" : rc.priorite().toUpperCase();
                r.addCell(c(p.isEmpty() ? "—" : p.charAt(0) + p.substring(1).toLowerCase(), bold, bg, TextAlignment.CENTER).setFontColor(p.startsWith("HAUT") ? ROUGE : p.startsWith("MOY") ? ORANGE : GRIS));
                r.addCell(c(rc.action(), reg, bg, TextAlignment.LEFT));
                r.addCell(c(rc.justification(), reg, bg, TextAlignment.LEFT).setFontColor(GRIS));
            }
            doc.add(r);
        }
        doc.add(new Paragraph(MENTION_IA).setFontSize(7.5f).setFontColor(GRIS).setItalic().setMarginTop(3));
    }
    private static Cell constatsPdf(PdfFont reg, PdfFont bold, String titre, List<ConstatChiffre> l, Color col) {
        Cell cell = new Cell().setBorder(Border.NO_BORDER).setPadding(4).add(new Paragraph(titre).setFont(bold).setFontSize(9).setFontColor(col).setMarginBottom(2));
        if (l == null || l.isEmpty()) return cell.add(new Paragraph("—").setFontColor(GRIS).setFontSize(8.5f));
        for (ConstatChiffre x : l) {
            Paragraph p = new Paragraph().setFontSize(8.5f).setMarginBottom(3).setPaddingLeft(6).setBorderLeft(new SolidBorder(col, 1.5f))
                    .add(new com.itextpdf.layout.element.Text(x.constat() == null ? "" : x.constat()));
            if (x.chiffres() != null && !x.chiffres().isBlank()) p.add(new com.itextpdf.layout.element.Text("\n" + x.chiffres()).setFontColor(GRIS).setFontSize(7.5f));
            cell.add(p);
        }
        return cell;
    }

    /** Phrase de lecture automatique des résultats. */
    private static String lecture(Depouillement d) {
        if (d.nbRepondants() == 0) return "Aucune réponse n'a encore été reçue : les résultats ne peuvent pas être interprétés.";
        StringBuilder sb = new StringBuilder();
        sb.append(d.nbRepondants()).append(" participant(s) sur ").append(d.nbParticipants()).append(" ont répondu (").append(d.taux()).append(" %)");
        if (d.taux() < 50) sb.append(", une base encore fragile pour conclure");
        sb.append(". ");
        if (d.satisfaction() != null) {
            sb.append("La satisfaction moyenne est de ").append(fmt(d.satisfaction())).append("/5, ")
              .append(d.satisfaction() >= 4 ? "un très bon niveau" : d.satisfaction() >= 3 ? "un niveau correct avec des marges de progrès" : "un niveau insuffisant qui appelle des actions")
              .append(". ");
        }
        List<QuestionAnalyse> cl = d.classement();
        if (cl.size() >= 2) {
            sb.append("Le point le mieux noté est « ").append(cl.get(0).stat().question().getLibelle()).append(" » (").append(fmt(cl.get(0).stat().moyenne())).append(")");
            QuestionAnalyse b = cl.get(cl.size() - 1);
            sb.append(", le plus faible « ").append(b.stat().question().getLibelle()).append(" » (").append(fmt(b.stat().moyenne())).append(").");
        }
        return sb.toString();
    }

    private static Table barre3(int def, int neu, int fav) {
        int[] v = {def, neu, fav}; Color[] col = {ROUGE, PISTE, VERT};
        int n = 0; for (int x : v) if (x > 0) n++;
        if (n == 0) return new Table(1).useAllAvailableWidth();
        float[] w = new float[n]; Color[] cc = new Color[n]; int j = 0;
        for (int k = 0; k < 3; k++) if (v[k] > 0) { w[j] = v[k]; cc[j++] = col[k]; }
        Table t = new Table(UnitValue.createPercentArray(w)).useAllAvailableWidth();
        for (int k = 0; k < n; k++) t.addCell(new Cell().setHeight(8).setBackgroundColor(cc[k]).setBorder(Border.NO_BORDER).setPadding(0));
        return t;
    }
    private static Cell liste(PdfFont reg, PdfFont bold, String titre, List<QuestionAnalyse> l, Color col) {
        Cell cell = new Cell().setBorder(new SolidBorder(LIGNE, .8f)).setBorderLeft(new SolidBorder(col, 3)).setPadding(8).setMarginRight(6);
        cell.add(new Paragraph(titre).setFont(bold).setFontSize(9.5f).setFontColor(col).setMarginBottom(4));
        for (QuestionAnalyse q : l)
            cell.add(new Paragraph(q.stat().question().getLibelle() + "  ").setFontSize(8.5f).setMargin(0).setMarginBottom(2)
                    .add(new com.itextpdf.layout.element.Text(fmt(q.stat().moyenne()) + "/5").setFont(bold)));
        return cell;
    }
    private static void kpi(Table k, PdfFont bold, String v, String l, Color col) {
        k.addCell(new Cell().setBorder(new SolidBorder(LIGNE, .8f)).setBackgroundColor(FOND).setPadding(7)
                .add(new Paragraph(v).setFont(bold).setFontSize(15).setFontColor(col).setMargin(0))
                .add(new Paragraph(l).setFontSize(7.5f).setFontColor(GRIS).setMargin(0)));
    }
    private static void titre(Document doc, PdfFont bold, String t) {
        doc.add(new Paragraph(t).setFont(bold).setFontSize(11).setFontColor(NAVY).setMarginTop(12).setMarginBottom(6));
    }
    private static Cell th(String h, PdfFont bold) {
        return new Cell().add(new Paragraph(h).setFont(bold).setFontSize(8).setFontColor(DeviceRgb.WHITE)).setBackgroundColor(NAVY).setBorder(new SolidBorder(NAVY, .5f)).setPadding(4);
    }
    private static Cell c(String v, PdfFont f, Color bg, TextAlignment al) {
        return new Cell().add(new Paragraph(v == null ? "" : v).setFont(f).setFontSize(8.5f).setMargin(0)).setBackgroundColor(bg).setTextAlignment(al)
                .setBorder(Border.NO_BORDER).setBorderBottom(new SolidBorder(LIGNE, .5f)).setPadding(4).setVerticalAlignment(VerticalAlignment.MIDDLE);
    }
    private static Color couleurNote(Double m) { return m == null ? NAVY : m >= 4 ? VERT : m >= 3 ? ORANGE : ROUGE; }
    private static Color couleurPct(int p) { return p >= 75 ? VERT : p >= 50 ? ORANGE : ROUGE; }
    private static String fmt(Double v) { return v == null ? "—" : String.format("%.1f", v); }

    private static String infosFormation(EvalChaud c) {
        StringBuilder sb = new StringBuilder();
        if (c.getOrganisme() != null && !c.getOrganisme().isBlank()) sb.append(" · ").append(c.getOrganisme());
        if (c.getFormateur() != null && !c.getFormateur().isBlank()) sb.append(" · formateur : ").append(c.getFormateur());
        LocalDate a = c.getDateFormationDebut(), b = c.getDateFormationFin();
        if (a != null) sb.append(" · formation du ").append(a.format(D)).append(b == null || b.equals(a) ? "" : " au " + b.format(D));
        return sb.toString();
    }
    private static String periode(EvalChaud c) {
        return (c.getDateDebut() == null ? "" : " du " + c.getDateDebut().format(D)) + (c.getDateFin() == null ? "" : " au " + c.getDateFin().format(D));
    }
    private static byte[] logo() {
        try {
            FacesContext ctx = FacesContext.getCurrentInstance();
            if (ctx != null) try (InputStream in = ctx.getExternalContext().getResourceAsStream("/resources/hrx/img/logo_bgfi.png")) { if (in != null) return in.readAllBytes(); }
        } catch (Throwable ignored) { }
        return null;
    }
}
