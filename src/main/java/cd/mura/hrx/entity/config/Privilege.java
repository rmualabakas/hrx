package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.Set;

@Entity
@Setter
@Getter
@Table(name = "privilege")
public class Privilege {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "nom",length = 50)
    @Basic(optional = false)
    @NotNull(message = "Veuillez saisir l'etiquete de ce privilège")
    private String nom;

    @Column(name = "code", unique = true, length = 30)
    @Basic(optional = false)
    @NotNull(message = "Veuillez saisir le code de ce privilège")
    private String code;

    @Column(name = "date_creation", columnDefinition = "DATE")
    @Basic(optional = false)
    private Date dateCreation = new Date();

    @Column(name = "date_maj", columnDefinition = "DATE")
    @Basic(optional = false)
    private Date dateMaj;

    @Column(name = "statut")
    @Basic(optional = false)
    private int statut = 1;

    @Column(name = "cree_par")
    @Basic(optional = false)
    private int creePar;

    @ManyToOne(fetch = FetchType.LAZY,targetEntity = Menu.class)
    @JoinColumn(name = "menu_id")
    private Menu menu;

    @ManyToMany( fetch = FetchType.LAZY)
    @JoinTable(
            name = "profil_privilege",
            joinColumns = @JoinColumn(name = "privilege_id"),
            inverseJoinColumns = @JoinColumn(name = "profil_id"))
    Set<Profil> profils;

    public int hashCode() {
        return 1;
    }

}
