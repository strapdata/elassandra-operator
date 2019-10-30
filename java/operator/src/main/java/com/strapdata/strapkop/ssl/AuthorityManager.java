package com.strapdata.strapkop.ssl;


import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.ssl.utils.CertManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Secret;
import io.reactivex.Single;
import io.vavr.control.Option;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.Callable;

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
    public static final String DEFAULT_PUBLIC_CA_MOUNT_PATH = "/tmp/operator-truststore"; // public CA certificate mount path

    public static final String DEFAULT_PRIVATE_CA_SECRET_NAME = "ca-key"; // secret for issuing certificates, only for some privileged pods
    public static final String DEFAULT_CA_SECRET_NAMESPACE = "default";

    // secret keys
    public static final String SECRET_CA_KEY = "ca.key";
    public static final String SECRET_CACERT_PEM = "cacert.pem";
    public static final String SECRET_TRUSTSTORE_P12 = "truststore.p12";

    private static final String CA_KEYPASS = "changeit";
    private static final String CA_TRUSTPASS = "changeit";

    @Inject
    private CertManager certManager;

    @Inject
    private CoreV1Api coreApi;

    /**
     * CA secret with public certificate, mounted by all pods
     * @return
     */
    public String getPublicCaSecretName() {
        String caSecretName = System.getenv("PUBLIC_CA_SECRET_NAME");
        return (caSecretName == null) ? AuthorityManager.DEFAULT_PUBLIC_CA_SECRET_NAME : caSecretName;
    }

    /**
     * Public CA files mount path where cacert.pem + truststore.p12 will be visible from pods.
     * @return
     */
    public String getPublicCaMountPath() {
        String caMountPath = System.getenv("PUBLIC_CA_MOUNT_PATH");
        return (caMountPath == null) ? AuthorityManager.DEFAULT_PUBLIC_CA_MOUNT_PATH : caMountPath;
    }

    /**
     * Private CA secret with CA private key, only mounted by the operator
     * @return
     */
    public String getPrivateCaSecretName() {
        String caSecretName = System.getenv("PRIVATE_CA_SECRET_NAME");
        return (caSecretName == null) ? AuthorityManager.DEFAULT_PRIVATE_CA_SECRET_NAME : caSecretName;
    }

    public String getCaKeyPass() {
        String password = System.getenv("CA_KEYPASS");
        return (password == null) ? AuthorityManager.CA_KEYPASS : password;
    }

    public String getCaTrustPass() {
        String password = System.getenv("CA_TRUSTPASS");
        return (password == null) ? AuthorityManager.CA_TRUSTPASS : password;
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
                .putDataItem(SECRET_TRUSTSTORE_P12, certManager.generateTruststoreBytes(ca, getCaTrustPass()));
        logger.info("Storing public CA in secret {} in namespace {} secret={}", getPublicCaSecretName(), DEFAULT_CA_SECRET_NAMESPACE, publicSecret);
        coreApi.createNamespacedSecret(DEFAULT_CA_SECRET_NAMESPACE, publicSecret, null, null, null);

        final V1Secret privateSecret = new V1Secret()
                .metadata(new V1ObjectMeta()
                        .name(getPrivateCaSecretName())
                        .namespace(DEFAULT_CA_SECRET_NAMESPACE)
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
    public X509CertificateAndPrivateKey loadFromSecret() throws StrapkopException, ApiException {
        final String certs = loadPublicCaFromSecret();
        final String key = loadPrivateCaFromSecret();
    
        return new X509CertificateAndPrivateKey(certs, key);
    }

    public Single<X509CertificateAndPrivateKey> loadFromSecretSingle() {
        return Single.fromCallable(new Callable<X509CertificateAndPrivateKey>() {
            @Override
            public X509CertificateAndPrivateKey call() throws Exception {
                return loadFromSecret();
            }
        });
    }
    
    public String loadPrivateCaFromSecret() throws StrapkopException, ApiException {
        return loadItemFromSecret(getPrivateCaSecretName(), SECRET_CA_KEY);
    }
    
    public String loadPublicCaFromSecret() throws StrapkopException, ApiException {
        return loadItemFromSecret(getPublicCaSecretName(), SECRET_CACERT_PEM);
    }
    
    private String loadItemFromSecret(String publicCaSecretName, String secretCacertPem) throws StrapkopException, ApiException {
        V1Secret publicSecret = coreApi.readNamespacedSecret(publicCaSecretName, DEFAULT_CA_SECRET_NAMESPACE, null, null, null);
        final byte[] certsBytes = publicSecret.getData().get(secretCacertPem);
        if (certsBytes == null) {
            throw new StrapkopException(MessageFormat.format("missing file {} from secret {} in namespace {}", secretCacertPem, publicCaSecretName, DEFAULT_CA_SECRET_NAMESPACE));
        }
        return new String(certsBytes);
    }
    
    /**
     * Generate a new CA key and certs
     */
    public X509CertificateAndPrivateKey generate() throws OperatorCreationException, CertificateException, SignatureException, NoSuchAlgorithmException, IOException {
        logger.info("Generating operator root ca");
        return certManager.generateCa("AutoGeneratedRootCA", getCaKeyPass().toCharArray());
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
    public byte[] issueCertificateKeystore(X509CertificateAndPrivateKey x509CertificateAndPrivateKey,
                                           String cn,
                                           List<String> dnsNames,
                                           List<InetAddress> ipAddresses,
                                           String alias,
                                           String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        return certManager.generateClientKeystoreBytes(
                x509CertificateAndPrivateKey,
            Option.of(getCaKeyPass()),
            cn,
            dnsNames,
            ipAddresses,
            alias,
            password);
    }


}
