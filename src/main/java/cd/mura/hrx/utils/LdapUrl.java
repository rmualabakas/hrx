package cd.mura.hrx.utils;

/** Construction homogène des URL LDAP ; le port standard 636 active LDAPS. */
public final class LdapUrl {
    private LdapUrl() { }

    public static String of(String server, String port) {
        String host = server == null ? "" : server.trim();
        String normalizedPort = port == null ? "" : port.trim();
        String scheme = "636".equals(normalizedPort) ? "ldaps" : "ldap";
        return scheme + "://" + host + ":" + normalizedPort;
    }
}
