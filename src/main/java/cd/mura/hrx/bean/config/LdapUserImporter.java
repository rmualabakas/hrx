package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.dao.config.ProfilDAO;
import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.config.Profil;
import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.utils.LdapUrl;
import cd.mura.hrx.include.UpdatableBCrypt;
import cd.mura.hrx.service.AuditService;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/** Bean professionnel pour l'importation d'utilisateurs depuis Active Directory/LDAP */
@Named
@ViewScoped
@Data
public class LdapUserImporter implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(LdapUserImporter.class);
    private static final UpdatableBCrypt BCRYPT = new UpdatableBCrypt(11);

    @EJB private UserDAO userDAO;
    @EJB private ProfilDAO profilDAO;
    @EJB private AppDAO appDAO;
    @Inject private LoginBean loginBean;
    @Inject private UserBean userBean;
    @Inject private AuditService auditService;

    // Configuration LDAP
    private App app;
    private String ldapUrl;
    private String baseDn;
    private String bindUser;
    private String bindPassword;

    // Données pour l'importation
    private Profil profilDefault;
    private List<Users> availableUsers;     // Utilisateurs trouvés dans LDAP
    private List<Users> selectedUsers;      // Utilisateurs sélectionnés pour import

    // Statistiques
    private int totalFound = 0;
    private int totalActive = 0;
    private int totalDisabled = 0;
    private int totalAlreadyExists = 0;
    private boolean importInProgress = false;

    @PostConstruct
    public void init() {
        try {
            // Charger la configuration LDAP
            app = appDAO.findFirst();
            if (app == null) {
                log.error("Configuration application non trouvée");
                addMessage(FacesMessage.SEVERITY_ERROR,
                        "Erreur", "Configuration application non trouvée");
                return;
            }

            // Construire l'URL LDAP - ADAPTATION À VOTRE STRUCTURE
            // Vos champs: ldapServerName, ldapPort, ldapBaseDn, ldapBindUser, ldapBindPassword
            if (app.getLdapServerName() == null || app.getLdapServerName().trim().isEmpty()) {
                log.warn("Serveur LDAP non configuré");
                ldapUrl = null;
                baseDn = null;
                bindUser = null;
                bindPassword = null;
            } else {
                // Construction de l'URL avec gestion du port String
                String portStr = app.getLdapPort() != null ? app.getLdapPort() : "389";
                ldapUrl = LdapUrl.of(app.getLdapServerName(), portStr) + "/";
                baseDn = app.getLdapBaseDn();
                bindUser = app.getLdapBindUser();
                bindPassword = app.getLdapBindPassword();

                log.info("Configuration LDAP chargée - Serveur: {}, Port: {}, Base DN: {}",
                        app.getLdapServerName(), portStr, baseDn);
            }

            // Charger le profil par défaut
            profilDefault = profilDAO.findByDefault(1);
            if (profilDefault == null) {
                log.warn("Aucun profil par défaut trouvé : l'import LDAP sera bloqué tant qu'un profil par défaut n'est pas défini (page Profils)");
            }

            // Initialiser les listes
            availableUsers = new ArrayList<>();
            selectedUsers = new ArrayList<>();

            log.info("LdapUserImporter initialisé avec succès");

        } catch (Exception e) {
            log.error("Erreur lors de l'initialisation du LdapUserImporter", e);
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Erreur", "Impossible d'initialiser l'importation LDAP: " + e.getMessage());
        }
    }

    /**
     * Recherche tous les utilisateurs dans Active Directory
     * Exclut les comptes machines (login$ ) et les comptes déjà importés
     */
    public void searchLdapUsers() {
        try {
            importInProgress = true;
            availableUsers.clear();
            selectedUsers.clear();
            resetStatistics();

            log.info("Début de la recherche LDAP dans {}", baseDn);

            // Validation de la configuration
            if (!validateLdapConfig()) {
                importInProgress = false;
                return;
            }

            // Vérifier le profil par défaut
            if (profilDefault == null) {
                addMessage(FacesMessage.SEVERITY_ERROR,
                        "Profil manquant",
                        "Aucun profil par défaut défini. Impossible d'importer les utilisateurs.");
                importInProgress = false;
                return;
            }

            // Connexion au serveur LDAP
            DirContext ctx = connectToLdap();
            if (ctx == null) {
                importInProgress = false;
                return;
            }

            try {
                // Configuration de la recherche
                SearchControls searchControls = new SearchControls();
                searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                searchControls.setReturningAttributes(new String[]{
                        "sAMAccountName", "mail", "givenName", "sn", "cn",
                        "userAccountControl", "displayName", "telephoneNumber"
                });

                // Filtre: tous les utilisateurs (pas les machines)
                String filter = "(&(objectCategory=person)(objectClass=user)(!(sAMAccountName=*$)))";

                NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, searchControls);

                while (results.hasMore()) {
                    processLdapEntry(results.next());
                }

                log.info("Recherche terminée. {} utilisateurs trouvés, {} déjà existants",
                        totalFound, totalAlreadyExists);

                if (availableUsers.isEmpty()) {
                    addMessage(FacesMessage.SEVERITY_WARN,
                            "Aucun utilisateur",
                            "Tous les utilisateurs LDAP sont déjà importés ou aucun utilisateur trouvé");
                } else {
                    // Pré-sélectionner tous les utilisateurs actifs
                    for (Users u : availableUsers) {
                        if (u.getStatut() == 1) {
                            selectedUsers.add(u);
                        }
                    }

                    addMessage(FacesMessage.SEVERITY_INFO,
                            "Recherche terminée",
                            String.format("%d nouveaux utilisateurs trouvés (%d actifs, %d désactivés)",
                                    availableUsers.size(), totalActive, totalDisabled));
                }

            } finally {
                closeLdapConnection(ctx);
            }

        } catch (Exception e) {
            log.error("Erreur lors de la recherche LDAP", e);
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Erreur LDAP",
                    "Impossible de rechercher les utilisateurs: " + e.getMessage());
        } finally {
            importInProgress = false;
        }
    }

    /**
     * Traite une entrée LDAP et crée un objet User
     */
    private void processLdapEntry(SearchResult searchResult) {
        try {
            Attributes attrs = searchResult.getAttributes();

            String login = getAttributeValue(attrs, "sAMAccountName");

            // Validation du login
            if (login == null || login.trim().isEmpty() || login.endsWith("$")) {
                return;
            }

            totalFound++;

            // Vérifier si l'utilisateur existe déjà
            Users existingUser = userDAO.findUserByLoginEmail(login);
            if (existingUser != null && existingUser.getId() > 0) {
                totalAlreadyExists++;
                log.debug("Utilisateur {} déjà existant, ignoré", login);
                return;
            }

            // Extraction des attributs
            String email = getAttributeValue(attrs, "mail");
            String prenom = getAttributeValue(attrs, "givenName");
            String nom = getAttributeValue(attrs, "sn");
            String cn = getAttributeValue(attrs, "cn");
            String displayName = getAttributeValue(attrs, "displayName");
            String phone = getAttributeValue(attrs, "telephoneNumber");

            // Fallback pour prénom/nom si manquants
            if ((prenom == null || prenom.trim().isEmpty()) && cn != null) {
                String[] parts = cn.trim().split("\\s+", 2);
                prenom = parts[0];
                if (parts.length > 1 && (nom == null || nom.trim().isEmpty())) {
                    nom = parts[1];
                }
            }

            // Vérifier si le compte est désactivé
            boolean disabled = isAccountDisabled(attrs);

            // Créer l'objet User
            Users user = new Users();
            user.setLogin(login);

            // Gestion de l'email avec fallback
            if (email != null && !email.trim().isEmpty()) {
                user.setEmail(email);
            } else {
                // Si pas d'email, utiliser login@domain
                String domain = app.getLdapDomainName() != null ? app.getLdapDomainName() : "domain.com";
                user.setEmail(login + "@" + domain);
            }

            user.setPrenom(prenom != null ? prenom : "");
            user.setNom(nom != null ? nom : login);
            user.setPhone(phone);
            user.setPassword(BCRYPT.hash("000000")); // ⚡ CORRECTION : Hash BCrypt du mot de passe temporaire
            user.setProfil(profilDefault);
            user.setCreePar(loginBean.getUsers().getId());
            user.setEditable(1);
            user.setStatut(disabled ? 0 : 1); // 0 = inactif, 1 = actif
            user.setPassChange(1); // Force le changement de mot de passe

            availableUsers.add(user);

            if (disabled) {
                totalDisabled++;
            } else {
                totalActive++;
            }

            log.debug("Utilisateur LDAP ajouté: {} {} ({})", prenom, nom, login);

        } catch (Exception e) {
            log.error("Erreur lors du traitement de l'entrée LDAP", e);
        }
    }

    /**
     * Importe les utilisateurs sélectionnés dans la base de données
     */
    public void importSelectedUsers() {
        setImportCallback(false);

        if (selectedUsers == null || selectedUsers.isEmpty()) {
            addMessage(FacesMessage.SEVERITY_WARN,
                    "Aucune sélection",
                    "Veuillez sélectionner au moins un utilisateur à importer");
            return;
        }

        try {
            importInProgress = true;
            int successCount = 0;
            int errorCount = 0;
            int skippedCount = 0;

            log.info("Début de l'importation de {} utilisateurs", selectedUsers.size());

            for (Users user : selectedUsers) {
                try {
                    Users existingUser = userDAO.findUserByLoginEmail(user.getLogin());
                    if (existingUser != null && existingUser.getId() > 0) {
                        totalAlreadyExists++;
                        skippedCount++;
                        log.info("Utilisateur {} déjà existant, ignoré pendant l'import", user.getLogin());
                        continue;
                    }

                    userDAO.persist(user);
                    successCount++;
                    log.info("Utilisateur importé: {} {} ({})",
                            user.getPrenom(), user.getNom(), user.getLogin());
                } catch (Exception e) {
                    errorCount++;
                    log.error("Erreur lors de l'importation de l'utilisateur " + user.getLogin(), e);
                }
            }

            if (successCount > 0 && userBean != null) {
                userBean.refreshLists();
            }

            auditService.log("LDAP_IMPORT", "Administration", "Users", null,
                    "Import LDAP",
                    String.format("%d utilisateur(s) importé(s), %d doublon(s), %d erreur(s)",
                            successCount, skippedCount, errorCount),
                    errorCount == 0);

            // Message de résultat
            if (successCount == 0 && skippedCount > 0 && errorCount == 0) {
                addMessage(FacesMessage.SEVERITY_WARN,
                        "Aucun nouvel utilisateur",
                        String.format("%d utilisateur(s) déjà existant(s), aucun import effectué", skippedCount));
            } else if (errorCount == 0) {
                addMessage(FacesMessage.SEVERITY_INFO,
                        "Importation réussie",
                        String.format("%d utilisateur(s) importé(s) avec succès", successCount));
            } else {
                addMessage(FacesMessage.SEVERITY_WARN,
                        "Importation partielle",
                        String.format("%d utilisateur(s) importé(s), %d erreur(s)",
                                successCount, errorCount));
            }

            setImportCallback(successCount > 0);

            // Réinitialiser les listes
            availableUsers.clear();
            selectedUsers.clear();
            resetStatistics();

        } catch (Exception e) {
            log.error("Erreur lors de l'importation des utilisateurs", e);
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Erreur",
                    "Erreur lors de l'importation: " + e.getMessage());
        } finally {
            importInProgress = false;
        }
    }

    private void setImportCallback(boolean success) {
        PrimeFaces.current().ajax().addCallbackParam("importSuccess", success);
    }

    /**
     * Sélectionner tous les utilisateurs actifs
     */
    public void selectAllActiveUsers() {
        selectedUsers.clear();
        for (Users u : availableUsers) {
            if (u.getStatut() == 1) {
                selectedUsers.add(u);
            }
        }
        addMessage(FacesMessage.SEVERITY_INFO,
                "Sélection",
                selectedUsers.size() + " utilisateur(s) actif(s) sélectionné(s)");
    }

    /**
     * Désélectionner tous les utilisateurs
     */
    public void unselectAll() {
        selectedUsers.clear();
        addMessage(FacesMessage.SEVERITY_INFO,
                "Sélection",
                "Tous les utilisateurs ont été désélectionnés");
    }

    /**
     * Valide la configuration LDAP
     */
    private boolean validateLdapConfig() {
        if (ldapUrl == null || ldapUrl.trim().isEmpty() || ldapUrl.contains("null")) {
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Configuration manquante",
                    "L'URL du serveur LDAP n'est pas configurée. Veuillez configurer les paramètres LDAP.");
            return false;
        }
        if (baseDn == null || baseDn.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Configuration manquante",
                    "Le Base DN n'est pas configuré. Veuillez renseigner ldap_base_dn.");
            return false;
        }
        if (bindUser == null || bindUser.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Configuration manquante",
                    "Le compte de connexion LDAP n'est pas configuré. Veuillez renseigner ldap_bind_user.");
            return false;
        }
        if (bindPassword == null || bindPassword.trim().isEmpty()) {
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Configuration manquante",
                    "Le mot de passe LDAP n'est pas configuré. Veuillez renseigner ldap_bind_password.");
            return false;
        }
        return true;
    }

    /**
     * Établit une connexion au serveur LDAP
     */
    private DirContext connectToLdap() {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, ldapUrl);
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, bindUser);
            env.put(Context.SECURITY_CREDENTIALS, bindPassword);

            // Timeout de connexion
            env.put("com.sun.jndi.ldap.connect.timeout", "10000");
            env.put("com.sun.jndi.ldap.read.timeout", "10000");

            log.info("Tentative de connexion LDAP: {}", ldapUrl);
            return new InitialDirContext(env);

        } catch (NamingException e) {
            log.error("Erreur de connexion LDAP à {}", ldapUrl, e);
            addMessage(FacesMessage.SEVERITY_ERROR,
                    "Connexion LDAP échouée",
                    "Impossible de se connecter au serveur LDAP. Vérifiez les paramètres de connexion: " + e.getMessage());
            return null;
        }
    }

    /**
     * Ferme la connexion LDAP
     */
    private void closeLdapConnection(DirContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
                log.debug("Connexion LDAP fermée");
            } catch (NamingException e) {
                log.warn("Erreur lors de la fermeture de la connexion LDAP", e);
            }
        }
    }

    /**
     * Récupère la valeur d'un attribut LDAP
     */
    private String getAttributeValue(Attributes attrs, String name) {
        try {
            Attribute attr = attrs.get(name);
            if (attr != null && attr.size() > 0) {
                Object value = attr.get();
                return value != null ? value.toString().trim() : null;
            }
        } catch (NamingException e) {
            log.debug("Attribut {} non trouvé", name);
        }
        return null;
    }

    /**
     * Vérifie si un compte Active Directory est désactivé
     * Le bit ACCOUNTDISABLE (0x0002) indique un compte désactivé
     */
    private boolean isAccountDisabled(Attributes attrs) {
        String uacStr = getAttributeValue(attrs, "userAccountControl");
        if (uacStr == null || uacStr.trim().isEmpty()) {
            return false; // Par défaut, considéré comme actif
        }

        try {
            int uac = Integer.parseInt(uacStr);
            return (uac & 0x0002) != 0; // Bit 2 = ACCOUNTDISABLE
        } catch (NumberFormatException e) {
            log.warn("Valeur userAccountControl invalide: {}", uacStr);
            return false;
        }
    }

    /**
     * Réinitialise les statistiques
     */
    private void resetStatistics() {
        totalFound = 0;
        totalActive = 0;
        totalDisabled = 0;
        totalAlreadyExists = 0;
    }

    /**
     * Ajoute un message FacesMessage
     */
    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(severity, summary, detail));
    }

    /**
     * Annule l'importation et réinitialise les données
     */
    public void cancelImport() {
        availableUsers.clear();
        selectedUsers.clear();
        resetStatistics();
        addMessage(FacesMessage.SEVERITY_INFO,
                "Annulé",
                "L'importation a été annulée");
    }

    // GETTERS POUR LES STATISTIQUES

    public int getTotalImportable() {
        return availableUsers != null ? availableUsers.size() : 0;
    }

    public int getTotalSelected() {
        return selectedUsers != null ? selectedUsers.size() : 0;
    }

    public boolean isHasUsers() {
        return availableUsers != null && !availableUsers.isEmpty();
    }

    public String getLdapStatus() {
        if (ldapUrl == null || ldapUrl.contains("null") || baseDn == null) {
            return "Non configuré";
        }
        return "Configuré: " + ldapUrl;
    }
}
