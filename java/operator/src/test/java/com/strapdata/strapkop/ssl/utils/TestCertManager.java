/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

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
        // GCP & AZ root CA are now added to the Cacert of the sidecar container.
        // there are useless in the Generated Truststore
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
        assertEquals(1, nbOfAliases);
    }
}
