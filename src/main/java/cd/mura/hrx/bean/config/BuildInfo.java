package cd.mura.hrx.bean.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/** Identifiant du déploiement courant, utilisé pour versionner les URL des CSS/JS (cache navigateur). */
@Named
@ApplicationScoped
public class BuildInfo {

    private final String version = Long.toString(System.currentTimeMillis(), 36);

    public String getVersion() {
        return version;
    }
}
