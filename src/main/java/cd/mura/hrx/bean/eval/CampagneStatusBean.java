package cd.mura.hrx.bean.eval;

import cd.mura.hrx.dao.eval.CampagneDAO;
import cd.mura.hrx.entity.eval.Campagne;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Named;
import lombok.Getter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** Pastille de la barre supérieure : campagne ouverte, échéance et jours restants. */
@Named
@RequestScoped
@Getter
public class CampagneStatusBean {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @EJB private CampagneDAO campagneDAO;
    private Campagne campagne;

    @PostConstruct
    public void init() { try { campagne = campagneDAO.findOuverte(); } catch (Exception e) { campagne = null; } }

    public boolean isOuverte() { return campagne != null; }
    public boolean isEnRetard() { return campagne != null && campagne.isEnRetard(); }

    public String getDetail() {
        if (campagne == null) return "aucune campagne ouverte";
        if (campagne.getDateFin() == null) return campagne.getTypeLabel() + " " + campagne.getExercice();
        long j = ChronoUnit.DAYS.between(LocalDate.now(), campagne.getDateFin());
        if (j < 0) return "échéance " + campagne.getDateFin().format(D) + " dépassée de " + (-j) + " j";
        if (j == 0) return "échéance aujourd'hui";
        return "échéance le " + campagne.getDateFin().format(D) + " · J-" + j;
    }
}
