package cd.mura.hrx.entity.config;

import java.util.Locale;

/** Thèmes visuels disponibles pour l'espace personnel */
public enum UserTheme {
    BGFI_ELEGANCE("Élégance ", "hrx-theme-bgfi-elegance"),
    MIDNIGHT_EXECUTIVE("Midnight Executive", "hrx-theme-midnight-executive"),
    AURORA_GLASS("Aurora Glass", "hrx-theme-aurora-glass"),
    EMERALD_BOARDROOM("Emerald Boardroom", "hrx-theme-emerald-boardroom"),
    COBALT_MODERN("Cobalt Modern", "hrx-theme-cobalt-modern"),
    HORIZON_LIGHT("Horizon Light", "hrx-theme-horizon-light");

    /** Thème appliqué aux nouveaux utilisateurs et en l'absence de préférence enregistrée. */
    public static final UserTheme DEFAULT = BGFI_ELEGANCE;

    private final String label;
    private final String bodyClass;

    UserTheme(String label, String bodyClass) {
        this.label = label;
        this.bodyClass = bodyClass;
    }

    public String getLabel() {
        return label;
    }

    public String getBodyClass() {
        return bodyClass;
    }

    public static UserTheme fromCode(String code) {
        if (code == null || code.isBlank()) return DEFAULT;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
