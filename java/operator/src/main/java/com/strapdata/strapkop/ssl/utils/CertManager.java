package com.strapdata.strapkop.ssl.utils;

import io.vavr.control.Option;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.X509KeyUsage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import javax.inject.Singleton;
import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.*;

@Singleton
public class CertManager {
    
    private CertManagerConfiguration config;
    private SecureRandom prng;
    
    public CertManager() {
        this(new CertManagerConfiguration());
    }
    
    public CertManager(CertManagerConfiguration config) {
        this.config = config;
        this.prng = new SecureRandom();
    }
    
    public X509CertificateAndPrivateKey generateCa(String projectName, char[] password) throws NoSuchAlgorithmException, IOException, CertificateException, SignatureException, OperatorCreationException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(config.keyAlgorithm);
        keyGen.initialize(config.caKeysize, this.prng);
        KeyPair keyPair = keyGen.genKeyPair();
        
        Calendar cal = GregorianCalendar.getInstance();
        cal.add(Calendar.SECOND, -1);
        Date notBefore = cal.getTime();
        
        cal.add(Calendar.HOUR, config.caValidityInDays * 24);
        Date notAfter = cal.getTime();
        
        ContentSigner contentSigner = new JcaContentSignerBuilder(config.signatureAlgorithm).build(keyPair.getPrivate());
        
        X500Name dnName = new X500Name("cn=" + projectName + ", " + config.caSubjectSuffix);
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(dnName, getSerial(), notBefore, notAfter, dnName, keyPair.getPublic());
        
        // Extensions --------------------------
        
        // Basic Constraints
        BasicConstraints basicConstraints = new BasicConstraints(true); // <-- true for CA, false for EndEntity
        certBuilder.addExtension(new ASN1ObjectIdentifier("2.5.29.19"), true, basicConstraints); // Basic Constraints is usually marked as critical.
        
