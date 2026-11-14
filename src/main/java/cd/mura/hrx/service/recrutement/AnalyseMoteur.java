package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.recrutement.Cv;
import cd.mura.hrx.entity.recrutement.PosteCritere;
import cd.mura.hrx.service.recrutement.RecrutementService.Detail;
import jakarta.annotation.Resource;
import jakarta.ejb.*;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.inject.Inject;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Traitement des analyses d'un poste en arrière-plan : lecture des CV par Claude (une seule fois par CV),
 * appréciation des critères qualitatifs, calcul du score. Les appels à l'API se font hors transaction.
 */
@Stateless
public class AnalyseMoteur {

    private static final Logger LOG = Logger.getLogger(AnalyseMoteur.class.getName());
    /** Postes en cours de traitement (un seul traitement par poste à la fois). */
    private static final Set<Integer> POSTES_ACTIFS = ConcurrentHashMap.newKeySet();
    /** Un CV partagé entre deux postes n'est lu qu'une fois. */
    private static final ConcurrentHashMap<Integer, Object> VERROUS_CV = new ConcurrentHashMap<>();

    @EJB private RecrutementService service;
    @EJB private CvthequeService cvtheque;
    @EJB private AppDAO appDAO;
    @Inject private ClaudeCvService claude;
    @Resource private ManagedExecutorService executor;

    public static boolean actif(int posteId) { return POSTES_ACTIFS.contains(posteId); }

    /** Suivi d'un traitement en cours : heure de début et nombre de CV traités depuis (pour l'avancement et l'estimation). */
    private static final ConcurrentHashMap<Integer, long[]> SUIVI = new ConcurrentHashMap<>();
    public static long debut(int posteId) { long[] v = SUIVI.get(posteId); return v == null ? 0 : v[0]; }
    public static long traites(int posteId) { long[] v = SUIVI.get(posteId); return v == null ? 0 : v[1]; }

    /** Lance le traitement des analyses en attente d'un poste. Renvoie immédiatement. */
    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void lancer(int posteId) {
        if (!POSTES_ACTIFS.add(posteId)) return;
        long[] suivi = {System.currentTimeMillis(), 0};
        SUIVI.put(posteId, suivi);
        try {
            App app = appDAO.findFirst();
            int parallele = app == null || app.getIaParallele() == null ? 3 : Math.max(1, Math.min(8, app.getIaParallele()));
            Semaphore places = new Semaphore(parallele);
            List<Future<?>> taches = new ArrayList<>();
            List<Integer> ids = service.enAttente(posteId);
            LOG.info("HRX recrutement : analyse du poste " + posteId + " lancée (" + ids.size() + " CV, " + parallele + " en parallèle)");
            for (Integer id : ids) {
                places.acquireUninterruptibly();
                taches.add(executor.submit(() -> {
                    try { traiter(app, id); }
                    finally { synchronized (suivi) { suivi[1]++; } places.release(); }
                }));
            }
            for (Future<?> f : taches) try { f.get(); } catch (Exception e) { LOG.log(Level.WARNING, "HRX recrutement : tâche interrompue", e); }
        } finally {
            POSTES_ACTIFS.remove(posteId);
            SUIVI.remove(posteId);
            LOG.info("HRX recrutement : analyse du poste " + posteId + " terminée en " + (System.currentTimeMillis() - suivi[0]) / 1000 + " s (" + suivi[1] + " CV traités)");
        }
    }

    /** Lit des CV de la CVthèque (profil extrait), hors de tout poste. */
    @Asynchronous
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public void lireCvs(List<Integer> cvIds) {
        App app = appDAO.findFirst();
        int parallele = app == null || app.getIaParallele() == null ? 3 : Math.max(1, Math.min(8, app.getIaParallele()));
        Semaphore places = new Semaphore(parallele);
        List<Future<?>> taches = new ArrayList<>();
        for (Integer id : cvIds) {
            places.acquireUninterruptibly();
            taches.add(executor.submit(() -> {
                try { service.relire(id); lire(app, id); }
                catch (Exception e) { LOG.log(Level.INFO, "HRX CVthèque : lecture du CV " + id + " en échec", e); }
                finally { places.release(); }
            }));
        }
        for (Future<?> f : taches) try { f.get(); } catch (Exception ignored) { }
    }

