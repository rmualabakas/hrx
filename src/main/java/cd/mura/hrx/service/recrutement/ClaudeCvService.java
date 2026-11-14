package cd.mura.hrx.service.recrutement;

import cd.mura.hrx.entity.config.App;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.ServiceUnavailableException;
import com.anthropic.errors.ContentTooLargeException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Accès à l'API Claude (SDK officiel Anthropic) pour la présélection des CV :
 * lecture d'un CV quel que soit son modèle (PDF, scan, image, Word) et appréciation des critères qualitatifs.
 */
@ApplicationScoped
public class ClaudeCvService {

    private static final Logger LOG = Logger.getLogger(ClaudeCvService.class.getName());
    public static final String MODELE_DEFAUT = "claude-opus-5-5";

    // ------------------------------------------------------------------ structures renvoyées par Claude

    public record Experience(
            @JsonPropertyDescription("Intitulé du poste occupé") String poste,
            @JsonPropertyDescription("Employeur") String employeur,
            @JsonPropertyDescription("Début au format AAAA-MM (ou AAAA), chaîne vide si inconnu") String debut,
            @JsonPropertyDescription("Fin au format AAAA-MM (ou AAAA), \"present\" si en cours, chaîne vide si inconnu") String fin,
            @JsonPropertyDescription("true pour un stage, une alternance ou un emploi étudiant") boolean stage) { }

    public record Diplome(
            String intitule,
            @JsonPropertyDescription("Domaine ou spécialité (finance, informatique, droit...)") String domaine,
            String etablissement,
            @JsonPropertyDescription("Année d'obtention, -1 si inconnue") int annee,
            @JsonPropertyDescription("Niveau : 1 secondaire sans bac, 2 bac, 3 bac+2, 4 bac+3, 5 bac+4, 6 bac+5, 7 doctorat, 0 inconnu") int niveau) { }

    public record Langue(String langue, @JsonPropertyDescription("Niveau tel qu'indiqué sur le CV, chaîne vide si absent") String niveau) { }

    public record ProfilCv(
            @JsonPropertyDescription("Prénom et nom du candidat, chaîne vide si absent") String nomComplet,
            String email,
            String telephone,
            @JsonPropertyDescription("Ville et pays de résidence, chaîne vide si absent") String localisation,
            @JsonPropertyDescription("Date de naissance au format AAAA-MM-JJ, seulement si elle figure sur le CV, sinon chaîne vide") String dateNaissance,
            @JsonPropertyDescription("Âge s'il est écrit sur le CV, -1 sinon. Ne jamais l'estimer.") int ageDeclare,
            @JsonPropertyDescription("Titre ou intitulé du profil (ex. Analyste crédit senior)") String titre,
            @JsonPropertyDescription("Résumé factuel du parcours en 2 ou 3 phrases") String resume,
            @JsonPropertyDescription("Années d'expérience professionnelle hors stages, périodes qui se chevauchent comptées une fois, arrondi à 0,5 ; -1 si impossible à déterminer") double experienceAnnees,
            List<Experience> experiences,
            @JsonPropertyDescription("Niveau d'études le plus élevé atteint (même échelle que les diplômes), 0 si inconnu") int niveauEtudes,
            @JsonPropertyDescription("Intitulé du diplôme le plus élevé, chaîne vide si absent") String diplomePrincipal,
            List<Diplome> diplomes,
            List<String> competences,
            List<Langue> langues,
            List<String> certifications,
            @JsonPropertyDescription("BONNE, PARTIELLE (passages illisibles) ou ILLISIBLE") String lisibilite) { }

    public record AvisCritere(
            @JsonPropertyDescription("Identifiant du critère, repris tel quel") int critereId,
            @JsonPropertyDescription("OUI si le CV montre que le critère est satisfait, PARTIEL s'il l'est en partie, NON s'il ne l'est pas ou si le CV ne permet pas de le vérifier") String resultat,
            @JsonPropertyDescription("Justification courte en français, citant les éléments du CV") String justification,
            @JsonPropertyDescription("Critères « Expérience dans un domaine » seulement : années d'expérience professionnelle dans ce domaine ou un domaine similaire aux mêmes caractéristiques (hors stages, périodes qui se chevauchent comptées une fois, arrondi à 0,5). -1 pour tous les autres critères.") double anneesDomaine) { }

