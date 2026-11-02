package cd.mura.hrx.bean.config;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.service.AuthorizationService;
import cd.mura.hrx.utils.LdapUrl;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Properties;

/**
 * Paramétrage de l'application (nom, SMTP, LDAP, planification des relances).
 * Exposé en session : le nom de l'application est affiché dans la coque (titre, login).
 */
@Named
@SessionScoped
@Getter
@Setter
public class AppBean implements Serializable {

    private static final long serialVersionUID = 1L;

    @EJB
    private AppDAO appDAO;

    @Inject
    private AuthorizationService authorization;

    private App app1;
    private int ongletActif = 0;

    private boolean notificationLundi;
    private boolean notificationMardi;
    private boolean notificationMercredi;
    private boolean notificationJeudi;
    private boolean notificationVendredi;
    private boolean notificationSamedi;
    private boolean notificationDimanche;

    private List<String> minutesArray;

    /** Résultats des tests de connexion affichés dans l'écran Paramètres. */
    private String ldapTestResult;
    private boolean ldapTestOk;
    private String smtpTestResult;
    private boolean smtpTestOk;
    private String smtpTestDestinataire;

    @PostConstruct
    public void init() {
        app1 = appDAO.findFirst();
        if (app1 == null) {
            app1 = new App();
            // Relances en semaine par défaut
            notificationLundi = notificationMardi = notificationMercredi = notificationJeudi = notificationVendredi = true;
        } else {
            parseJoursNotification();
        }
        if (app1.getNotificationHeure() == null || app1.getNotificationHeure().isEmpty()) app1.setNotificationHeure("7");
        if (app1.getNotificationMinute() == null || app1.getNotificationMinute().isEmpty()) app1.setNotificationMinute("0");
        minutesArray = new ArrayList<>();
        for (int i = 0; i < 60; i++) minutesArray.add(String.valueOf(i));
    }

    /** "1,2,3,4,5" -> cases à cocher (1 = lundi … 7 = dimanche). */
    private void parseJoursNotification() {
        notificationLundi = notificationMardi = notificationMercredi = notificationJeudi =
                notificationVendredi = notificationSamedi = notificationDimanche = false;
        String jours = app1.getNotificationJour();
        if (jours == null || jours.isBlank()) return;
        for (String j : jours.split(",")) {
            switch (j.trim()) {
                case "1" -> notificationLundi = true;
                case "2" -> notificationMardi = true;
                case "3" -> notificationMercredi = true;
                case "4" -> notificationJeudi = true;
                case "5" -> notificationVendredi = true;
                case "6" -> notificationSamedi = true;
                case "7" -> notificationDimanche = true;
                default -> { }
            }
        }
    }

    private void construireJoursNotification() {
        List<String> jours = new ArrayList<>();
        if (notificationLundi) jours.add("1");
        if (notificationMardi) jours.add("2");
        if (notificationMercredi) jours.add("3");
        if (notificationJeudi) jours.add("4");
        if (notificationVendredi) jours.add("5");
        if (notificationSamedi) jours.add("6");
        if (notificationDimanche) jours.add("7");
        app1.setNotificationJour(String.join(",", jours));
    }

