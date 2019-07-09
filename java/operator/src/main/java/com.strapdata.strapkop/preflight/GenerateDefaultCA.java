package com.strapdata.strapkop.preflight;


import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

/**
 * Generates a CA as secret if not exists
 */
@Singleton
public class GenerateDefaultCA implements Preflight<X509CertificateAndPrivateKey> {

    static final Logger logger = LoggerFactory.getLogger(GenerateDefaultCA.class);
    
    private final AuthorityManager authorityManager;

    public GenerateDefaultCA(final AuthorityManager authorityManager) {
        this.authorityManager = authorityManager;
    }

    @Override
    public X509CertificateAndPrivateKey call() throws Exception {
        X509CertificateAndPrivateKey ca;
        try {
            ca = authorityManager.loadFromSecret();
            logger.info("Operator default CA secret name public/private={}/{} already exists",
                authorityManager.getPublicCaSecretName(), authorityManager.getPrivateCaSecretName());
            return ca;
        }
        catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        logger.info("Generating operator default CA as secret public/private={}/{}",
            authorityManager.getPublicCaSecretName(), authorityManager.getPrivateCaSecretName());

        ca = authorityManager.generate();
        authorityManager.storeAsSecret(ca);
        return ca;
    }
}
