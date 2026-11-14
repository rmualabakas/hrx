package cd.mura.hrx.utils;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.primefaces.PrimeFaces;

/**
 * Confirmation d'une étape importante (transmission, validation, signature...) affichée dans une
 * fenêtre stylée plutôt que dans un growl. Le rendu est fait côté client par hxDialogue (hrx-shell.js).
 * Hors requête ajax, se replie sur un message classique.
 */
public final class Dialogue {

    public static final String SUCCES = "succes", INFO = "info", ALERTE = "alerte";

    private Dialogue() { }

    public static void succes(String titre, String texte) { afficher(SUCCES, titre, texte, null, null); }
    public static void info(String titre, String texte) { afficher(INFO, titre, texte, null, null); }
    public static void alerte(String titre, String texte) { afficher(ALERTE, titre, texte, null, null); }
    /** Avec un bouton de navigation (lien relatif au contexte, ex. "/ui/periodeEssai?id=3"). */
    public static void succes(String titre, String texte, String lienLibelle, String lien) { afficher(SUCCES, titre, texte, lienLibelle, lien); }

    public static void afficher(String type, String titre, String texte, String lienLibelle, String lien) {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) return;
        if (!fc.getPartialViewContext().isAjaxRequest()) {
            fc.addMessage(null, new FacesMessage(ALERTE.equals(type) ? FacesMessage.SEVERITY_WARN : FacesMessage.SEVERITY_INFO, titre, texte));
            return;
        }
        String url = lien == null || lien.isBlank() ? null : fc.getExternalContext().getRequestContextPath() + lien;
        PrimeFaces.current().executeScript("hxDialogue({type:" + js(type) + ",titre:" + js(titre) + ",texte:" + js(texte)
                + ",lienLibelle:" + js(lienLibelle) + ",lien:" + js(url) + "})");
    }

    /** Littéral JavaScript sûr (null, ou chaîne échappée, y compris les séquences HTML). */
    static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '<' -> b.append("\\u003c");
                case '>' -> b.append("\\u003e");
                case '&' -> b.append("\\u0026");
                case '\'' -> b.append("\\u0027");
                case ' ' -> b.append("\\u2028");
                case ' ' -> b.append("\\u2029");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
