package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.bean.recrutement.RecrutementView;
import cd.mura.hrx.bean.recrutement.RecrutementView.Ligne;
import cd.mura.hrx.entity.recrutement.Poste;
import cd.mura.hrx.entity.recrutement.PosteCritere;
import cd.mura.hrx.service.recrutement.RecrutementService.Detail;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/** Classement d'un poste au format Excel : une ligne par candidat, une colonne par critère. */
@ApplicationScoped
public class RecrutementExportService implements Serializable {

    private static final long serialVersionUID = 1L;

    public byte[] excel(Poste p, List<Ligne> lignes, RecrutementView vue) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Classement");
            Font fT = wb.createFont(); fT.setBold(true); fT.setFontHeightInPoints((short) 14); fT.setColor(IndexedColors.DARK_BLUE.getIndex());
            CellStyle titre = wb.createCellStyle(); titre.setFont(fT);
            Font fS = wb.createFont(); fS.setItalic(true); fS.setFontHeightInPoints((short) 9); fS.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            CellStyle sous = wb.createCellStyle(); sous.setFont(fS);
            Font fH = wb.createFont(); fH.setBold(true); fH.setColor(IndexedColors.WHITE.getIndex());
            CellStyle head = wb.createCellStyle(); head.setFont(fH); head.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND); head.setWrapText(true); head.setVerticalAlignment(VerticalAlignment.CENTER);
            CellStyle cell = wb.createCellStyle(); cell.setWrapText(true); cell.setVerticalAlignment(VerticalAlignment.TOP);
            CellStyle vert = couleur(wb, new byte[]{(byte) 0xD8, (byte) 0xEF, (byte) 0xDF}), orange = couleur(wb, new byte[]{(byte) 0xFB, (byte) 0xEB, (byte) 0xCC}),
                    rouge = couleur(wb, new byte[]{(byte) 0xF6, (byte) 0xD5, (byte) 0xD5}), gris = couleur(wb, new byte[]{(byte) 0xE5, (byte) 0xE7, (byte) 0xEB});

            List<PosteCritere> criteres = p.getCriteres();
            Row r0 = s.createRow(0); txt(r0, 0, "BGFIBank RDC - HRX · Présélection des CV : " + p.getIntitule(), titre);
            Row r1 = s.createRow(1); txt(r1, 0, "Seuils : bon ≥ " + p.getSeuilBon() + " %, moyen ≥ " + p.getSeuilMoyen() + " % · analyse assistée par IA, à valider par le recruteur · édité le "
                    + new SimpleDateFormat("dd/MM/yyyy").format(new Date()) + " · document confidentiel", sous);
            Row rh = s.createRow(3); rh.setHeightInPoints(42);
            String[] fixes = {"Rang", "Candidat", "Fichier", "E-mail", "Téléphone", "Âge", "Expérience (ans)", "Niveau d'études", "Score %", "Appréciation", "Action suggérée", "Décision"};
            int col = 0;
            for (String h : fixes) txt(rh, col++, h, head);
            for (PosteCritere c : criteres) txt(rh, col++, c.getResume() + " (×" + c.getPonderation() + (c.isEliminatoire() ? ", éliminatoire" : "") + ")", head);
            txt(rh, col, "Synthèse", head);

            int r = 4;
            for (Ligne x : lignes) {
                Row row = s.createRow(r++);
                String cl = vue.classe(x);
                CellStyle sc = switch (cl) { case "bon" -> vert; case "moyen" -> orange; case "faible" -> rouge; default -> gris; };
                int k = 0;
                if (x.getRang() > 0) row.createCell(k++).setCellValue(x.getRang()); else txt(row, k++, "", cell);
                txt(row, k++, x.getCv().getCandidatNom(), cell); txt(row, k++, x.getCv().getNomFichier(), cell);
                txt(row, k++, x.getCv().getEmail(), cell); txt(row, k++, x.getCv().getTelephone(), cell);
                if (x.getCv().getAge() != null) row.createCell(k++).setCellValue(x.getCv().getAge()); else txt(row, k++, "", cell);
                if (x.getCv().getExperienceAnnees() != null) row.createCell(k++).setCellValue(x.getCv().getExperienceAnnees()); else txt(row, k++, "", cell);
                txt(row, k++, x.getCv().getNiveauEtudes() == null ? "" : PosteCritere.niveauLabel(x.getCv().getNiveauEtudes()), cell);
                org.apache.poi.ss.usermodel.Cell cs = row.createCell(k++); if (x.getScore() != null) cs.setCellValue(x.getScore()); cs.setCellStyle(sc);
                txt(row, k++, switch (cl) { case "bon" -> "Bon"; case "moyen" -> "Moyen"; case "faible" -> "Faible"; case "elimine" -> "Écarté (critère éliminatoire)";
                    case "erreur" -> "Erreur : " + x.getErreur(); default -> "Non analysé"; }, sc);
                txt(row, k++, vue.suggestionLabel(x), sc);
                txt(row, k++, x.getDecisionLabel(), cell);
                for (PosteCritere c : criteres) {
                    Detail d = x.getDetails().get(c.getId());
                    txt(row, k++, d == null ? "" : vue.resultatLabel(d) + " : " + d.justification(), cell);
                }
                txt(row, k, x.getSynthese(), cell);
            }
            s.setColumnWidth(0, 6 * 256); s.setColumnWidth(1, 26 * 256); s.setColumnWidth(2, 26 * 256); s.setColumnWidth(3, 26 * 256);
            s.setColumnWidth(4, 15 * 256); s.setColumnWidth(5, 7 * 256); s.setColumnWidth(6, 11 * 256); s.setColumnWidth(7, 22 * 256);
            s.setColumnWidth(8, 9 * 256); s.setColumnWidth(9, 18 * 256); s.setColumnWidth(10, 18 * 256); s.setColumnWidth(11, 18 * 256);
            for (int i = 0; i < criteres.size(); i++) s.setColumnWidth(12 + i, 34 * 256);
            s.setColumnWidth(12 + criteres.size(), 60 * 256);
            s.createFreezePane(2, 4);
            if (r > 4) s.setAutoFilter(new CellRangeAddress(3, r - 1, 0, 12 + criteres.size()));
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) { throw new IllegalStateException("Export Excel impossible : " + e.getMessage(), e); }
    }

    private static CellStyle couleur(XSSFWorkbook wb, byte[] rgb) {
        XSSFCellStyle st = wb.createCellStyle();
        st.setFillForegroundColor(new XSSFColor(rgb, null)); st.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        st.setVerticalAlignment(VerticalAlignment.TOP); st.setWrapText(true);
        return st;
    }
    private static void txt(Row r, int c, String v, CellStyle st) { org.apache.poi.ss.usermodel.Cell x = r.createCell(c); x.setCellValue(v == null ? "" : v); x.setCellStyle(st); }
}
