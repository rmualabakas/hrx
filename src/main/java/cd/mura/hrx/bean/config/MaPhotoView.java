package cd.mura.hrx.bean.config;

import cd.mura.hrx.entity.config.Users;
import cd.mura.hrx.service.AuditService;
import cd.mura.hrx.service.PhotoService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.enterprise.context.SessionScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;
import org.primefaces.PrimeFaces;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.CroppedImage;
import org.primefaces.model.DefaultStreamedContent;
import org.primefaces.model.StreamedContent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Locale;

/** Mon compte : envoi, recadrage et suppression de sa photo professionnelle (portée session : le recadrage relit l'image dans une requête à part). */
@Named
@SessionScoped
@Getter
@Setter
public class MaPhotoView implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final long TAILLE_MAX = 5L * 1024 * 1024;

    @Inject private LoginBean loginBean;
    @Inject private AccountBean accountBean;
    @Inject private PhotoService photos;
    @Inject private AuditService auditService;

    /** Image envoyée, réduite à 1200 px, en attente de recadrage. */
    private byte[] source;
    private CroppedImage recadrage;

    public void recevoir(FileUploadEvent ev) {
        try {
            if (ev.getFile().getSize() > TAILLE_MAX) { erreur("Image trop lourde", "5 Mo au maximum."); return; }
            String nom = ev.getFile().getFileName() == null ? "" : ev.getFile().getFileName().toLowerCase(Locale.ROOT);
            if (!nom.matches(".*\\.(jpe?g|png)$")) { erreur("Format non accepté", "Choisissez une image JPEG ou PNG."); return; }
            source = PhotoService.reduirePourRecadrage(ev.getFile().getContent(), 1200);
            recadrage = null;
            PrimeFaces.current().ajax().update("dlgPhoto");
            PrimeFaces.current().executeScript("PF('dlgPhoto').show()");
        } catch (Exception e) {
            source = null;
            erreur("Image illisible", "Le fichier n'a pas pu être lu comme une image. Essayez avec une photo JPEG ou PNG.");
        }
    }

    /** Image à recadrer, servie au composant de recadrage. */
    public StreamedContent getImageSource() {
        if (source == null) return null;
        byte[] data = source;
        return DefaultStreamedContent.builder().contentType("image/jpeg").name("photo.jpg").stream(() -> new ByteArrayInputStream(data)).build();
    }

    public void enregistrer() {
        Users moi = loginBean.getUsers();
        if (source == null) { erreur("Aucune image", "Choisissez d'abord une photo."); return; }
        try {
            byte[] image = source;
            if (recadrage != null && recadrage.getWidth() > 0 && recadrage.getHeight() > 0) {
                image = recadrage.getBytes();
                if (image == null || image.length == 0) image = couper(source, recadrage);
            }
            String version = photos.enregistrer(moi.getId(), image);
            moi.setAvatar(version);
            if (accountBean.getProfile() != null) accountBean.getProfile().setAvatar(version);
            source = null; recadrage = null;
            auditService.updated("Mon compte", "Users", moi.getId(), "Photo professionnelle mise à jour", moi.getNomComplet());
            info("Photo enregistrée", "Elle apparaît désormais dans HRX et sur vos fiches.");
            PrimeFaces.current().ajax().addCallbackParam("ok", true);
        } catch (Exception e) {
            erreur("Photo non enregistrée", "L'image n'a pas pu être traitée. Réessayez avec une autre photo.");
        }
    }

    public void supprimer() {
        Users moi = loginBean.getUsers();
        photos.supprimer(moi.getId());
        moi.setAvatar(null);
        if (accountBean.getProfile() != null) accountBean.getProfile().setAvatar(null);
        auditService.updated("Mon compte", "Users", moi.getId(), "Photo professionnelle retirée", moi.getNomComplet());
        info("Photo retirée", "Vos initiales s'affichent à la place.");
    }

    public void annuler() { source = null; recadrage = null; }

    /** Recadrage côté serveur si le composant n'a pas fourni l'image découpée. */
    private static byte[] couper(byte[] src, CroppedImage c) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(src));
        int x = Math.max(0, c.getLeft()), y = Math.max(0, c.getTop());
        int w = Math.min(c.getWidth(), img.getWidth() - x), h = Math.min(c.getHeight(), img.getHeight() - y);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img.getSubimage(x, y, w, h), "jpg", out);
        return out.toByteArray();
    }

    private void info(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_INFO, t, d)); }
    private void erreur(String t, String d) { FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, t, d)); }
}
