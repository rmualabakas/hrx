package cd.mura.hrx.service.recrutement;

import jakarta.ejb.EJB;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Au démarrage : remet en attente les analyses interrompues. Puis ferme chaque heure les candidatures des postes dont la date limite est passée. */
@Singleton
@Startup
public class CloturePostes {

    private static final Logger LOG = Logger.getLogger(CloturePostes.class.getName());

    @EJB private RecrutementService service;

    @jakarta.annotation.PostConstruct
    void auDemarrage() {
        try {
            int n = service.debloquerTout();
            if (n > 0) LOG.info("HRX recrutement : " + n + " analyse(s) interrompue(s) par l'arrêt du serveur remise(s) en attente");
        } catch (Exception e) { LOG.log(Level.WARNING, "HRX recrutement : remise en attente des analyses interrompues en échec", e); }
        verifier();
    }

    @Schedule(hour = "*", minute = "2", persistent = false)
    public void verifier() {
        try {
            List<String> clos = service.cloturerEchus();
            if (!clos.isEmpty()) LOG.info("HRX recrutement : candidatures closes (date limite passée) pour " + clos);
        } catch (Exception e) { LOG.log(Level.WARNING, "HRX recrutement : clôture des postes échus en échec", e); }
    }
}
