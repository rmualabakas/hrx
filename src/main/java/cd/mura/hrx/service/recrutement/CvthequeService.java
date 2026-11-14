package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.dao.config.AppDAO;
import cd.mura.hrx.entity.config.App;
import cd.mura.hrx.entity.recrutement.Cv;
import cd.mura.hrx.entity.recrutement.CvAnalyse;
import cd.mura.hrx.entity.recrutement.CvDossier;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

/** CVthèque : stockage des fichiers sur disque, dossiers, rangement, archivage. */
@Stateless
public class CvthequeService {

    @PersistenceContext(unitName = "PU")
    private EntityManager em;
    @EJB private AppDAO appDAO;

    public static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp", "txt", "text/plain");
    public static final String RACINE_CANDIDATURES = "Candidatures";

    // ------------------------------------------------------------------ stockage

    /** Répertoire des fichiers : {répertoire racine des paramètres}/cvtheque, à défaut le répertoire de données de WildFly. */
    public Path repertoire() {
        App app = appDAO.findFirst();
        String base = app == null ? null : app.getRepBaseFichier();
        Path p = base == null || base.isBlank()
                ? Paths.get(System.getProperty("jboss.server.data.dir", System.getProperty("java.io.tmpdir")), "hrx", "cvtheque")
                : Paths.get(base.trim(), "cvtheque");
        try { Files.createDirectories(p); } catch (IOException e) { throw new IllegalStateException("Répertoire de la CVthèque inaccessible : " + p, e); }
        return p;
    }

