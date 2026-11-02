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
@Setter
@Getter
@Table(name = "menu")
public class Menu {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "nom",nullable = false)
    @Basic(optional = false)
    @NotNull(message = "Veuillez saisir l'etiquete de ce menu'")
    private String nom;

    @Column(name = "url")
    @Basic(optional = false)
    @NotNull(message = "Veuillez saisir l'url de ce menu, si le menu n'a pas d'url mettez # à la place !")
    private String url;

    @Column(name = "icone")
    @Basic(optional = false)
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

    @Column(name = "parent_id")
    @Basic(optional = false)
    private int parentId = 0;

    @Column(name = "cree_par")
    @Basic(optional = false)
    private int creePar;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private ModuleP moduleP;

    @OneToMany(mappedBy = "menu",targetEntity = Privilege.class,fetch = FetchType.LAZY, cascade = CascadeType.ALL,orphanRemoval = true)
    private List<Privilege> privileges = new ArrayList<>();

    @ManyToMany(mappedBy = "menus",fetch = FetchType.LAZY)
    Set<Profil> profils ;

}