    public record Appreciation(
            List<AvisCritere> criteres,
            @JsonPropertyDescription("Synthèse en 2 phrases de l'adéquation du candidat au poste, en français") String synthese) { }

    public record CritereSuggere(
            @JsonPropertyDescription("Type : EXPERIENCE, DIPLOME, DOMAINE, COMPETENCE, LANGUE, CERTIFICATION, LOCALISATION ou AUTRE") String type,
            @JsonPropertyDescription("Énoncé vérifiable sur un CV, en français (ex. « Connaissance des règles KYC et AML/CFT »). EXPERIENCE : domaine exigé s'il y en a un (ex. « surveillance du portefeuille crédits »), sinon chaîne vide. DIPLOME : précision facultative") String libelle,
            @JsonPropertyDescription("EXPERIENCE : années minimum ; DIPLOME : niveau minimum (3 bac+2, 4 bac+3, 5 bac+4, 6 bac+5, 7 doctorat) ; -1 sinon") int valeurMin,
            @JsonPropertyDescription("Pondération de 1 à 5 : 5 pour l'essentiel du métier, 1 pour l'accessoire") int ponderation,
            @JsonPropertyDescription("true seulement pour une exigence ferme de l'offre (diplôme minimum, expérience minimum)") boolean eliminatoire) { }

    public record FichePoste(
            @JsonPropertyDescription("Intitulé du poste") String intitule,
            @JsonPropertyDescription("Référence de l'offre, chaîne vide si absente") String reference,
            @JsonPropertyDescription("Nombre de postes à pourvoir, 1 si non précisé") int nombrePostes,
            @JsonPropertyDescription("Lieu(x) d'affectation, chaîne vide si absent") String lieuAffectation,
            @JsonPropertyDescription("Supérieur hiérarchique direct (ex. Chef d'Agence), chaîne vide si absent") String rattachement,
            @JsonPropertyDescription("Date limite de dépôt au format AAAA-MM-JJ, chaîne vide si absente") String dateLimite,
            @JsonPropertyDescription("Critères de présélection des CV déduits de la fiche") List<CritereSuggere> criteres) { }

    public record ObjectifLu(
            @JsonPropertyDescription("Code de la rubrique HRX, choisi exclusivement dans la liste fournie") String rubrique,
            @JsonPropertyDescription("Activité ou axe de rattachement tel qu'écrit, chaîne vide si absent") String activite,
            @JsonPropertyDescription("Objectif, repris fidèlement du document") String libelle,
            @JsonPropertyDescription("Moyens mis à disposition, chaîne vide si absents") String moyens,
            @JsonPropertyDescription("Indicateur : l'énoncé mesurable complet tel qu'écrit (ex. « 100% des sites disponibles à 95% sur une base mensuelle », « Nombre d'anomalies traitées / nombre d'anomalies remontées »), chaîne vide si absent") String indicateur,
            @JsonPropertyDescription("Cible : uniquement une valeur courte écrite à part dans une colonne ou une mention « cible » distincte (ex. 100 %, 0 incident, 48 h) ; chaîne vide sinon, la cible par défaut est alors 100 %") String cible,
            @JsonPropertyDescription("MENSUEL, BIMESTRIEL, TRIMESTRIEL, SEMESTRIEL, ANNUEL ou PONCTUEL ; TRIMESTRIEL si non précisé") String periodicite,
            @JsonPropertyDescription("Trimestre 1 concerné") boolean t1,
            @JsonPropertyDescription("Trimestre 2 concerné") boolean t2,
            @JsonPropertyDescription("Trimestre 3 concerné") boolean t3,
            @JsonPropertyDescription("Trimestre 4 concerné") boolean t4,
            @JsonPropertyDescription("Échéance au format AAAA-MM-JJ, chaîne vide si absente") String echeance,
            @JsonPropertyDescription("Pondération en pourcentage si le document en donne une, 0 sinon") int poids) { }