    public static String extension(String nom) {
        int i = nom == null ? -1 : nom.lastIndexOf('.');
        return i < 0 ? "" : nom.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    /** Enregistre un CV. Un fichier identique déjà présent n'est pas dupliqué : la fiche existante est renvoyée. */
    public Cv importer(byte[] contenu, String nomFichier, Integer dossierId, int userId) {
        String ext = extension(nomFichier);
        String mime = TYPES.get(ext);
        if (mime == null) throw new IllegalArgumentException("Format non pris en charge pour « " + nomFichier + " » : PDF, Word (.docx), image (JPG, PNG) ou texte.");
        if (contenu == null || contenu.length == 0) throw new IllegalArgumentException("Le fichier « " + nomFichier + " » est vide.");
        String sha = sha256(contenu);
        List<Cv> ex = em.createQuery("select c from Cv c where c.sha256 = :s", Cv.class).setParameter("s", sha).getResultList();
        if (!ex.isEmpty()) {
            Cv c = ex.get(0);
            if (c.isArchive()) c.setArchive(false);
            return c;
        }
        Path f = repertoire().resolve(sha + "." + ext);
        try { if (!Files.exists(f)) Files.write(f, contenu); } catch (IOException e) { throw new IllegalStateException("Écriture du fichier impossible : " + e.getMessage(), e); }
        Cv c = new Cv();
        c.setNomFichier(nomFichier.length() > 255 ? nomFichier.substring(nomFichier.length() - 255) : nomFichier);
        c.setTypeMime(mime); c.setTaille(contenu.length); c.setSha256(sha); c.setChemin(f.getFileName().toString());
        c.setDossier(dossierId == null ? null : em.find(CvDossier.class, dossierId));
        c.setImportePar(userId);
        em.persist(c);
        return c;
    }

    public byte[] contenu(Cv c) {
        try { return Files.readAllBytes(repertoire().resolve(c.getChemin())); }
        catch (IOException e) { throw new IllegalStateException("Fichier du CV introuvable sur le serveur (" + c.getChemin() + ")", e); }
    }

    public Cv cv(int id) { return em.find(Cv.class, id); }

    /** Limite le rendu simultané des miniatures (PDFBox consomme de la mémoire). */
    private static final java.util.concurrent.Semaphore RENDU = new java.util.concurrent.Semaphore(2);
    private static final int LARGEUR_MINI = 240;

    /**
     * Miniature PNG de la première page (PDF) ou de l'image, mise en cache sur disque à côté des CV.
     * Renvoie null pour les formats sans aperçu (Word, texte) ou si le rendu échoue.
     */
    public byte[] miniature(Cv c) {
        String ext = extension(c.getNomFichier());
        if (!java.util.Set.of("pdf", "jpg", "jpeg", "png").contains(ext)) return null;
        try {
            Path dir = repertoire().resolve("miniatures");
            Files.createDirectories(dir);
            Path f = dir.resolve(c.getSha256() + ".png");
            if (Files.exists(f)) return Files.readAllBytes(f);
            RENDU.acquire();
            try {
                if (Files.exists(f)) return Files.readAllBytes(f);
                java.awt.image.BufferedImage src;
                if ("pdf".equals(ext)) {
                    try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(repertoire().resolve(c.getChemin()).toFile(),
                            org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())) {
                        if (doc.getNumberOfPages() == 0) return null;
                        float largeur = doc.getPage(0).getMediaBox().getWidth();
                        float echelle = largeur <= 0 ? 0.4f : LARGEUR_MINI / largeur;
                        src = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImage(0, echelle, org.apache.pdfbox.rendering.ImageType.RGB);
                    }
                } else {
                    src = javax.imageio.ImageIO.read(repertoire().resolve(c.getChemin()).toFile());
                    if (src == null) return null;
                }
                java.awt.image.BufferedImage mini = reduire(src);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(mini, "png", out);
                byte[] png = out.toByteArray();
                Files.write(f, png);
                return png;
            } finally { RENDU.release(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception | OutOfMemoryError e) {
            java.util.logging.Logger.getLogger(CvthequeService.class.getName()).warning("Miniature impossible pour le CV " + c.getId() + " : " + e.getMessage());
            return null;
        }
    }

    private static java.awt.image.BufferedImage reduire(java.awt.image.BufferedImage src) {
        if (src.getWidth() <= LARGEUR_MINI) return src;
        int h = Math.max(1, Math.round(src.getHeight() * (LARGEUR_MINI / (float) src.getWidth())));
        h = Math.min(h, LARGEUR_MINI * 2);
        java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(LARGEUR_MINI, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = dst.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, LARGEUR_MINI, h);
        int hs = Math.round(src.getHeight() * (LARGEUR_MINI / (float) src.getWidth()));
        g.drawImage(src, 0, 0, LARGEUR_MINI, hs, null);
        g.dispose();
        return dst;
    }

    private static String sha256(byte[] b) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------------ dossiers

    public List<CvDossier> dossiers() {
        return em.createQuery("select d from CvDossier d left join fetch d.parent order by d.nom", CvDossier.class).getResultList();
    }

    public CvDossier creerDossier(String nom, Integer parentId, int userId) {
        String n = nettoyer(nom);
        CvDossier parent = parentId == null ? null : em.find(CvDossier.class, parentId);
        if (existe(n, parent, 0)) throw new IllegalArgumentException("Un dossier « " + n + " » existe déjà à cet endroit.");
        CvDossier d = new CvDossier();
        d.setNom(n); d.setParent(parent); d.setCreePar(userId);
        em.persist(d);
        return d;
    }

    public void renommerDossier(int id, String nom) {
        CvDossier d = em.find(CvDossier.class, id);
        if (d == null) return;
        String n = nettoyer(nom);
        if (existe(n, d.getParent(), id)) throw new IllegalArgumentException("Un dossier « " + n + " » existe déjà à cet endroit.");
        d.setNom(n);
    }

    public void deplacerDossier(int id, Integer parentId) {
        CvDossier d = em.find(CvDossier.class, id);
        if (d == null) return;
        CvDossier p = parentId == null ? null : em.find(CvDossier.class, parentId);
        for (CvDossier x = p; x != null; x = x.getParent()) if (x.getId() == id) throw new IllegalArgumentException("Un dossier ne peut pas être déplacé dans lui-même.");
        if (existe(d.getNom(), p, id)) throw new IllegalArgumentException("Un dossier « " + d.getNom() + " » existe déjà à cet endroit.");
        d.setParent(p);
    }

    /** Supprime un dossier vide (ni sous-dossier ni CV, archivés compris). */
    public void supprimerDossier(int id) {
        CvDossier d = em.find(CvDossier.class, id);
        if (d == null) return;
        long sous = em.createQuery("select count(d) from CvDossier d where d.parent.id = :i", Long.class).setParameter("i", id).getSingleResult();
        long cvs = em.createQuery("select count(c) from Cv c where c.dossier.id = :i", Long.class).setParameter("i", id).getSingleResult();
        if (sous + cvs > 0) throw new IllegalStateException("Le dossier n'est pas vide (" + sous + " sous-dossier(s), " + cvs + " CV, archives comprises).");
        em.remove(d);
    }

    /** Dossier où ranger les CV reçus pour un poste : Candidatures / {poste}. */
    public CvDossier dossierPoste(String nomPoste, int userId) {
        CvDossier racine = trouver(RACINE_CANDIDATURES, null);
        if (racine == null) racine = creerDossier(RACINE_CANDIDATURES, null, userId);
        String n = nettoyer(nomPoste);
        CvDossier d = trouver(n, racine);
        return d != null ? d : creerDossier(n, racine.getId(), userId);
    }

    private CvDossier trouver(String nom, CvDossier parent) {
        List<CvDossier> l = (parent == null
                ? em.createQuery("select d from CvDossier d where d.parent is null and lower(d.nom) = lower(:n)", CvDossier.class)
                : em.createQuery("select d from CvDossier d where d.parent.id = :p and lower(d.nom) = lower(:n)", CvDossier.class).setParameter("p", parent.getId()))
                .setParameter("n", nom).getResultList();
        return l.isEmpty() ? null : l.get(0);
    }
    private boolean existe(String nom, CvDossier parent, int saufId) { CvDossier d = trouver(nom, parent); return d != null && d.getId() != saufId; }

    private static String nettoyer(String nom) {
        String n = nom == null ? "" : nom.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Le nom du dossier est obligatoire.");
        return n.length() > 150 ? n.substring(0, 150) : n;
    }

    // ------------------------------------------------------------------ CV

    /** CV d'un dossier (null : racine), ou de toute la CVthèque si une recherche est saisie. */
    public List<Cv> lister(Integer dossierId, String recherche, boolean archives) {
        return lister(dossierId, recherche, archives, false);
    }

    /** @param toutSansDossier sans dossier ni recherche : tous les CV au lieu de ceux de la racine seule. */
    public List<Cv> lister(Integer dossierId, String recherche, boolean archives, boolean toutSansDossier) {
        StringBuilder q = new StringBuilder("select c from Cv c left join fetch c.dossier where c.archive = :a");
        boolean rech = recherche != null && !recherche.isBlank();
        if (rech) q.append(" and (lower(c.nomFichier) like :r or lower(c.candidatNom) like :r or lower(c.titreProfil) like :r or lower(c.email) like :r or lower(c.etiquettes) like :r or lower(c.diplome) like :r or lower(c.profilJson) like :r)");
        else if (dossierId != null) q.append(" and c.dossier.id = :d");
        else if (!toutSansDossier) q.append(" and c.dossier is null");
        q.append(" order by c.dateImport desc, c.id desc");
        var query = em.createQuery(q.toString(), Cv.class).setParameter("a", archives);
        if (rech) query.setParameter("r", "%" + recherche.trim().toLowerCase(Locale.ROOT) + "%");
        else if (dossierId != null) query.setParameter("d", dossierId);
        return query.getResultList();
    }

    /** Nombre de CV non archivés par dossier (clé null : racine). */
    public Map<Integer, Long> compteParDossier() {
        Map<Integer, Long> m = new HashMap<>();
        for (Object[] r : em.createQuery("select d.id, count(c) from Cv c left join c.dossier d where c.archive = false group by d.id", Object[].class).getResultList())
            m.put((Integer) r[0], (Long) r[1]);
        return m;
    }

    public void deplacer(Collection<Integer> ids, Integer dossierId) {
        CvDossier d = dossierId == null ? null : em.find(CvDossier.class, dossierId);
        for (Integer id : ids) { Cv c = em.find(Cv.class, id); if (c != null) c.setDossier(d); }
    }

    public void archiver(Collection<Integer> ids, boolean archive) {
        for (Integer id : ids) { Cv c = em.find(Cv.class, id); if (c != null) c.setArchive(archive); }
    }

    public void renommer(int id, String nomFichier) {
        Cv c = em.find(Cv.class, id);
        if (c == null || nomFichier == null || nomFichier.isBlank()) return;
        String n = nomFichier.trim();
        String ext = c.getExtension();
        if (!ext.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith("." + ext)) n = n + "." + ext;
        c.setNomFichier(n.length() > 255 ? n.substring(0, 255) : n);
    }

    public void annoter(int id, String etiquettes, String notes) {
        Cv c = em.find(Cv.class, id);
        if (c == null) return;
        c.setEtiquettes(etiquettes == null || etiquettes.isBlank() ? null : etiquettes.trim());
        c.setNotes(notes == null || notes.isBlank() ? null : notes.trim());
    }

    /** Suppression définitive : fiche, analyses et fichier. */
    public int supprimer(Collection<Integer> ids) {
        int n = 0;
        for (Integer id : ids) {
            Cv c = em.find(Cv.class, id);
            if (c == null) continue;
            em.createQuery("delete from CvAnalyse a where a.cv.id = :c").setParameter("c", id).executeUpdate();
            String chemin = c.getChemin();
            em.remove(c);
            try { Files.deleteIfExists(repertoire().resolve(chemin)); } catch (IOException ignored) { }
            n++;
        }
        return n;
    }

    public long nbAnalyses(int cvId) {
        return em.createQuery("select count(a) from CvAnalyse a where a.cv.id = :c", Long.class).setParameter("c", cvId).getSingleResult();
    }

    /** Postes pour lesquels un CV a été analysé, avec son score. */
    public List<CvAnalyse> analysesDe(int cvId) {
        return em.createQuery("select a from CvAnalyse a join fetch a.poste where a.cv.id = :c order by a.dateAjout desc", CvAnalyse.class)
                .setParameter("c", cvId).getResultList();
    }
}
