package com.strapdata.strapkop.ssl;

import com.strapdata.strapkop.ssl.utils.CertManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Secret;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

@Singleton
public class SecretIssuer {
    
    static final Logger logger = LoggerFactory.getLogger(SecretIssuer.class);

    @Inject
    private CertManager certManager;
    
    @Inject
    private AuthorityManager authorityManager;
    
    /**
     * Issue a certificate as a secret containing cassandra keystore and truststore plus CA pem certificate
     * @param target the metadata of the secret
     * @param attributes the attributes of the certificates to be issued
     * @return the kubernetes secret
     */
    public V1Secret issue(V1ObjectMeta target, final Attributes attributes) throws Exception {
        
        final X509CertificateAndPrivateKey ca = authorityManager.loadFromSecret(attributes.getCaSecretName(), attributes.getCaSecretNamespace());
        
        final byte[] keystoreBytes = bytesBufferToArray(certManager.generateClientKeystoreByteBuffer(
                ca,
                attributes.getCommonName(),
                attributes.getDomains(),
                attributes.getAddresses(),
                attributes.getAlias(),
                attributes.getPassword()));
        
        final byte[] truststoreBytes = bytesBufferToArray(certManager.generateTruststoreByteBuffer(ca, attributes.getPassword()));
        
        return new V1Secret()
                .metadata(target)
                .putDataItem("keystore.p12", keystoreBytes)
                .putDataItem("truststore.p12", truststoreBytes)
                .putStringDataItem("cacert.pem", ca.getCertificateChainAsString());
    }
    
    
    private static byte[] bytesBufferToArray(ByteBuffer buffer) {
        final byte[] array = new byte[buffer.remaining()];
        buffer.get(array);
        return array;
    }
    
    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    @Builder
    public static class Attributes {
        private String caSecretName;
        private String caSecretNamespace;
        private String commonName;
        private List<String> domains;
        private List<InetAddress> addresses;
        private String alias;
        private String password;
    }
}
