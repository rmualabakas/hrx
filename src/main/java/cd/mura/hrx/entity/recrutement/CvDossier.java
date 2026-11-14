package cd.mura.hrx.entity.recrutement;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;

/** Dossier de la CVthèque (arborescence libre, comme un répertoire). */
@Getter @Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "rec_dossier")
public class CvDossier implements Serializable {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private int id;

    @Column(nullable = false, length = 150) private String nom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private CvDossier parent;

    @Column(name = "cree_par") private int creePar;
    @Column(name = "date_creation") @Temporal(TemporalType.TIMESTAMP) private Date dateCreation = new Date();
}
