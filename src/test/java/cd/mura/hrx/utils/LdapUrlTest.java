package cd.mura.hrx.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LdapUrlTest {

    @Test
    void utiliseLdapsSurLePortStandardSecurise() {
        assertEquals("ldaps://ad.example.local:636", LdapUrl.of(" ad.example.local ", "636"));
    }

    @Test
    void conserveLdapSurLesAutresPorts() {
        assertEquals("ldap://ad.example.local:389", LdapUrl.of("ad.example.local", "389"));
    }
}
