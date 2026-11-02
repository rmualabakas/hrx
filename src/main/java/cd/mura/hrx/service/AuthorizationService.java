package cd.mura.hrx.service;

import cd.mura.hrx.bean.config.LoginBean;
import cd.mura.hrx.dao.config.PrivilegeDAO;
import cd.mura.hrx.entity.config.Privilege;
import cd.mura.hrx.entity.config.Users;
import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Contrôle d'autorisation côté serveur. Les conditions d'affichage JSF restent
 * utiles pour l'ergonomie, mais ne constituent jamais la frontière de sécurité.
 */
@RequestScoped
public class AuthorizationService {

    private static final int ROOT_PROFIL_ID = 1;

    @Inject
    private LoginBean loginBean;

    @EJB
    private PrivilegeDAO privilegeDAO;

    private Set<String> privilegeCodes;

    public Users currentUser() {
        if (loginBean == null || !loginBean.isLogged() || loginBean.getUsers() == null
                || loginBean.getUsers().getId() <= 0) {
            throw new SecurityException("Authentification requise");
        }
        return loginBean.getUsers();
    }

    public boolean isRoot() {
        Users user = currentUser();
        return user.getProfil() != null && user.getProfil().getId() == ROOT_PROFIL_ID;
    }

    public boolean hasPrivilege(String code) {
        if (code == null || code.isBlank()) return false;
        if (isRoot()) return true;
        return loadPrivilegeCodes().contains(code);
    }

    public void requirePrivilege(String code) {
        if (!hasPrivilege(code)) {
            throw new SecurityException("Privilège requis : " + code);
        }
    }

    public void requireAnyPrivilege(String... codes) {
        if (codes != null && Arrays.stream(codes).anyMatch(this::hasPrivilege)) return;
        throw new SecurityException("Privilège insuffisant");
    }

    private Set<String> loadPrivilegeCodes() {
        if (privilegeCodes == null) {
            privilegeCodes = new HashSet<>();
            Users user = currentUser();
            if (user.getProfil() != null) {
                for (Privilege privilege : privilegeDAO.findAllByProfil(user.getProfil())) {
                    if (privilege != null && privilege.getStatut() == 1 && privilege.getCode() != null) {
                        privilegeCodes.add(privilege.getCode());
                    }
                }
            }
        }
        return privilegeCodes;
    }
}