    public record FicheObjectifsLue(
            @JsonPropertyDescription("Nom et prénom du salarié tels qu'écrits sur la fiche, chaîne vide si absents") String salarie,
            @JsonPropertyDescription("Exercice de la fiche (année), -1 si absent") int exercice,
            @JsonPropertyDescription("Mission du poste telle qu'écrite (une ligne par point), chaîne vide si absente") String mission,
            @JsonPropertyDescription("Objectifs lus, dans l'ordre du document") List<ObjectifLu> objectifs,
            @JsonPropertyDescription("Points à signaler au relecteur en une ou deux phrases (passages illisibles, rubrique incertaine…), chaîne vide sinon") String remarques) { }

    public record ConstatChiffre(
            @JsonPropertyDescription("Constat court et précis, en français") String constat,
            @JsonPropertyDescription("Chiffre(s) du dépouillement qui l'étayent, repris tels quels (ex. « 4,2/5 ; 83 % favorables ; 5 répondants »)") String chiffres) { }

    public record Recommandation(
            @JsonPropertyDescription("Action concrète et réaliste, en français") String action,
            @JsonPropertyDescription("Constat ou chiffre qui la justifie") String justification,
            @JsonPropertyDescription("HAUTE, MOYENNE ou BASSE") String priorite) { }

    public record AnalyseFormation(
            @JsonPropertyDescription("Synthèse analytique en 4 à 6 phrases : niveau global, ce qui l'explique, fiabilité des résultats") String synthese,
            @JsonPropertyDescription("FIABLE, INDICATIVE ou INSUFFISANTE selon le taux et le nombre de réponses") String fiabilite,
            @JsonPropertyDescription("Une phrase qui justifie la fiabilité retenue") String fiabiliteJustification,
            @JsonPropertyDescription("2 à 4 points forts, chacun étayé par des chiffres") List<ConstatChiffre> pointsForts,
            @JsonPropertyDescription("2 à 4 points de vigilance ou axes d'amélioration, chacun étayé par des chiffres") List<ConstatChiffre> pointsVigilance,
            @JsonPropertyDescription("Enseignements tirés de la dispersion des notes, des écarts entre questions ou entre directions ; liste vide s'il n'y a rien de significatif") List<ConstatChiffre> ecartsSignificatifs,
            @JsonPropertyDescription("Thèmes récurrents des réponses libres, avec le nombre approximatif de mentions ; liste vide s'il n'y a pas de réponses libres") List<ConstatChiffre> themesVerbatims,
            @JsonPropertyDescription("Évaluation à froid seulement : lecture de l'efficacité et du transfert des acquis au poste, en croisant l'avis des collaborateurs et celui des supérieurs ; chaîne vide à chaud") String efficaciteTransfert,
            @JsonPropertyDescription("3 à 5 recommandations classées par priorité") List<Recommandation> recommandations) { }

    /** Critère qualitatif transmis à Claude. */
    public record CritereQualitatif(int id, String type, String enonce) { }

    // ------------------------------------------------------------------ consignes (stables : mises en cache côté API)

    private static final String CONSIGNES_LECTURE = """
            Tu lis des CV pour la direction du capital humain d'une banque. Les CV arrivent dans des modèles très différents \
            (mises en page en colonnes, tableaux, scans, photos, CV en français ou en anglais). Tu en extrais un profil structuré, fidèle au document.

            Règles :
            - N'invente rien. Une information absente du CV reste vide (chaîne vide), à -1 ou à 0 selon le champ.
            - Date de naissance et âge : uniquement s'ils sont écrits sur le CV. Ne déduis jamais un âge de l'année du baccalauréat, des études ou d'une photo.
            - Expérience : additionne les périodes d'emploi professionnel, hors stages et alternances ; une période en cours se termine à la date du jour fournie ; \
            les périodes qui se chevauchent ne comptent qu'une fois. Si le CV donne seulement « X ans d'expérience », reprends ce chiffre.
            - Niveau d'études : 1 secondaire sans bac, 2 baccalauréat ou équivalent (diplôme d'État), 3 bac+2 (BTS, DUT, graduat), 4 bac+3 (licence, bachelor), \
            5 bac+4 (maîtrise, master 1), 6 bac+5 (master, DESS, DEA, ingénieur, MBA), 7 doctorat. Un diplôme en cours ne compte pas comme obtenu.
            - Compétences : techniques et métier, sans répéter les intitulés de postes.
            - Réponds en français pour les champs rédigés.""";

