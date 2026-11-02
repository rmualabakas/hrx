package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "module")
public class ModuleP {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "nom",unique = true)
    @Basic(optional = false)
    @NotNull(message = "Veuillez saisir le nom du module")
    private String nom;

    @Column(name = "icone")
    @Basic(optional = false)
    @NotNull(message = "Veuillez entrer l'icône de ce module (Ex: fa fa-trash)")
    private String icone;

    @Column(name = "date_creation", columnDefinition = "DATE")
    @Basic(optional = false)
    private Date dateCreation = new Date();

    @Column(name = "date_maj", columnDefinition = "DATE")
    @Basic(optional = false)
    private Date dateMaj;

    @Column(name = "statut")
    @Basic(optional = false)
    private int statut = 1;

    @Column(name = "style")
    @Basic(optional = false)
    private String style;

    @Column(name = "cree_par")
    @Basic(optional = false)
    private int creePar;

    @OneToMany(mappedBy = "moduleP",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<Menu> menus = new ArrayList<>();

    public int hashCode() {
        return 1;
    }

}
