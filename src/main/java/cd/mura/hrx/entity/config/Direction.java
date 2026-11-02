package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Setter
@Getter
@Table(name = "direction")
public class Direction {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "nom", unique = true)
    @Basic(optional = false)
    private String nom;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "abrege", unique = true)
    private String abrege;

    @Column(name = "statut")
    @Basic(optional = false)
    private int statut = 1;

    @Column(name = "date_creation", columnDefinition = "DATE")
    @Basic(optional = false)
    private LocalDate dateCreation = LocalDate.now();

    @Column(name = "date_maj")
    private LocalDate dateMaj;

    @Column(name = "cree_par")
    private int creePar;

    @Column(name = "maj_par")
    private int majPar;

    @Column(name = "dirige_par")
    private int dirigePar;

    /** Collaborateurs rattachés à la direction (population évaluable par entité). */
    @OneToMany(mappedBy = "direction", fetch = FetchType.LAZY)
    private List<Users> collaborateurs = new ArrayList<>();

    public int hashCode() {
        return 1;
    }

}
