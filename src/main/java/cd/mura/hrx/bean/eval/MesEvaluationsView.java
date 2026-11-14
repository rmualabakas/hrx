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
import java.util.Map;

/** Espace personnel : onglet « Mes objectifs » (fiche de fixation, via ObjectifsView) et onglet « Mes évaluations ». */
@Named
@ViewScoped
@Getter
@Setter
public class MesEvaluationsView implements Serializable {

    private static final long serialVersionUID = 1L;

    @Inject private LoginBean loginBean;
    @EJB private EvaluationDAO evaluationDAO;
    @EJB private cd.mura.hrx.service.PeriodeEssaiService periodeEssai;
    @EJB private cd.mura.hrx.service.EvalChaudService evalChaud;

    private Integer onglet = 0;
    private List<Evaluation> evaluations = new ArrayList<>();
    /** Évaluation de fin de période d'essai du collaborateur, s'il en a une. */
    private cd.mura.hrx.entity.eval.EvaluationEssai essai;
    /** Questionnaires d'évaluation à chaud adressés au collaborateur ; valeur = déjà répondu. */
    private List<Questionnaire> questionnaires = new ArrayList<>();
    public record Questionnaire(cd.mura.hrx.entity.eval.EvalChaud evalChaud, boolean repondu) implements Serializable {
        public cd.mura.hrx.entity.eval.EvalChaud getEvalChaud() { return evalChaud; }
        public boolean isRepondu() { return repondu; }
    }

    /** Fiches à froid des collaborateurs directs : avis du N+1 à donner (en tête) ou déjà donnés. */
    private List<cd.mura.hrx.entity.eval.EvalChaudReponse> avisEquipe = new ArrayList<>();
    public List<cd.mura.hrx.entity.eval.EvalChaudReponse> getAvisEquipe() { return avisEquipe; }
    public long getNbAvisAttente() { return avisEquipe.stream().filter(r -> !r.isAvisDonne()).count(); }

    public void charger() {
        if (onglet == null || onglet < 0 || onglet > 2) onglet = 0;
        if (loginBean.getUsers() == null) return;
        int moi = loginBean.getUsers().getId();
        evaluations = evaluationDAO.findBySalarie(moi);
        essai = periodeEssai.findBySalarie(moi);
        questionnaires = new ArrayList<>();
        for (Map.Entry<cd.mura.hrx.entity.eval.EvalChaud, Boolean> e : evalChaud.mesQuestionnaires(moi).entrySet()) questionnaires.add(new Questionnaire(e.getKey(), e.getValue()));
        avisEquipe = evalChaud.avisEquipe(moi);
    }

    public long getNbQuestionnairesEnAttente() { return questionnaires.stream().filter(q -> !q.repondu() && q.evalChaud().isRepondable()).count() + getNbAvisAttente(); }
}
