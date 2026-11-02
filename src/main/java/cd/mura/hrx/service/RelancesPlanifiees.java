package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Relances automatiques, selon les jours et l'heure choisis dans Paramètres > Relances.
 * Vérifie toutes les 5 minutes ; un seul envoi par jour, rattrapé si le serveur a redémarré après l'heure prévue.
 */
@Singleton
@Startup
public class RelancesPlanifiees {

    private static final Logger LOG = Logger.getLogger(RelancesPlanifiees.class.getName());

    @EJB private AppDAO appDAO;
    @EJB private SuiviObjectifsService suiviObjectifs;

    @Schedule(hour = "*", minute = "*/5", persistent = false)
    public void verifier() {
        try {
            App app = appDAO.findFirst();
            if (app == null || !app.isNotificationsActivees()) return;
            LocalDate jour = LocalDate.now();
            if (jour.equals(app.getDerniereRelance())) return;
            if (!jourPrevu(app.getNotificationJour(), jour.getDayOfWeek().getValue())) return;
            int h = entier(app.getNotificationHeure(), 7), m = entier(app.getNotificationMinute(), 0);
            if (LocalTime.now().isBefore(LocalTime.of(Math.min(23, h), Math.min(59, m)))) return;

            app.setDerniereRelance(jour);   // marqué avant l'envoi : pas de double envoi en cas d'erreur partielle
            appDAO.update(app);
            int envoyes = 0;
            if (app.isRappelsObjectifsActifs()) envoyes += suiviObjectifs.envoyerRappels(jour.getYear());
            LOG.info("HRX relances du " + jour + " : " + envoyes + " e-mail(s) envoyé(s)");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "HRX : relances planifiées en échec", e);
        }
    }

    /** "1,2,3,4,5" : 1 = lundi … 7 = dimanche ; vide = du lundi au vendredi. */
    static boolean jourPrevu(String jours, int jourSemaine) {
        if (jours == null || jours.isBlank()) return jourSemaine <= 5;
        return Arrays.stream(jours.split(",")).map(String::trim).anyMatch(j -> j.equals(String.valueOf(jourSemaine)));
    }

    private static int entier(String v, int defaut) {
        try { return v == null || v.isBlank() ? defaut : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaut; }
    }
}
