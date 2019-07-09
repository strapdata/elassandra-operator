package com.strapdata.strapkop.ssl;


import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.ssl.utils.CertManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Secret;
import io.vavr.control.Option;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.text.MessageFormat;
import java.util.List;

/**
 * Read/Write the operator CA in a k8s secret.
 * Secret name is defined by PUBLIC_CA_SECRET_NAME / PRIVATE_CA_SECRET_NAME env variable, default=ca
 */
// TODO: move ca password in a secret
@Singleton
public class AuthorityManager {
    private static final Logger logger = LoggerFactory.getLogger(AuthorityManager.class);

    // secret names
    public static final String DEFAULT_PUBLIC_CA_SECRET_NAME = "ca-pub"; // public CA certificate, secret available for all pods
    public static final String DEFAULT_PRIVATE_CA_SECRET_NAME = "ca-key"; // secret for issuing certificates, only for some privileged pods
    public static final String DEFAULT_CA_SECRET_NAMESPACE = "default";

    // secret keys
    public static final String SECRET_CA_KEY = "ca.key";
    public static final String SECRET_CACERT_PEM = "cacert.pem";
    public static final String SECRET_TRUSTSTORE_P12 = "truststore.p12";

    public static final String CA_KEYPASS = "changeit";
    public static final String CA_TRUSTPASS = "changeit";

    @Inject
    private CertManager certManager;
    
    @Inject
    private CoreV1Api coreApi;

    /**
     * Enable K8S API debug if K8S_API_DEBUG env variable is defined
     */
    @PostConstruct
    void init() {
        if (System.getenv("K8S_API_DEBUG") != null)
            coreApi.getApiClient().setDebugging(true);
    }

    /**
     * CA secret with public certificate, mounted by all pods
     * @return
     */
    public String getPublicCaSecretName() {
        String caSecretName = System.getenv("PUBLIC_CA_SECRET_NAME");
        if (caSecretName == null)
            caSecretName = AuthorityManager.DEFAULT_PUBLIC_CA_SECRET_NAME;
        return caSecretName;
    }

    /**
     * Private CA secret with CA private key, only mounted by the operator
     * @return
     */
    public String getPrivateCaSecretName() {
        String caSecretName = System.getenv("PRIVATE_CA_SECRET_NAME");
        if (caSecretName == null)
            caSecretName = AuthorityManager.DEFAULT_PRIVATE_CA_SECRET_NAME;
        return caSecretName;
    }

    /**
     * Store CA in 2 secrets, a public one with CA Cert, a private one with CA private key.
     * @param ca
     * @throws ApiException
     * @throws GeneralSecurityException
     * @throws IOException
     * @throws OperatorCreationException
     */
    public void storeAsSecret(X509CertificateAndPrivateKey ca) throws ApiException, GeneralSecurityException, IOException, OperatorCreationException {
        final V1Secret publicSecret = new V1Secret()
                .metadata(new V1ObjectMeta()
                        .name(getPublicCaSecretName())
                        .namespace(DEFAULT_CA_SECRET_NAMESPACE)
                        .labels(OperatorLabels.MANAGED))
                .putStringDataItem(SECRET_CACERT_PEM, ca.getCertificateChainAsString())
                .putDataItem(SECRET_TRUSTSTORE_P12, certManager.generateTruststoreBytes(ca, CA_TRUSTPASS));
        logger.info("Storing public CA in secret {} in namespace {} secret={}", getPublicCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE, publicSecret);
        coreApi.createNamespacedSecret(DEFAULT_CA_SECRET_NAMESPACE, publicSecret, null, null, null);

        final V1Secret privateSecret = new V1Secret()
            .metadata(new V1ObjectMeta()
                .name(getPrivateCaSecretName())
                .labels(OperatorLabels.MANAGED))
            .putStringDataItem(SECRET_CA_KEY, ca.getPrivateKeyAsString());
        logger.info("Storing private CA in secret {} in namespace {}", getPrivateCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE);
        coreApi.createNamespacedSecret(DEFAULT_CA_SECRET_NAMESPACE, privateSecret, null, null, null);
    }

    /**
     * Load a CA key and certs from a kubernetes secret
     * @return the CA key and certs
     * @throws ApiException 404 if it does not exists
     */
    public X509CertificateAndPrivateKey loadFromSecret() throws Exception {
        V1Secret publicSecret = coreApi.readNamespacedSecret(getPublicCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE, null, null, null);
        final byte[] certsBytes = publicSecret.getData().get(SECRET_CACERT_PEM);
        if (certsBytes == null) {
            throw new Exception(MessageFormat.format("missing file {} from secret {} in namespace {}", SECRET_CACERT_PEM, getPublicCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE));
        }
        final String certs = new String(certsBytes);

        V1Secret privateSecret = coreApi.readNamespacedSecret(getPrivateCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE, null, null, null);
        final byte[] keyBytes = privateSecret.getData().get(SECRET_CA_KEY);
        if (keyBytes == null) {
            throw new Exception(MessageFormat.format("missing file {} from secret {} in namespace {}", SECRET_CA_KEY, getPrivateCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE));
        }
        final String key = new String(keyBytes);
        
        return new X509CertificateAndPrivateKey(certs, key);
    }
    
    /**
     * Generate a new CA key and certs
     */
    public X509CertificateAndPrivateKey generate() throws OperatorCreationException, CertificateException, SignatureException, NoSuchAlgorithmException, IOException {
        logger.info("Generating operator root ca");
        return certManager.generateCa("AutoGeneratedRootCA", CA_KEYPASS.toCharArray());
    }

    /**
     * Issue a child certificate and private key in a PKC12 keystore protected by the provided password.
     * @param cn
     * @param dnsNames
     * @param ipAddresses
     * @param alias
     * @param password
     * @return
     * @throws Exception
     */
    public byte[] issueCertificateKeystore(String cn,
                                           List<String> dnsNames,
                                           List<InetAddress> ipAddresses,
                                           String alias,
                                           String password) throws Exception {
        return certManager.generateClientKeystoreBytes(
            loadFromSecret(),
            Option.of(CA_KEYPASS),
            cn,
            dnsNames,
            ipAddresses,
            alias,
            password);
    }


}