    private static final String CONSIGNES_OBJECTIFS = """
            Tu lis des fiches de fixation des objectifs individuels pour la direction du capital humain d'une banque \
            (modèle « MAF 066 » ou tout autre document listant des objectifs : Word, PDF, scan, photo, tableau). \
            Tu en extrais les objectifs pour pré-remplir la fiche dans l'application ; un responsable relira et corrigera tout avant validation.

            Règles :
            - Reprends chaque objectif fidèlement, sans le reformuler ni en inventer. Ignore les lignes vides du modèle, les exemples \
            et les consignes imprimées du formulaire.
            - Une ligne du tableau = un objectif. Si une cellule contient plusieurs objectifs distincts numérotés, sépare-les.
            - Rubrique : rattache chaque objectif à la rubrique de la liste fournie qui correspond à la section du document où il figure \
            (ex. « I. Tronc commun – A. Activités opérationnelles »). À défaut de section, choisis la plus proche par le sens. \
            N'utilise que les codes fournis.
            - Indicateur et cible : dans la fiche MAF 066, la colonne « Indicateurs » contient en général un énoncé mesurable complet \
            (ex. « 100% des sites disponibles à 95% sur une base mensuelle », « 100% des ressources réseaux sous supervision », \
            « Nombre des fiches établies / nombre de changements »). Cet énoncé entier va dans l'indicateur, même s'il contient des \
            pourcentages : ne le découpe pas et ne le recopie pas dans la cible. La cible n'est renseignée que si le document donne \
            une valeur courte distincte (colonne « Cible », mention « cible : … ») ; sinon laisse-la vide (100 % par défaut).
            - Moyens, activité : reprends-les tels qu'écrits ; chaîne vide s'ils sont absents.
            - Période d'atteinte : coche les trimestres indiqués par le document. S'il n'en indique aucun, coche les trimestres \
            à partir du trimestre de début fourni jusqu'à T4.
            - Pondération : seulement si le document en donne une explicitement, sinon 0.
            - Mission : reprends le texte de la rubrique « Mission » s'il existe.
            - Ne reprends ni appréciations, ni notes, ni signatures.""";

