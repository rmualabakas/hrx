package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Entity
@Getter
@Setter
@Table(name = "profil")
public class Profil {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @Basic(optional = false)
    @Column(name = "nom", nullable = false,unique = true,length = 50)
    @NotNull(message = "Vous devez definir l'intitulé du profil")
    private String nom;

    @Basic(optional = false)
    @Column(name = "description")
    private String description;

    @Basic(optional = false)
    @Column(name = "date_creation",columnDefinition = "DATE")
    private Date dateCreation=new Date();

    @Basic(optional = false)
    @Column(name = "date_maj",columnDefinition = "DATE")
    private Date dateMaj;

    @Basic(optional = false)
    @Column(name = "cree_par")
    private int creePar;

    @Column(name = "statut")
    @Basic(optional = false)
    private int statut=1;

    @Column(name = "profil_par_defaut")
    @Basic(optional = false)
    private Boolean profilParDefaut=Boolean.FALSE;

    @ManyToMany( fetch = FetchType.LAZY)
    @JoinTable(
            name = "profil_menu",
            joinColumns = @JoinColumn(name = "profil_id"),
            inverseJoinColumns = @JoinColumn(name = "menu_id"))
    Set<Menu> menus;

    @ManyToMany(mappedBy = "profils",fetch = FetchType.LAZY)
    Set<Privilege> privileges ;

    @OneToMany(mappedBy = "profil",fetch = FetchType.LAZY)
    private List<Users> users = new ArrayList<>();

}