    private void traiter(App app, int analyseId) {
        if (!service.reserver(analyseId)) return;
        try {
            RecrutementService.Travail t = service.travail(analyseId);
            if (t == null) return;
            Cv cv = lire(app, t.cvId());
            List<PosteCritere> criteres = t.poste().getCriteres();
            Map<Integer, Detail> avis = new HashMap<>();
            String synthese = null;
            Map<Integer, Double> anneesDomaine = new HashMap<>();
            List<ClaudeCvService.CritereQualitatif> qualitatifs = criteres.stream().filter(PosteCritere::isApprecieIa)
                    .map(c -> c.isExperienceDomaine()
                            ? new ClaudeCvService.CritereQualitatif(c.getId(), "Expérience dans un domaine",
                                "années d'expérience professionnelle dans ce domaine ou un domaine similaire aux mêmes caractéristiques : " + c.getLibelle().trim()
                                + (c.getValeurMin() != null ? " (au moins " + c.getValeurMin() + " an(s) attendus)" : ""))
                            : new ClaudeCvService.CritereQualitatif(c.getId(), c.getTypeLabel(), c.getLibelle())).toList();
            if (!qualitatifs.isEmpty()) {
                ClaudeCvService.Appreciation ap = claude.apprecier(app, t.poste().getIntitule(), t.poste().getFicheTexte(), qualitatifs, cv.getProfilJson());
                for (ClaudeCvService.AvisCritere a : ap.criteres()) {
                    avis.put(a.critereId(), new Detail(a.critereId(), a.resultat(), a.justification()));
                    if (a.anneesDomaine() >= 0) anneesDomaine.put(a.critereId(), a.anneesDomaine());
                }
                synthese = ap.synthese();
            }
            Notation.Resultat r = Notation.noter(criteres, cv, avis, anneesDomaine);
            if (synthese == null) synthese = cv.getTitreProfil();
            service.resultat(analyseId, r.score(), r.elimine(), r.details(), synthese);
        } catch (Exception e) {
            LOG.log(Level.INFO, "HRX recrutement : analyse " + analyseId + " en échec", e);
            service.echec(analyseId, ClaudeCvService.message(racine(e)));
        }
    }

    /** Profil du CV : déjà lu, ou lu maintenant par Claude. */
    private Cv lire(App app, int cvId) {
        synchronized (VERROUS_CV.computeIfAbsent(cvId, k -> new Object())) {
            try {
                Cv cv = cvtheque.cv(cvId);
                if (cv.isLu()) return cv;
                service.marquerLecture(cvId);
                try {
                    byte[] contenu = cvtheque.contenu(cv);
                    String texte = texte(contenu, cv.getExtension());
                    ClaudeCvService.ProfilCv p = claude.lire(app, contenu, cv.getTypeMime(), cv.getNomFichier(), texte);
                    if ("ILLISIBLE".equalsIgnoreCase(p.lisibilite()) && (p.nomComplet() == null || p.nomComplet().isBlank()))
                        throw new IllegalStateException("Document illisible : numérisez-le en meilleure qualité.");
                    return service.profil(cvId, p, ClaudeCvService.modele(app));
                } catch (RuntimeException e) {
                    service.echecLecture(cvId, ClaudeCvService.message(racine(e)));
                    throw e;
                }
            } finally {
                VERROUS_CV.remove(cvId);
            }
        }
    }

    /** Texte d'un document Word ou texte ; null pour les PDF et images, lus directement par Claude. */
    public static String texte(byte[] contenu, String ext) {
        try {
            if ("docx".equals(ext)) try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(contenu)); XWPFWordExtractor x = new XWPFWordExtractor(d)) { return x.getText(); }
            if ("txt".equals(ext)) return new String(contenu, StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Document Word illisible : " + e.getMessage(), e); }
        return null;
    }

    private static Throwable racine(Throwable e) {
        Throwable c = e;
        while ((c instanceof EJBException || c instanceof ExecutionException || c instanceof CompletionException) && c.getCause() != null) c = c.getCause();
        return c;
    }
}