    private static final String CONSIGNES_APPRECIATION = """
            Tu aides la direction du capital humain d'une banque à présélectionner des candidats. Pour un poste, tu reçois des critères \
            et le profil extrait du CV d'un candidat. Le contexte du poste, quand il est fourni, \
            aide à interpréter les critères ; seuls les critères listés sont notés. Pour chaque critère, tu dis s'il est satisfait au vu du CV : OUI, PARTIEL ou NON.

            Règles :
            - Juge uniquement sur ce que montre le profil. Un critère que le CV ne permet pas de vérifier est NON.
            - PARTIEL : le critère est approché (domaine voisin, niveau de langue inférieur, compétence citée sans expérience).
            - Une justification courte et factuelle par critère, qui cite le CV.
            - Ne tiens jamais compte du sexe, de l'origine, de la religion, de la situation familiale ni de la photo : ce ne sont pas des critères.
            - Reprends l'identifiant de chaque critère tel qu'il est fourni, un avis par critère.
            - Critère « Expérience dans un domaine » : parcours les postes du CV un par un. Retiens un poste s'il relève du domaine indiqué \
            ou d'un domaine similaire présentant les mêmes caractéristiques : mêmes missions de fond, mêmes compétences mobilisées, même type \
            de responsabilités (ex. pour la surveillance du portefeuille crédits : analyse ou suivi de crédits, gestion des risques de crédit, \
            recouvrement, engagements). Ne retiens pas un poste qui partage seulement le secteur, l'employeur ou un intitulé voisin \
            (de l'informatique ou du support dans une banque n'est pas de l'expérience en crédit). Additionne les durées retenues dans \
            anneesDomaine et cite dans la justification chaque poste retenu, sa durée et, s'il s'agit d'un domaine similaire, en quoi il l'est. \
            Le résultat (OUI, PARTIEL, NON) est recalculé ensuite à partir de anneesDomaine.
            - Critère « Domaine d'études » : apprécie la formation au sens large, en équilibrant diplôme, formations et expérience, \
            car l'un peut compenser l'autre (un informaticien de formation qui a fait carrière en finance, ou qui a suivi des formations en finance).
              OUI : diplôme dans le domaine ou un domaine équivalent ; ou diplôme dans un autre domaine compensé par une formation \
            qualifiante reconnue dans le domaine (certification, diplôme complémentaire, cursus professionnel de plusieurs mois) ou par \
            au moins 2 ans d'expérience professionnelle réellement exercée dans le domaine.
              PARTIEL : diplôme dans un autre domaine avec seulement des modules, des formations courtes ou moins de 2 ans d'expérience dans le domaine.
              NON : ni diplôme, ni formation, ni expérience dans le domaine.
              La compensation doit être substantielle et figurer sur le CV : un module isolé ou un intitulé de poste ne suffit pas pour OUI. \
            Dans la justification, indique le diplôme puis ce qui compense (formation ou expérience, avec sa durée).""";

    private static final String CONSIGNES_FICHE = """
            Tu lis une fiche de poste (offre d'emploi) d'une banque, souvent un document scanné. Tu en reprends l'en-tête \
            (intitulé, référence, nombre de postes, lieu d'affectation, rattachement, date limite) sans rien inventer : un élément absent reste vide. \
            Puis tu proposes les critères qui serviront à présélectionner les CV reçus.

            Règles pour les critères :
            - Uniquement ce qu'un CV permet de vérifier : diplôme et domaine d'études, années d'expérience, connaissances et savoir-faire \
            techniques, langues, certifications. Le savoir-être et les comportements relèvent de l'entretien : ne les transforme jamais en critères.
            - DIPLOME : niveau minimum exigé ; DOMAINE : les domaines d'études cités ; EXPERIENCE : nombre d'années minimum. Ces exigences fermes sont éliminatoires.
            - Si l'expérience est exigée dans un domaine (« 3 ans dans le domaine », « en banque », « en crédit »), mets ce domaine, explicité \
            d'après la fiche, dans le libellé du critère EXPERIENCE : seules les années dans ce domaine seront comptées. Sinon, libellé vide.
            - Regroupe les connaissances proches en un critère : 5 à 10 critères au total.
            - Jamais de critère d'âge, de sexe, d'origine, de religion ou de situation familiale, même si la fiche encourage un public particulier.
            - Le lieu d'affectation n'est pas un critère éliminatoire.""";

    // ------------------------------------------------------------------ client

    private AnthropicClient client;
    private String cleClient;

    private synchronized AnthropicClient client(App app) {
        String cle = cle(app);
        if (cle == null) throw new IllegalStateException("Aucune clé d'API Claude n'est configurée (Paramètres > IA).");
        if (client == null || !cle.equals(cleClient)) {
            if (client != null) try { client.close(); } catch (Exception ignored) { }
            client = AnthropicOkHttpClient.builder().apiKey(cle).maxRetries(3).build();
            cleClient = cle;
        }
        return client;
    }

    @PreDestroy
    synchronized void fermer() { if (client != null) try { client.close(); } catch (Exception ignored) { } }

