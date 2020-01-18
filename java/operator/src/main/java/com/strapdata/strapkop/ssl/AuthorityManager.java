package com.strapdata.strapkop.ssl;


import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.ssl.utils.CertManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Secret;
import io.micronaut.cache.annotation.CacheConfig;
import io.micronaut.caffeine.cache.AsyncLoadingCache;
import io.micronaut.caffeine.cache.Caffeine;
import io.reactivex.Single;
import io.vavr.Tuple2;
import io.vavr.control.Option;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Read/Write the operator CA in a k8s secret.
 * Secret name is defined by PUBLIC_CA_SECRET_NAME / PRIVATE_CA_SECRET_NAME env variable, default=ca
 */
// TODO: move ca password in a secret
@Singleton
@CacheConfig("ca-cache")
public class AuthorityManager {
    private static final Logger logger = LoggerFactory.getLogger(AuthorityManager.class);

    // secret names
    public static final String DEFAULT_PUBLIC_CA_SECRET_NAME = "ca-pub"; // public CA certificate, secret available for all pods
    public static final String DEFAULT_PUBLIC_CA_MOUNT_PATH = "/tmp/operator-truststore"; // public CA certificate mount path

    public static final String DEFAULT_PRIVATE_CA_SECRET_NAME = "ca-key"; // secret for issuing certificates, only for some privileged pods

    // secret keys
    public static final String SECRET_CA_KEY = "ca.key";
    public static final String SECRET_CACERT_PEM = "cacert.pem";
    public static final String SECRET_TRUSTSTORE_P12 = "truststore.p12";

    private static final String CA_KEYPASS = "changeit";
    private static final String CA_TRUSTPASS = "changeit";

    @Inject
    private CertManager certManager;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    private CoreV1Api coreApi;

    AsyncLoadingCache<String, X509CertificateAndPrivateKey> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .buildAsync(ns -> loadOrGenerateCa(ns).blockingGet());

    public X509CertificateAndPrivateKey get(String namespace) throws ExecutionException, InterruptedException {
        return getAsync(namespace).get();
    }

    public CompletableFuture<X509CertificateAndPrivateKey> getAsync(String namespace)  {
        logger.debug("Get CA for namespace={}", namespace);
        return cache.get(namespace);
    }

    public Single<X509CertificateAndPrivateKey> getSingle(String namespace) {
        return Single.fromFuture(getAsync(namespace));
    }

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
    public Single<X509CertificateAndPrivateKey> storeAsSecret(String namespace, X509CertificateAndPrivateKey ca) throws ApiException, GeneralSecurityException, IOException, OperatorCreationException {
        final V1Secret publicSecret = new V1Secret()
                .metadata(new V1ObjectMeta()
                        .name(getPublicCaSecretName())
                        .namespace(namespace)
                        .labels(OperatorLabels.MANAGED))
                .putStringDataItem(SECRET_CACERT_PEM, ca.getCertificateChainAsString())
                .putDataItem(SECRET_TRUSTSTORE_P12, certManager.generateTruststoreBytes(ca, getCaTrustPass()));
        logger.info("Storing public CA in secret {} in namespace {} secret={}", getPublicCaSecretName(), namespace, publicSecret);
        return k8sResourceUtils.createNamespacedSecret(publicSecret)
                .flatMap(s -> {
                    final V1Secret privateSecret = new V1Secret()
                            .metadata(new V1ObjectMeta()
                                    .name(getPrivateCaSecretName())
                                    .namespace(namespace)
                                    .labels(OperatorLabels.MANAGED))
                            .putStringDataItem(SECRET_CA_KEY, ca.getPrivateKeyAsString());
                    logger.info("Storing private CA in secret {} in namespace {}", getPrivateCaSecretName(), namespace);
                    return k8sResourceUtils.createNamespacedSecret(privateSecret).map(s2 -> ca);
                });
    }

    
    private Single<X509CertificateAndPrivateKey> loadOrGenerateCa(String namespace) {
        return k8sResourceUtils.readNamespacedSecret(namespace, getPublicCaSecretName())
                .flatMap(caPub -> k8sResourceUtils.readNamespacedSecret(namespace, getPrivateCaSecretName()).map(caKey -> new Tuple2<>(caPub, caKey)))
                .flatMap(tuple -> {
                    X509CertificateAndPrivateKey ca;
                    final byte[] certsBytes = tuple._1.getData().get(SECRET_CACERT_PEM);
                    final byte[] key = tuple._2.getData().get(SECRET_CA_KEY);
                    if (certsBytes == null || key == null) {
                        logger.info("Generating operator root ca for namespace={}", namespace);
                        ca = certManager.generateCa("AutoGeneratedRootCA", getCaKeyPass().toCharArray());
                        return storeAsSecret(namespace, ca);
                    } else {
                        return Single.just(new X509CertificateAndPrivateKey(new String(certsBytes), new String(key)));
                    }
                });
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
