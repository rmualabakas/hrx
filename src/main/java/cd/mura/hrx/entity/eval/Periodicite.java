package cd.mura.hrx.entity.eval;

/** Fréquence à laquelle l'atteinte d'un objectif est mesurée. */
public enum Periodicite {
    MENSUEL("Mensuel", 12), BIMESTRIEL("Bimestriel", 6), TRIMESTRIEL("Trimestriel", 4),
    SEMESTRIEL("Semestriel", 2), ANNUEL("Annuel", 1), PONCTUEL("Ponctuel (échéance)", 0);

    private final String libelle; private final int occurrencesParAn;
    Periodicite(String libelle, int n) { this.libelle = libelle; this.occurrencesParAn = n; }
    public String getLibelle() { return libelle; }
    public int getOccurrencesParAn() { return occurrencesParAn; }
    public String getCode() { return name(); }
    public static Periodicite of(String c) { try { return valueOf(c); } catch (Exception e) { return TRIMESTRIEL; } }
}