    static String cle(App app) {
        String c = app == null ? null : app.getIaCleApi();
        if (c == null || c.isBlank()) c = System.getenv("ANTHROPIC_API_KEY");
        return c == null || c.isBlank() ? null : c.trim();
    }
    static String modele(App app) { return app == null || app.getIaModele() == null || app.getIaModele().isBlank() ? MODELE_DEFAUT : app.getIaModele().trim(); }
    private static OutputConfig.Effort effort(App app) {
        String e = app == null || app.getIaEffort() == null ? "medium" : app.getIaEffort().toLowerCase(Locale.ROOT);
        return switch (e) { case "low" -> OutputConfig.Effort.LOW; case "high" -> OutputConfig.Effort.HIGH; default -> OutputConfig.Effort.MEDIUM; };
    }

    // ------------------------------------------------------------------ appels

    /**
     * Lit un CV. Les PDF et images sont transmis tels quels (Claude lit aussi les scans) ; un document Word
     * est transmis par son texte.
     */
    public ProfilCv lire(App app, byte[] contenu, String typeMime, String nomFichier, String texte) {
        ContentBlockParam doc = bloc(contenu, typeMime, nomFichier, texte, "du CV");
        java.util.function.LongFunction<StructuredMessageCreateParams<ProfilCv>> params = max -> MessageCreateParams.builder()
                .model(modele(app))
                .maxTokens(max)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(CONSIGNES_LECTURE)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessageOfContentBlockParams(List.of(doc, ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("Date du jour : " + LocalDate.now() + ". Fichier : " + nomFichier + ". Extrais le profil de ce CV.").build())))
                .outputConfig(StructuredOutputConfig.<ProfilCv>builder().format(ProfilCv.class, JsonSchemaLocalValidation.YES).effort(effort(app)).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        return premier(appeler(app, params), "lecture du CV");
    }

    /** Lit une fiche de poste (PDF, scan, image ou texte d'un Word) et propose des critères de présélection. */
    public FichePoste lireFiche(App app, byte[] contenu, String typeMime, String nomFichier, String texte) {
        java.util.function.LongFunction<StructuredMessageCreateParams<FichePoste>> params = max -> MessageCreateParams.builder()
                .model(modele(app))
                .maxTokens(max)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(CONSIGNES_FICHE)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessageOfContentBlockParams(List.of(bloc(contenu, typeMime, nomFichier, texte, "de la fiche de poste"), ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("Date du jour : " + LocalDate.now() + ". Fichier : " + nomFichier + ". Reprends l'en-tête de cette fiche de poste et propose les critères.").build())))
                .outputConfig(StructuredOutputConfig.<FichePoste>builder().format(FichePoste.class, JsonSchemaLocalValidation.YES).effort(effort(app)).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        return premier(appeler(app, params), "lecture de la fiche de poste");
    }

    /**
     * Lit une fiche de fixation des objectifs (PDF, scan, image ou texte d'un Word) et en extrait les objectifs,
     * rattachés aux rubriques HRX fournies ({@code rubriques} : une ligne « CODE : groupe - libellé (aide) » par rubrique).
     */
    public FicheObjectifsLue lireObjectifs(App app, byte[] contenu, String typeMime, String nomFichier, String texte, String rubriques, int exercice, int trimestreDebut) {
        java.util.function.LongFunction<StructuredMessageCreateParams<FicheObjectifsLue>> params = max -> MessageCreateParams.builder()
                .model(modele(app))
                .maxTokens(max)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(CONSIGNES_OBJECTIFS)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessageOfContentBlockParams(List.of(bloc(contenu, typeMime, nomFichier, texte, "de la fiche d'objectifs"), ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("Fichier : " + nomFichier + ". Exercice en cours : " + exercice + ". Trimestre de début : T" + trimestreDebut
                                + ".\nRubriques HRX (code : groupe - rubrique) :\n" + rubriques + "\nExtrais les objectifs de cette fiche.").build())))
                .outputConfig(StructuredOutputConfig.<FicheObjectifsLue>builder().format(FicheObjectifsLue.class, JsonSchemaLocalValidation.YES).effort(effort(app)).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        return premier(appeler(app, params), "lecture de la fiche d'objectifs");
    }

