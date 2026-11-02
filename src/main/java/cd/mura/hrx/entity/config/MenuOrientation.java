package cd.mura.hrx.entity.config;

import java.util.Locale;

/** Dispositions de navigation disponibles pour l'espace personnel. */
public enum MenuOrientation {
    SIDEBAR_LEFT("Latéral gauche", "hrx-menu-left", false),
    SIDEBAR_RIGHT("Latéral droit", "hrx-menu-right", false),
    HORIZONTAL("Horizontal", "hrx-menu-horizontal", false),
    RTL("RTL", "hrx-menu-rtl", true);

    public static final MenuOrientation DEFAULT = SIDEBAR_LEFT;

    private final String label;
    private final String bodyClass;
    private final boolean rtl;

    MenuOrientation(String label, String bodyClass, boolean rtl) {
        this.label = label;
        this.bodyClass = bodyClass;
        this.rtl = rtl;
    }

    public String getLabel() { return label; }
    public String getBodyClass() { return bodyClass; }
    public boolean isRtl() { return rtl; }

    public static MenuOrientation fromCode(String code) {
        if (code == null || code.isBlank()) return DEFAULT;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
