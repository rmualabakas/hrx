package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import jakarta.ejb.Asynchronous;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.inject.Inject;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Envoi asynchrone d'e-mails HTML avec la configuration SMTP de la plateforme (Paramètres → Messagerie). */
@Stateless
public class MailService {

    private static final Logger LOG = Logger.getLogger(MailService.class.getName());

    @EJB private AppDAO appDAO;
    @Inject private AuditService auditService;

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** Trace dans le journal d'audit (type NOTIFICATION) : visible dans Administration → Journal d'audit. */
    private void trace(String dest, String sujet, boolean ok, String detail) {
        try { auditService.logForUser(null, "NOTIFICATION", "Notifications", "Email", dest, sujet, "Destinataire : " + dest + "\n" + detail, ok); } catch (Exception ex) { LOG.log(Level.WARNING, "Trace audit notification impossible : " + ex.getMessage()); }
    }

    /** Envoi en arrière-plan : l'écran n'attend jamais le serveur de messagerie. */
    @Asynchronous
    @ActivateRequestContext
    public void envoyerHtml(String destinataire, String sujet, String html) {
        if (blank(destinataire)) return;
        App app = appDAO.findFirst();
        if (app == null || blank(app.getHostSmtp()) || Boolean.FALSE.equals(app.getNotificationsActives())) {
            LOG.info("HRX mail non envoyé (SMTP non configuré ou notifications désactivées) : " + sujet + " → " + destinataire);
            trace(destinataire, sujet, false, app == null ? "Configuration application absente" : (blank(app.getHostSmtp()) ? "Serveur SMTP non renseigné (Paramètres → Messagerie)" : "Notifications désactivées (Paramètres → Relances)"));
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", app.getHostSmtp().trim());
            props.put("mail.smtp.port", blank(app.getPortSmtp()) ? "25" : app.getPortSmtp().trim());
            String type = app.getTypeSmtp() == null ? "" : app.getTypeSmtp().trim().toUpperCase();
            boolean auth = !blank(app.getUserSmpt());
            props.put("mail.smtp.auth", String.valueOf(auth));
            if ("SSL".equals(type)) props.put("mail.smtp.ssl.enable", "true");
            else if ("TLS".equals(type) || "STARTTLS".equals(type)) props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.connectiontimeout", "8000");
            props.put("mail.smtp.timeout", "10000");
            Session session = auth ? Session.getInstance(props, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(app.getUserSmpt().trim(), app.getPasswordSmtp() == null ? "" : app.getPasswordSmtp());
                }
            }) : Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session);
            String from = blank(app.getEmailExpediteurSmtp()) ? (blank(app.getAdminMail()) ? destinataire : app.getAdminMail()) : app.getEmailExpediteurSmtp().trim();
            msg.setFrom(new InternetAddress(from, blank(app.getExpediteurSmtp()) ? (blank(app.getNomApp()) ? "HRX" : app.getNomApp()) : app.getExpediteurSmtp(), "UTF-8"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destinataire.trim()));
            msg.setSubject("[" + (blank(app.getNomApp()) ? "HRX" : app.getNomApp()) + "] " + sujet, "UTF-8");
            msg.setContent(html, "text/html; charset=UTF-8");
            Transport.send(msg);
            LOG.info("HRX mail envoyé : " + sujet + " → " + destinataire);
            trace(destinataire, sujet, true, "Envoyé via " + app.getHostSmtp());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX mail en échec : " + sujet + " → " + destinataire + " : " + e.getMessage());
            trace(destinataire, sujet, false, "Échec : " + e.getMessage());
        }
    }
}
