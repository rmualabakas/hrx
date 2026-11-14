package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Jour férié (même modèle que clix) : soit ponctuel (une date précise, ex. un férié décrété),
 * soit récurrent (même jour et même mois chaque année, ex. 30 juin).
 */
@Entity
@Table(name = "jour_ferie")
@Getter @Setter
public class JourFerie implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Column(nullable = false, length = 150)
    private String libelle;

    /** Date du férié ponctuel. */
    @Column(name = "date_ferie", columnDefinition = "DATE")
    private LocalDate dateFerie;

    /** Jour et mois du férié récurrent. */
    @Column(name = "jour") private Integer jour;
    @Column(name = "mois") private Integer mois;

    @Column(nullable = false) private boolean recurrent;
    @Column(nullable = false) private boolean actif = true;

    /** Le férié tombe-t-il ce jour-là ? */
    public boolean tombeLe(LocalDate d) {
        if (!actif || d == null) return false;
        if (recurrent) return jour != null && mois != null && d.getDayOfMonth() == jour && d.getMonthValue() == mois;
        return d.equals(dateFerie);
    }

    /** Date du férié pour une année donnée (null si un 29 février n'existe pas cette année-là). */
    public LocalDate dansAnnee(int annee) {
        if (!recurrent) return dateFerie != null && dateFerie.getYear() == annee ? dateFerie : null;
        try { return jour == null || mois == null ? null : LocalDate.of(annee, mois, jour); } catch (Exception e) { return null; }
    }

    public String getDateLisible() {
        if (recurrent) {
            if (jour == null || mois == null) return "";
            return jour + " " + java.time.Month.of(mois).getDisplayName(java.time.format.TextStyle.FULL, Locale.FRENCH) + " (chaque année)";
        }
        return dateFerie == null ? "" : dateFerie.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH));
    }

    @Override public boolean equals(Object o) { return this == o || (o instanceof JourFerie j && id != 0 && j.id == id); }
    @Override public int hashCode() { return 31; }
}
