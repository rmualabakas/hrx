package cd.mura.hrx.entity.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity

@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_login", columnNames = "login"),
                @UniqueConstraint(name = "uk_user_email", columnNames = "email")
        },
        indexes = {
                @Index(name = "idx_user_login", columnList = "login"),
                @Index(name = "idx_user_email", columnList = "email")
        }
)
public class Users implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    @Column(name = "id")
    private int id;

    @Basic(optional = false)
    @Column(name = "nom", nullable = false)
    @NotNull(message = "Vous devez saisir le nom de l'utilisateur")
    private String nom;

    @Basic(optional = false)
    @Column(name = "prenom", nullable = false)
    @NotNull(message = "Vous devez saisir le prenom de l'utilisateur")
    private String prenom;

    /** Post-nom (facultatif, usage RDC). */
    @Column(name = "postnom", length = 80)
    private String postnom;

    /** Nom complet d'affichage : Prénom NOM Postnom. */
    public String getNomComplet() {
        StringBuilder b = new StringBuilder();
        if (prenom != null && !prenom.isBlank()) b.append(prenom.trim());
        if (nom != null && !nom.isBlank()) b.append(b.length() > 0 ? " " : "").append(nom.trim());
        if (postnom != null && !postnom.isBlank()) b.append(b.length() > 0 ? " " : "").append(postnom.trim());
        return b.toString();
    }

    @Basic(optional = false)
    @Column(name = "email")
    @Email(message = "Adresse email invalide")
    @Pattern(regexp = "(^[A-Za-z0-9+_.-]+@(.+)$)", message = "Merci de saisir une adresse email correcte")
    @NotNull(message = "Merci de renseigner une adresse email avant de continuer")
    private String email;

    @Basic(optional = false)
    @Column(name = "login", unique = true)
    @NotNull(message = "Merci de renseigner le login")
    private String login;

    /**
     * Hash déjà stocké ici ; non exposé en JSON
     */
    @Basic(optional = false)
    @Column(name = "password", nullable = false)
    @JsonIgnore
    private String password;

    @Column(name = "d_creation", columnDefinition = "DATE")
    private Date dateCreation = new Date();

    @Column(name = "d_maj", columnDefinition = "DATE")
    private Date dateMaj;

    @Column(name = "last_login")
    private Date lastLogin;

    @Column(name = "cree_par")
    private int creePar;

    @Column(name = "statut", length = 1)
    private int statut = 1;

    @Column(name = "editable", length = 1)
    private int editable = 1;

    @Column(name = "pass_change", length = 1)
    private int passChange = 1;

    @Column(name = "avatar")
    private String avatar;

    @Column(name = "phone")
    private String phone;

    // Données RH (HRX)

    /** Matricule du collaborateur dans le référentiel du personnel. */
    @Column(name = "matricule", length = 30, unique = true)
    private String matricule;

    /** Intitulé du poste / fonction occupée. */
    @Column(name = "fonction", length = 150)
    private String fonction;

    /** Catégorie professionnelle (cadre, agent de maîtrise, employé…) : conditionne le modèle de fiche. */
    @Column(name = "categorie", length = 60)
    private String categorie;

    @Column(name = "date_embauche", columnDefinition = "DATE")
    private Date dateEmbauche;

    /** Direction / entité de rattachement (taux de réalisation par direction). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "direction_id")
    private Direction direction;

    /** 1 = collaborateur évaluable dans les campagnes ; 0 = exclu (stagiaire, départ…). */
    @Column(name = "evaluable", length = 1)
    private int evaluable = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profil_id")
    private Profil profil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superieur_id")
    private Users superieurHierarchique;

    @OneToMany(mappedBy = "superieurHierarchique", fetch = FetchType.LAZY)
    private List<Users> subordonnes = new ArrayList<>();

    @ManyToMany(mappedBy = "membres", fetch = FetchType.LAZY)
    private Set<GroupeUtilisateur> groupesUtilisateur = new java.util.HashSet<>();

    /* Callbacks pour timestamps (sans changer les noms/colonnes) */
    @PrePersist
    void prePersist() {
        if (dateCreation == null) {
            dateCreation = new Date();
        }
    }

    @PreUpdate
    void preUpdate() {
        dateMaj = new Date();
    }

}
