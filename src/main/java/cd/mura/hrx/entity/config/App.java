package cd.mura.hrx.entity.config;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** Paramétrage global de la plateforme HRX (une seule ligne) */
@Entity
@Setter
@Getter
@Table(name = "app")
public class App {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Column(name = "id")
    private int id;

    @Column(name = "nom_app", unique = true)
    @Basic(optional = false)
    private String nomApp = "HRX";

    @Column(name = "url")
    @Basic(optional = false)
    private String url;

    @Column(name = "statut")
    @Basic(optional = false)
    private int statut = 1;

    @Column(name = "date_creation")
    @Basic(optional = false)
    private Date dateCreation = new Date();

    @Column(name = "date_maj")
    @Basic(optional = false)
    private Date dateMaj = new Date();

    @Column(name = "admin_mail")
    @Email(message = "Veuillez saisir une adresse email valide")
    @Basic(optional = false)
    private String adminMail;

    // SMTP
    @Column(name = "host_smtp")
    private String hostSmtp;

    @Column(name = "expediteur_smtp")
    private String expediteurSmtp;

    @Column(name = "email_expediteur_smtp")
    @Email(message = "Veuillez saisir une adresse email valide")
    private String emailExpediteurSmtp;

    @Column(name = "type_smtp")
    private String typeSmtp;

    @Column(name = "user_smpt")
    private String userSmpt;

    @Column(name = "password_smpt")
    private String passwordSmtp;

    @Column(name = "port_smtp")
    private String portSmtp;

    // LDAP / Active Directory
    @Column(name = "ldap_server_name")
    private String ldapServerName;

    @Column(name = "ldap_domain_name")
    private String ldapDomainName;

    @Column(name = "ldap_port")
    private String ldapPort;

    @Column(name = "ldap_base_dn")
    private String ldapBaseDn;

    @Column(name = "ldap_bind_user")
    private String ldapBindUser;

    @Column(name = "ldap_bind_password")
    private String ldapBindPassword;

    // Fichiers
    /** Répertoire racine des fichiers (fiches PDF archivées, exports, logos). */
    @Column(name = "rep_base_fichier")
    private String repBaseFichier;

    @Column(name = "logo")
    private String logo;

    /** Onglet sélectionné par défaut sur la page de connexion : 2 = compte réseau (LDAP), 1 = compte local. */
    @Column(name = "mode_connexion_defaut")
    private Integer modeConnexionDefaut = 2;

    /** Rappels de fixation des objectifs (N+1 et DCHT), envoyés avec les relances planifiées. */
    @Column(name = "rappels_objectifs")
    private Boolean rappelsObjectifs = Boolean.TRUE;

    /** Dernier jour d'envoi des relances planifiées (évite un double envoi). */
    @Column(name = "derniere_relance")
    private java.time.LocalDate derniereRelance;

    public boolean isRappelsObjectifsActifs() { return rappelsObjectifs == null || rappelsObjectifs; }

    // Relances automatiques (job quotidien)
    /** Interrupteur global du job quotidien d'emails (relances évaluateurs, échéances de campagne). */
    @Column(name = "notifications_actives")
    private Boolean notificationsActives = Boolean.TRUE;

    /** Jours d'envoi, ex. "1,2,3,4,5" (lundi → vendredi). */
    @Column(name = "notification_jour")
    private String notificationJour;

    @Column(name = "notification_heure")
    private String notificationHeure;

    @Column(name = "notification_minute")
    private String notificationMinute;

    /** {@code null} (ligne existante) = actif. */
    public boolean isNotificationsActivees() {
        return !Boolean.FALSE.equals(notificationsActives);
    }

    public int hashCode() {
        return 1;
    }
}
