package cd.mura.hrx.include;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Politique des mots de passe locaux, appliquée côté serveur (les écrans l'affichent à l'identique).
 * 8 caractères minimum, majuscule, minuscule, chiffre, symbole ; 72 octets au plus (limite BCrypt) ;
 * l'identifiant ne doit pas y figurer.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    /** BCrypt ignore tout ce qui dépasse 72 octets : on refuse plutôt que de tronquer en silence. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** @return null si le mot de passe est conforme, sinon le message à afficher. */
    public static String violation(String password, String login) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Le mot de passe doit contenir au moins " + MIN_LENGTH + " caractères.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "Le mot de passe est trop long (" + MAX_BYTES + " octets au maximum).";
        }
        if (!password.chars().anyMatch(Character::isUpperCase)) return "Ajoutez au moins une majuscule.";
        if (!password.chars().anyMatch(Character::isLowerCase)) return "Ajoutez au moins une minuscule.";
        if (!password.chars().anyMatch(Character::isDigit)) return "Ajoutez au moins un chiffre.";
        if (password.chars().allMatch(Character::isLetterOrDigit)) return "Ajoutez au moins un symbole (@#$%&*…).";
        if (login != null) {
            String l = login.trim().toLowerCase(Locale.ROOT);
            int at = l.indexOf('@');
            if (at > 0) l = l.substring(0, at);
            if (l.length() >= 3 && password.toLowerCase(Locale.ROOT).contains(l)) {
                return "Le mot de passe ne doit pas contenir votre identifiant.";
            }
        }
        return null;
    }
}