        // -------------------------------------
        Provider bcProvider = new BouncyCastleProvider();
        X509Certificate caCert = new JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner));

        return new X509CertificateAndPrivateKey()
                .withCertificates(Collections.singletonList(caCert))
                .withPrivateKey(keyPair.getPrivate(), password);
    }
    
    @SuppressWarnings("deprecation")
    private Pair<X509Certificate, PrivateKey> generateCertificate(X509CertificateAndPrivateKey caCertAndKey, Option<String> caPassword, X500Name subject, BigInteger serial, List<String> dnsNames, List<InetAddress> addresses) throws IOException, GeneralSecurityException, OperatorCreationException {
        Calendar cal = GregorianCalendar.getInstance();
        cal.add(Calendar.SECOND, -1);
        Date notBefore = cal.getTime();
        
        cal.add(Calendar.HOUR, config.validityInDays * 24);
        Date notAfter = cal.getTime();
        
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(config.keyAlgorithm);
        keyPairGenerator.initialize(config.keysize);
        KeyPair keyPair = keyPairGenerator.genKeyPair();
        
        X509Certificate caCert = caCertAndKey.getCertificate();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(caCert, serial, notBefore, notAfter, subject, keyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new X509KeyUsage(
                        X509KeyUsage.digitalSignature |
                                X509KeyUsage.keyEncipherment));
        
        Vector<KeyPurposeId> extendedKeyUsages = new Vector<>();
        extendedKeyUsages.add(KeyPurposeId.id_kp_clientAuth);
        extendedKeyUsages.add(KeyPurposeId.id_kp_serverAuth);
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(extendedKeyUsages));
        
        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(caCert.getPublicKey()));
        
        List<GeneralName> altNames = new ArrayList<>();
        if (dnsNames != null)
            for (String dnsName : dnsNames)
                altNames.add(new GeneralName(GeneralName.dNSName, dnsName));
        if (addresses != null)
            for (InetAddress addr : addresses)
                altNames.add(new GeneralName(GeneralName.iPAddress, addr.getHostAddress()));
        if (altNames.size() > 0) {
            GeneralNames subjectAltName = new GeneralNames(altNames.toArray(new GeneralName[altNames.size()]));
            builder.addExtension(Extension.subjectAlternativeName, false, subjectAltName);
        }
        
        KeyFactory kf = KeyFactory.getInstance(config.keyAlgorithm);
        PrivateKey privKey = kf.generatePrivate(caCertAndKey.getPrivateKey(caPassword));
        
        ContentSigner signer = new JcaContentSignerBuilder(config.signatureAlgorithm).build(privKey);
        X509CertificateHolder holder = builder.build(signer);
        
        List<X509Certificate> newChain = new ArrayList<>();
        newChain.addAll(caCertAndKey.getCertificateChain());
        newChain.add(new JcaX509CertificateConverter().getCertificate(holder));
        
        return Pair.create(new JcaX509CertificateConverter().getCertificate(holder), keyPair.getPrivate());
    }
    
    public ByteBuffer generateClientKeystoreByteBuffer(X509CertificateAndPrivateKey caCertAndKey, Option<String> caPassword, String projectName, String alias, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        return generateClientKeystoreByteBuffer(caCertAndKey, caPassword, projectName, Collections.emptyList(), Collections.emptyList(), alias, password);
    }

    public byte[] generateClientKeystoreBytes(X509CertificateAndPrivateKey caCertAndKey, Option<String> caPassword, String projectName, List<String> dnsNames, List<InetAddress> addresses, String alias, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        KeyStore keyStore = generateClientKeystore(caCertAndKey, caPassword, projectName, dnsNames, addresses, alias, password);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            keyStore.store(baos, password.toCharArray());
            return baos.toByteArray();
        }
    }

    public ByteBuffer generateClientKeystoreByteBuffer(X509CertificateAndPrivateKey caCertAndKey, Option<String> caPassword, String projectName, List<String> dnsNames, List<InetAddress> addresses, String alias, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        return ByteBuffer.wrap(generateClientKeystoreBytes(caCertAndKey, caPassword, projectName, dnsNames, addresses, alias, password));
    }

    public KeyStore generateClientKeystore(X509CertificateAndPrivateKey caCertAndKey, Option<String> caPassword, String projectName, List<String> dnsNames, List<InetAddress> addresses, String alias, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        X500Name subject = new X500Name("cn=" + projectName + ", " + config.subjectSuffix);
        Pair<X509Certificate, PrivateKey> pair = generateCertificate(caCertAndKey, caPassword, subject, getSerial(), dnsNames, addresses);
        return generateKeystore(caCertAndKey.getCertificateChain(), pair.left, pair.right, alias, password);
    }
    
    private KeyStore generateKeystore(Collection<? extends Certificate> caChain, X509Certificate issuedCertificate, PrivateKey issuedPrivateKey, String alias, String keypass) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException, CertStoreException, InvalidKeyException, NoSuchProviderException, SignatureException {
        Certificate caCert = null;
        Certificate[] entryChain = new Certificate[caChain.size() + 1];
        entryChain[0] = issuedCertificate;
        int i = 1;
        for (Certificate c : caChain) {
            entryChain[i++] = c;
            caCert = c; // last certificate in caChain
        }
        
        // verify signature
        issuedCertificate.verify(caCert.getPublicKey());
        
        KeyStore store = KeyStore.getInstance(config.storetype);
        store.load(null, null);
        store.setKeyEntry(alias, issuedPrivateKey, keypass.toCharArray(), entryChain);
        store.setCertificateEntry("ca", caCert);
        return store;
    }
    
    private static PKCS10CertificationRequest buildCSR(KeyPair keypair, String subject, String csrpass, String sigalg) throws OperatorCreationException {
        X500Principal entitySubject = new X500Principal(subject);
        PublicKey entityPubKey = keypair.getPublic();
        
        PKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(entitySubject, entityPubKey);
        
        // add challange password attribute
        if (csrpass != null) {
            DERPrintableString password = new DERPrintableString(csrpass);
            csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_challengePassword, password);
        }
        
        // sign CSR
        PrivateKey entityPrivKey = keypair.getPrivate();
        JcaContentSignerBuilder csrSignerBuilder = new JcaContentSignerBuilder(sigalg);
        ContentSigner csrSigner = csrSignerBuilder.build(entityPrivKey);
        return csrBuilder.build(csrSigner);
    }
    
    private BigInteger getSerial() {
        Random rnd = new Random(System.currentTimeMillis());
        return BigInteger.valueOf(Math.abs(rnd.nextLong()) + 1);
    }

    public byte[] generateTruststoreBytes(X509CertificateAndPrivateKey caCertAndKey, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        KeyStore keyStore = PemConverter.loadTrustStore(caCertAndKey.getCertificateChain());

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final Collection<? extends Certificate> gcpCertificate = cf.generateCertificates(getClass().getClassLoader().getResourceAsStream("cacert/cacert.pem"));
        for (Certificate cert: gcpCertificate) {
            if (cert instanceof X509Certificate) {
                keyStore.setCertificateEntry(((X509Certificate) cert).getSubjectX500Principal().toString(), cert);
            }
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            keyStore.store(baos, password.toCharArray());
            return baos.toByteArray();
        }
    }

    public ByteBuffer generateTruststoreByteBuffer(X509CertificateAndPrivateKey caCertAndKey, String password) throws GeneralSecurityException, IOException, OperatorCreationException {
        return ByteBuffer.wrap(generateTruststoreBytes(caCertAndKey, password));
    }

}


