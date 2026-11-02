package cd.mura.hrx.entity.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "groupe_utilisateur")
public class GroupeUtilisateur implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "nom", nullable = false, unique = true, length = 120)
    private String nom;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "statut", nullable = false)
    private int statut = 1;

    @Column(name = "cree_par")
    private int creePar = 0;

    @Column(name = "modifie_par")
    private int modifiePar = 0;

    @Column(name = "date_creation", columnDefinition = "DATE")
    private Date dateCreation = new Date();

    @Column(name = "date_maj", columnDefinition = "DATE")
    private Date dateMaj;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "groupe_utilisateur_membre",
            joinColumns = @JoinColumn(name = "groupe_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    @ToString.Exclude
    private Set<Users> membres = new LinkedHashSet<>();

    @PreUpdate
    void preUpdate() {
        dateMaj = new Date();
    }
}
