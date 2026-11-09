package cd.mura.hrx.service;

import cd.mura.hrx.dao.config.UserDAO;
import cd.mura.hrx.entity.config.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Photos professionnelles des collaborateurs.
 * Un fichier JPEG carré de 400 px par personne, rangé dans le dossier de données de WildFly (hrx/photos/{id}.jpg).
 * La colonne users.avatar garde un numéro de version (horodatage) : il sert à savoir qu'une photo existe et à rafraîchir les navigateurs.
 */
@Named("photo")
@ApplicationScoped
public class PhotoService implements Serializable {

    private static final Logger LOG = Logger.getLogger(PhotoService.class.getName());
    public static final int TAILLE = 400;

    @Inject private UserDAO userDAO;

    private Path dossier;

    @PostConstruct
    void init() {
        String base = System.getProperty("jboss.server.data.dir", System.getProperty("user.home"));
        dossier = Path.of(base, "hrx", "photos");
        try { Files.createDirectories(dossier); } catch (IOException e) { LOG.warning("HRX : dossier des photos inaccessible " + dossier + " : " + e.getMessage()); }
    }

    private Path fichier(int userId) { return dossier.resolve(userId + ".jpg"); }

    /** Vrai si le collaborateur a une photo. */
    public boolean aPhoto(Users u) {
        return u != null && u.getAvatar() != null && u.getAvatar().matches("\\d+") && Files.exists(fichier(u.getId()));
    }

    /** Adresse de la photo pour une balise img, ou null s'il n'y en a pas. */
    public String url(Users u) {
        if (!aPhoto(u)) return null;
        String ctx = FacesContext.getCurrentInstance() == null ? "" : FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath();
        return ctx + "/photo/" + u.getId() + "?v=" + u.getAvatar();
    }

    /** Octets JPEG de la photo, ou null. */
    public byte[] octets(int userId) {
        try { Path f = fichier(userId); return Files.exists(f) ? Files.readAllBytes(f) : null; } catch (IOException e) { return null; }
    }

    /** Octets JPEG de la photo d'un collaborateur s'il en a une (pour les documents), sinon null. */
    public byte[] octets(Users u) { return aPhoto(u) ? octets(u.getId()) : null; }

    /**
     * Enregistre la photo recadrée : mise au carré, réduite à 400 px, JPEG.
     * @return la nouvelle version, à reporter dans users.avatar
     */
    public String enregistrer(int userId, byte[] image) throws IOException {
        BufferedImage src = lireImage(image);
        int cote = Math.min(src.getWidth(), src.getHeight());
        BufferedImage carre = src.getSubimage((src.getWidth() - cote) / 2, (src.getHeight() - cote) / 2, cote, cote);
        int t = Math.min(TAILLE, cote);
        BufferedImage out = new BufferedImage(t, t, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, t, t);   // fond blanc pour les PNG transparents
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(carre, 0, 0, t, t, null);
        g.dispose();

        Path tmp = Files.createTempFile(dossier, "p" + userId, ".tmp");
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(tmp.toFile())) {
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.9f);
            w.setOutput(ios);
            w.write(null, new IIOImage(out, null, null), p);
        } finally { w.dispose(); }
        Files.move(tmp, fichier(userId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        String version = String.valueOf(System.currentTimeMillis());
        userDAO.majAvatar(userId, version);
        return version;
    }

    public void supprimer(int userId) {
        try { Files.deleteIfExists(fichier(userId)); } catch (IOException e) { LOG.warning("HRX : photo " + userId + " non supprimée : " + e.getMessage()); }
        userDAO.majAvatar(userId, null);
    }

    /** Au-delà, l'image décodée pèserait des centaines de Mo en mémoire (bombe de décompression). */
    private static final long PIXELS_MAX = 40_000_000L;

    /** Décode une image JPEG/PNG après avoir vérifié ses dimensions dans l'en-tête, sans tout décompresser. */
    static BufferedImage lireImage(byte[] image) throws IOException {
        if (image == null || image.length == 0) throw new IOException("Image vide.");
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            if (in == null) throw new IOException("Format d'image non reconnu.");
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Format d'image non reconnu.");
            javax.imageio.ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg") && !format.equals("png")) throw new IOException("Format d'image non accepté.");
                reader.setInput(in, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w * h > PIXELS_MAX) throw new IOException("Dimensions d'image non acceptées.");
                BufferedImage img = reader.read(0);
                if (img == null) throw new IOException("Format d'image non reconnu.");
                return img;
            } finally { reader.dispose(); }
        }
    }

    /** Réduit une image trop grande avant le recadrage (affichage plus rapide). */
    public static byte[] reduirePourRecadrage(byte[] image, int max) throws IOException {
        BufferedImage src = lireImage(image);
        double r = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = (int) Math.round(src.getWidth() * r), h = (int) Math.round(src.getHeight() * r);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bos);
        return bos.toByteArray();
    }
}
