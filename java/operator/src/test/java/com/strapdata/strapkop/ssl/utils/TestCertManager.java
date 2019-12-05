package com.strapdata.strapkop.ssl.utils;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class TestCertManager {

    public static final String PASSWORD = "changeit";
    public static final String TEST_STRAPKOP = "TEST-STRAPKOP";

    @Test
    public void testGenerateCert() throws Exception {
        String expectedAlias = "c=fr,o=strapdata,ou=elassandraoperator,cn=test-strapkop";

        CertManager certMng = new CertManager();
        X509CertificateAndPrivateKey certAndPk = certMng.generateCa(TEST_STRAPKOP, "changeit".toCharArray());
        byte[] truststore = certMng.generateTruststoreBytes(certAndPk, PASSWORD);

        // Create an instance of KeyStore using the generated p12
        // and check if GCP & AZ are present in addition of the TEST-STRAPKOP cert
        boolean foundAlias = false;
        int nbOfAliases = 0;
        KeyStore keyStore = KeyStore.getInstance("pkcs12");
        keyStore.load(new ByteArrayInputStream(truststore), PASSWORD.toCharArray());
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            System.out.println("Found:[" + a + "]");
            if (expectedAlias.equals(a)) {
                foundAlias = true;
            }
            nbOfAliases++;
        }
        assertTrue(foundAlias);
        assertTrue(nbOfAliases > 2);
    }
}
