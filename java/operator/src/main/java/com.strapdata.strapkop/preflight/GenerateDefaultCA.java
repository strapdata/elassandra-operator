package com.strapdata.strapkop.preflight;


import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class GenerateDefaultCA implements Callable<X509CertificateAndPrivateKey> {
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
        X509CertificateAndPrivateKey ca;
        try {
            ca = authorityManager.loadFromSecret(AuthorityManager.DEFAULT_SECRET_NAME, namespace);
            logger.info("Operator default CA already exists");
            return ca;
        }
        catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        logger.info("Generating operator default CA");

        ca = authorityManager.generate();
        authorityManager.storeAsSecret(AuthorityManager.DEFAULT_SECRET_NAME, namespace, ca);
        return ca;
    }
}