    public void save() {
        authorization.requirePrivilege("CFG_EDIT");
        try {
            App enBase = appDAO.findFirst();
            if (enBase != null && app1.getId() == enBase.getId()) app1.setDerniereRelance(enBase.getDerniereRelance());   // ne pas écraser la date du dernier envoi
            construireJoursNotification();
            appDAO.save(app1);
            addMessage(FacesMessage.SEVERITY_INFO, "Succès", "Configuration enregistrée avec succès");
            init();
        } catch (Exception e) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Erreur", "Erreur lors de l'enregistrement : " + e.getMessage());
        }
    }

    public void update() {
        authorization.requirePrivilege("CFG_EDIT");
        try {
            construireJoursNotification();
            app1.setDateMaj(new java.util.Date());
            appDAO.update(app1);
            addMessage(FacesMessage.SEVERITY_INFO, "Succès", "Configuration mise à jour avec succès");
            init();
        } catch (Exception e) {
            addMessage(FacesMessage.SEVERITY_ERROR, "Erreur", "Erreur lors de la mise à jour : " + e.getMessage());
        }
    }

    /** Enregistre (création ou mise à jour selon l'existence de la ligne). */
    public void enregistrer() {
        if (app1 != null && app1.getId() > 0) update(); else save();
    }

    /** Teste le compte de service LDAP (bind) avec les valeurs saisies, sans les enregistrer. */
    public void testLdap() {
        authorization.requirePrivilege("CFG_EDIT");
        ldapTestResult = null; ldapTestOk = false;
        if (app1 == null || blank(app1.getLdapServerName()) || blank(app1.getLdapPort())) {
            ldapTestResult = "Renseignez au moins le serveur et le port."; return;
        }
        String url = LdapUrl.of(app1.getLdapServerName(), app1.getLdapPort());
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put("com.sun.jndi.ldap.connect.timeout", "4000");
        env.put("com.sun.jndi.ldap.read.timeout", "4000");
        if (!blank(app1.getLdapBindUser()) && !blank(app1.getLdapBindPassword())) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, app1.getLdapBindUser().trim());
            env.put(Context.SECURITY_CREDENTIALS, app1.getLdapBindPassword());
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        }
        DirContext ctx = null;
        long t0 = System.currentTimeMillis();
        try {
            ctx = new InitialDirContext(env);
            ldapTestOk = true;
            ldapTestResult = "Connexion réussie à " + url + (blank(app1.getLdapBindUser()) ? " (anonyme)" : " avec " + app1.getLdapBindUser().trim())
                    + " en " + (System.currentTimeMillis() - t0) + " ms.";
        } catch (Exception e) {
            ldapTestResult = "Échec : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\\n")[0]);
        } finally {
            if (ctx != null) try { ctx.close(); } catch (Exception ignored) { }
        }
    }

    /** Envoie un e-mail de test avec la configuration SMTP saisie. */
    public void testSmtp() {
        authorization.requirePrivilege("CFG_EDIT");
        smtpTestResult = null; smtpTestOk = false;
        String dest = blank(smtpTestDestinataire) ? (app1 == null ? null : app1.getAdminMail()) : smtpTestDestinataire.trim();
        if (app1 == null || blank(app1.getHostSmtp()) || blank(dest)) {
            smtpTestResult = "Renseignez le serveur SMTP et un destinataire."; return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", app1.getHostSmtp().trim());
            props.put("mail.smtp.port", blank(app1.getPortSmtp()) ? "25" : app1.getPortSmtp().trim());
            String type = app1.getTypeSmtp() == null ? "" : app1.getTypeSmtp().trim().toUpperCase();
            boolean auth = !blank(app1.getUserSmpt());
            props.put("mail.smtp.auth", String.valueOf(auth));
            if ("SSL".equals(type)) { props.put("mail.smtp.ssl.enable", "true"); }
            else if ("TLS".equals(type) || "STARTTLS".equals(type)) { props.put("mail.smtp.starttls.enable", "true"); }
            props.put("mail.smtp.connectiontimeout", "6000");
            props.put("mail.smtp.timeout", "8000");
            jakarta.mail.Session session = auth
                    ? jakarta.mail.Session.getInstance(props, new jakarta.mail.Authenticator() {
                        @Override protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                            return new jakarta.mail.PasswordAuthentication(app1.getUserSmpt().trim(), app1.getPasswordSmtp() == null ? "" : app1.getPasswordSmtp());
                        }
                    })
                    : jakarta.mail.Session.getInstance(props);
            jakarta.mail.internet.MimeMessage msg = new jakarta.mail.internet.MimeMessage(session);
            String from = blank(app1.getEmailExpediteurSmtp()) ? dest : app1.getEmailExpediteurSmtp().trim();
            msg.setFrom(new jakarta.mail.internet.InternetAddress(from, blank(app1.getExpediteurSmtp()) ? "HRX" : app1.getExpediteurSmtp(), "UTF-8"));
            msg.setRecipients(jakarta.mail.Message.RecipientType.TO, jakarta.mail.internet.InternetAddress.parse(dest));
            msg.setSubject("[" + (blank(app1.getNomApp()) ? "HRX" : app1.getNomApp()) + "] Test de la messagerie", "UTF-8");
            msg.setText("Ce message confirme que la configuration SMTP de la plateforme est opérationnelle.", "UTF-8");
            jakarta.mail.Transport.send(msg);
            smtpTestOk = true;
            smtpTestResult = "E-mail de test envoyé à " + dest + ".";
        } catch (Exception e) {
            smtpTestResult = "Échec : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\\n")[0]);
        }
    }

    /** Mémorise l'onglet actif de l'écran Paramètres (session). */
    public void onTabChange(org.primefaces.event.TabChangeEvent<?> event) {
        if (event != null && event.getTab() != null && event.getTab().getParent() != null) {
            ongletActif = event.getTab().getParent().getChildren().indexOf(event.getTab());
        }
    }

    /** 0..23 pour la liste des heures d'envoi. */
    public List<String> getHeures() {
        List<String> h = new ArrayList<>();
        for (int i = 0; i < 24; i++) h.add(String.valueOf(i));
        return h;
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }

    /** Libellé lisible de la planification des relances (prévisualisation). */
    public String getProchaineLancement() {
        if (app1 != null && !app1.isNotificationsActivees()) return "Désactivées - aucun email ne sera envoyé";
        String heure = app1.getNotificationHeure() != null ? app1.getNotificationHeure() : "7";
        String minute = app1.getNotificationMinute() != null ? app1.getNotificationMinute() : "0";
        List<String> noms = new ArrayList<>();
        boolean[] flags = {notificationLundi, notificationMardi, notificationMercredi, notificationJeudi,
                notificationVendredi, notificationSamedi, notificationDimanche};
        List<String> libelles = Arrays.asList("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche");
        for (int i = 0; i < flags.length; i++) if (flags[i]) noms.add(libelles.get(i));
        StringBuilder sb = new StringBuilder(noms.isEmpty() ? "Tous les jours" : String.join(", ", noms));
        sb.append(" à ").append(heure).append("h");
        if (!"0".equals(minute)) sb.append(minute);
        return sb.toString();
    }

    private void addMessage(FacesMessage.Severity severity, String summary, String detail) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(severity, summary, detail));
    }
}