    /** Document transmis à Claude : PDF et images tels quels (scans compris), sinon le texte extrait. */
    private static ContentBlockParam bloc(byte[] contenu, String typeMime, String nomFichier, String texte, String quoi) {
        String mime = typeMime == null ? "" : typeMime.toLowerCase(Locale.ROOT);
        String b64 = contenu == null ? null : Base64.getEncoder().encodeToString(contenu);
        if (mime.equals("application/pdf"))
            return ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(b64).build()).title(nomFichier).build());
        if (mime.startsWith("image/"))
            return ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder().data(b64).mediaType(Base64ImageSource.MediaType.of(mime.equals("image/jpg") ? "image/jpeg" : mime)).build()).build());
        if (texte == null || texte.isBlank()) throw new IllegalArgumentException("Le document ne contient pas de texte lisible.");
        return ContentBlockParam.ofText(TextBlockParam.builder().text("Contenu " + quoi + " « " + nomFichier + " » :\n\n" + texte).build());
    }

    /** Apprécie des critères qualitatifs pour un candidat, à partir de son profil (JSON). */
    public Appreciation apprecier(App app, String intitulePoste, String descriptionPoste, List<CritereQualitatif> criteres, String profilJson) {
        StringBuilder sb = new StringBuilder("Poste : ").append(intitulePoste).append('\n');
        if (descriptionPoste != null && !descriptionPoste.isBlank()) sb.append("Contexte du poste : ").append(descriptionPoste).append('\n');
        sb.append("\nCritères à apprécier :\n");
        for (CritereQualitatif c : criteres) sb.append("- [").append(c.id()).append("] ").append(c.type()).append(" : ").append(c.enonce()).append('\n');
        sb.append("\nProfil du candidat (extrait de son CV) :\n").append(profilJson);
        java.util.function.LongFunction<StructuredMessageCreateParams<Appreciation>> params = max -> MessageCreateParams.builder()
                .model(modele(app))
                .maxTokens(max)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(CONSIGNES_APPRECIATION)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessage(sb.toString())
                .outputConfig(StructuredOutputConfig.<Appreciation>builder().format(Appreciation.class, JsonSchemaLocalValidation.YES).effort(effort(app)).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        return premier(appeler(app, params), "appréciation des critères");
    }

    private static final String CONSIGNES_ANALYSE_FORMATION = """
            Tu es analyste à la direction du capital humain d'une banque. Tu analyses le dépouillement d'une évaluation de formation \
            (à chaud : satisfaction en fin de formation, fiche MAF 063 ; à froid : efficacité et impact quelques mois après, fiche MAF 064, \
            complétée par l'avis du supérieur hiérarchique). Ton analyse figure dans le rapport remis aux responsables formation.

            Règles :
            - Sois analytique : appuie chaque constat sur les chiffres fournis (moyennes, % favorables, médiane, écart-type, taux de réponse, \
            répartition par direction). Ne cite que des chiffres présents dans les données ; n'en invente aucun et n'extrapole pas.
            - Interprète, ne recopie pas : explique ce que les chiffres signifient (consensus ou dispersion, écart à la moyenne, cohérence \
            entre questions, entre les réponses des collaborateurs et l'avis des supérieurs).
            - Tiens compte de la taille de l'échantillon : avec peu de répondants ou un taux de réponse faible, dis-le et nuance les conclusions.
            - Échelle des notes : 1 à 5, 4-5 favorables, 1-2 défavorables ; pour une échelle « centrée », 3 = adapté et 1 ou 5 = inadapté.
            - Les réponses sont anonymes : ne cherche jamais à identifier une personne.
            - Rédige en français professionnel, phrases courtes, sans jargon. Pas de formules creuses.""";

    /** Analyse rédigée par Claude du dépouillement d'une évaluation de formation (données fournies en texte). */
    public AnalyseFormation analyserFormation(App app, String donnees) {
        java.util.function.LongFunction<StructuredMessageCreateParams<AnalyseFormation>> params = max -> MessageCreateParams.builder()
                .model(modele(app))
                .maxTokens(max)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(CONSIGNES_ANALYSE_FORMATION)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                .addUserMessage("Voici le dépouillement à analyser :\n\n" + donnees)
                .outputConfig(StructuredOutputConfig.<AnalyseFormation>builder().format(AnalyseFormation.class, JsonSchemaLocalValidation.YES).effort(effort(app)).build())
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        return premier(appeler(app, params), "analyse du dépouillement");
    }

    /** Vérifie la clé et le modèle sans rien générer. */
    public String tester(App app) {
        try {
            var m = client(app).models().retrieve(modele(app));
            return "Connexion établie : modèle " + m.displayName() + " (" + m.id() + ") disponible.";
        } catch (RuntimeException e) { throw new IllegalStateException(message(e), e); }
    }

    /** Limite de réponse habituelle, puis limite élargie pour une seconde tentative quand la réponse a été coupée. */
    private static final long MAX_TOKENS = 16000L, MAX_TOKENS_LONG = 48000L;

    /**
     * Appelle Claude. Si la réponse est coupée par la limite de longueur (CV très long ou très dense), l'appel est
     * refait une fois avec une limite plus large avant d'abandonner.
     */
    private <T> StructuredMessage<T> appeler(App app, java.util.function.LongFunction<StructuredMessageCreateParams<T>> params) {
        StructuredMessage<T> r = appeler(app, params.apply(MAX_TOKENS));
        if (r.stopReason().filter(StopReason.MAX_TOKENS::equals).isEmpty()) return r;
        LOG.info("HRX IA : réponse coupée à " + MAX_TOKENS + " tokens, nouvelle tentative à " + MAX_TOKENS_LONG);
        r = appeler(app, params.apply(MAX_TOKENS_LONG));
        if (r.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent())
            throw new IllegalStateException(TRONQUEE);
        return r;
    }

    /** Message enregistré quand la réponse reste coupée malgré la seconde tentative. */
    public static final String TRONQUEE = "Réponse de Claude tronquée (document trop long).";

    private <T> StructuredMessage<T> appeler(App app, StructuredMessageCreateParams<T> params) {
        try {
            StructuredMessage<T> r = client(app).messages().create(params);
            r.stopReason().ifPresent(s -> {
                if (s.equals(StopReason.REFUSAL)) throw new IllegalStateException("Claude a refusé de traiter ce document.");
            });
            return r;
        } catch (IllegalStateException e) { throw e;
        } catch (RuntimeException e) { throw new IllegalStateException(message(e), e); }
    }

    private static <T> T premier(StructuredMessage<T> r, String quoi) {
        return r.content().stream().flatMap(b -> b.text().stream()).map(StructuredTextBlock::text).findFirst()
                .orElseThrow(() -> new IllegalStateException("Réponse de Claude vide pour la " + quoi + "."));
    }

    /** Message d'erreur lisible pour l'utilisateur. */
    static String message(Throwable e) {
        if (e instanceof UnauthorizedException) return "Clé d'API Claude refusée : vérifiez-la dans Paramètres > IA.";
        if (e instanceof PermissionDeniedException) return "Cette clé d'API n'a pas accès à ce modèle.";
        if (e instanceof NotFoundException) return "Modèle Claude inconnu : vérifiez le nom du modèle dans Paramètres > IA.";
        if (e instanceof RateLimitException) return "Limite de débit de l'API Claude atteinte : réessayez dans quelques minutes ou réduisez le parallélisme.";
        if (e instanceof OverloadedException || e instanceof ServiceUnavailableException) return "L'API Claude est momentanément surchargée : relancez l'analyse dans quelques minutes.";
        if (e instanceof ContentTooLargeException) return "Document trop volumineux pour l'API Claude.";
        if (e instanceof AnthropicServiceException s) return "Erreur de l'API Claude (" + s.statusCode() + ") : " + e.getMessage();
        LOG.warning("HRX IA : " + e);
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
