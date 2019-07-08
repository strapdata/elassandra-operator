package com.strapdata.strapkop.preflight;


import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
public class GenerateDefaultCA implements Preflight<X509CertificateAndPrivateKey> {
    static final Logger logger = LoggerFactory.getLogger(GenerateDefaultCA.class);
    
    private final AuthorityManager authorityManager;
    private final String namespace;

    public GenerateDefaultCA(final AuthorityManager authorityManager) {
        this(authorityManager, "default");
    }

    public GenerateDefaultCA(final AuthorityManager authorityManager,
                             final String namespace) {
        this.authorityManager = authorityManager;
        this.namespace = namespace;
    }

    @Override
    public X509CertificateAndPrivateKey call() throws Exception {
        String caSecretName = System.getenv("CA_SECRET_NAME");
        if (caSecretName == null)
            caSecretName = AuthorityManager.DEFAULT_SECRET_NAME;

        X509CertificateAndPrivateKey ca;
        try {
            ca = authorityManager.loadFromSecret(caSecretName, namespace);
            logger.info("Operator default CA secret name={} already exists", caSecretName);
            return ca;
        }
        catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        logger.info("Generating operator default CA as secret name={}", caSecretName);

        ca = authorityManager.generate();
        authorityManager.storeAsSecret(caSecretName, namespace, ca);
        return ca;
    }
}
