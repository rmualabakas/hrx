package cd.mura.hrx.entity.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationTest {

    @Test
    void convertitLesBornesDeLaGrilleSurVingt() {
        assertEquals(0.0, Evaluation.noteSur20(-10), 0.001);
        assertEquals(9.9, Evaluation.noteSur20(50), 0.001);
        assertEquals(10.0, Evaluation.noteSur20(51), 0.001);
        assertEquals(15.0, Evaluation.noteSur20(70), 0.001);
        assertEquals(17.0, Evaluation.noteSur20(85), 0.001);
        assertEquals(20.0, Evaluation.noteSur20(100), 0.001);
        assertEquals(20.0, Evaluation.noteSur20(140), 0.001);
    }

    @Test
    void classeLeNiveauAtteintAuxBornesMetier() {
        assertEquals("NON_ATTEINTS", Evaluation.niveauAtteintePour(50));
        assertEquals("FAIBLEMENT", Evaluation.niveauAtteintePour(51));
        assertEquals("MOYENNEMENT", Evaluation.niveauAtteintePour(70));
        assertEquals("ATTEINTS", Evaluation.niveauAtteintePour(85));
    }

    @Test
    void produitLesAppreciationsAttendues() {
        assertEquals("Mauvais", Evaluation.appreciationPour(9.9));
        assertEquals("Insuffisant", Evaluation.appreciationPour(10));
        assertEquals("Moyennement bon", Evaluation.appreciationPour(15));
        assertEquals("Bon", Evaluation.appreciationPour(17));
        assertEquals("Excellent", Evaluation.appreciationPour(20));
    }
}
