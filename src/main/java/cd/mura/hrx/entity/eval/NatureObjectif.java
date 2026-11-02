package cd.mura.hrx.entity.eval;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * Rubrique (nature) d'objectif de la fiche de fixation, paramétrable par la DCHT, car la fiche évolue d'une année à l'autre.
 * Ex. groupe « I. Tronc commun » → rubrique « A. Activités opérationnelles ». Une rubrique peut être bornée à une plage d'exercices.
 */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "nature_objectif", uniqueConstraints = @UniqueConstraint(name = "uk_nature_code", columnNames = "code"))
public class NatureObjectif implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Column(nullable = false, length = 40)
    private String code;

    /** Groupe de la fiche (I. Tronc commun, II. Objectifs liés au management, III. Autres objectifs spécifiques…). */
    @Column(nullable = false, length = 120)
    private String groupe;

    @Column(name = "ordre_groupe", nullable = false)
    private int ordreGroupe = 1;

    /** Libellé de la rubrique (A. Activités opérationnelles…). */
    @Column(nullable = false, length = 150)
    private String libelle;

    /** Texte d'aide affiché sous la rubrique. */
    @Column(length = 500)
    private String aide;

    /** Condition d'applicabilité (ex. « Si pilote de processus ou manager de proximité »). */
    @Column(name = "condition_appli", length = 200)
    private String condition;

    @Column(nullable = false)
    private int ordre = 1;

    @Column(nullable = false)
    private int statut = 1;

    /** Plage d'exercices d'application ; null = sans borne. */
    @Column(name = "exercice_debut") private Integer exerciceDebut;
    @Column(name = "exercice_fin") private Integer exerciceFin;

    public boolean applicable(int exercice) {
        return statut == 1 && (exerciceDebut == null || exercice >= exerciceDebut) && (exerciceFin == null || exercice <= exerciceFin);
    }
    public String getPlageLabel() {
        if (exerciceDebut == null && exerciceFin == null) return "Tous les exercices";
        if (exerciceFin == null) return "Depuis " + exerciceDebut;
        if (exerciceDebut == null) return "Jusqu'en " + exerciceFin;
        return exerciceDebut.equals(exerciceFin) ? "Exercice " + exerciceDebut : exerciceDebut + " – " + exerciceFin;
    }
}
