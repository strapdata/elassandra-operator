package com.strapdata.strapkop.ssl.utils;

import io.vavr.control.Option;
import org.bouncycastle.operator.OperatorCreationException;

import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;

public class X509CertificateAndPrivateKey {
    String certs;

    String key;

    public X509CertificateAndPrivateKey() {
    }

    public X509CertificateAndPrivateKey(String certs, String key) {
        this.certs = certs;
        this.key = key;
    }

    public PKCS8EncodedKeySpec getPrivateKey(Option<String> password) throws IOException, GeneralSecurityException {
        return PemConverter.readPrivateKey(key, password);
    }

    public String getPrivateKeyAsString() {
        return key;
    }

    public String getCertificateChainAsString() {
        return certs;
    }

    public List<X509Certificate> getCertificateChain() throws IOException, GeneralSecurityException {
        return PemConverter.readCertificateChain(this.certs);
    }

    public X509Certificate getCertificate() throws IOException, GeneralSecurityException {
        List<X509Certificate> certChain = PemConverter.readCertificateChain(this.certs);
        return certChain.get(certChain.size() - 1);
    }

    public X509TrustManager getX509TrustManager() throws IOException, GeneralSecurityException {
        List<X509Certificate> certChain = PemConverter.readCertificateChain(this.certs);
        return new X509TrustManager() {

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return certChain.toArray(new X509Certificate[certChain.size()]);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] certs, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                boolean match = false;
                try {
                    for (X509Certificate c : chain) {
                        if (c.equals(certChain.get(certChain.size() - 1))) {
                            match = true;
                        }
                    }
                } catch (Exception e) {
                    throw new CertificateException();
                }

                if (!match)
                    throw new CertificateException();
            }
        };
    }

    public X509CertificateAndPrivateKey withPrivateKey(PrivateKey key, char[] password) throws IOException, OperatorCreationException {
        this.key = PemConverter.writePrivateKey(key, password);
        return this;
    }

    public X509CertificateAndPrivateKey withCertificates(List<X509Certificate> certs) throws IOException, CertificateEncodingException {
        this.certs = PemConverter.writeCertificates(certs);
        return this;
    }

}
