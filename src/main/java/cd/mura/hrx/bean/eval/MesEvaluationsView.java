package cd.mura.hrx.bean.eval;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.eval.EvaluationDAO;
import cd.mura.hrx.entity.eval.Evaluation;
import jakarta.ejb.EJB;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Espace personnel : onglet « Mes objectifs » (fiche de fixation, via ObjectifsView) et onglet « Mes évaluations ». */
@Named
@ViewScoped
@Getter
@Setter
public class MesEvaluationsView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @EJB private EvaluationDAO evaluationDAO;

    private Integer onglet = 0;
    private List<Evaluation> evaluations = new ArrayList<>();

    public void charger() {
        if (onglet == null || onglet < 0 || onglet > 1) onglet = 0;
        if (loginBean.getUsers() != null) evaluations = evaluationDAO.findBySalarie(loginBean.getUsers().getId());
    }
}
