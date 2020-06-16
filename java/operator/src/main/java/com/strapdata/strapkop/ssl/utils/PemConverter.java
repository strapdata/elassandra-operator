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

import io.vavr.control.Option;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.util.io.pem.PemObject;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.security.auth.x500.X500Principal;
import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.nio.CharBuffer;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static javax.crypto.Cipher.DECRYPT_MODE;

public class PemConverter {
    private static final Pattern CERT_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                    // Base64 text
                    "-+END\\s+.*CERTIFICATE[^-]*-+",            // Footer
            CASE_INSENSITIVE);
    
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                    "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                    "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
            CASE_INSENSITIVE);
    
    private PemConverter() {
    }
    
    public static KeyStore loadTrustStore(File certificateChainFile)
            throws IOException, GeneralSecurityException {
        return loadTrustStore(readCertificateChain(certificateChainFile));
    }
    
    public static KeyStore loadTrustStore(List<X509Certificate> certificateChain)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        
        for (X509Certificate certificate : certificateChain) {
            X500Principal principal = certificate.getSubjectX500Principal();
            keyStore.setCertificateEntry(principal.getName("RFC2253"), certificate);
        }
        return keyStore;
    }
    
    public static KeyStore loadKeyStore(File certificateChainFile, File privateKeyFile, Option<String> keyPassword)
            throws IOException, GeneralSecurityException {
        PKCS8EncodedKeySpec encodedKeySpec = readPrivateKey(privateKeyFile, keyPassword);
        PrivateKey key;
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            key = keyFactory.generatePrivate(encodedKeySpec);
        } catch (InvalidKeySpecException ignore) {
            KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            key = keyFactory.generatePrivate(encodedKeySpec);
        }
        
        List<X509Certificate> certificateChain = readCertificateChain(certificateChainFile);
        if (certificateChain.isEmpty()) {
            throw new CertificateException("Certificate file does not contain any certificates: " + certificateChainFile);
        }
        
        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        keyStore.setKeyEntry("key", key, keyPassword.orElse(Option.of("")).get().toCharArray(), certificateChain.stream().toArray(Certificate[]::new));
        return keyStore;
    }
    
    public static List<X509Certificate> readCertificateChain(File certificateChainFile)
            throws IOException, GeneralSecurityException {
        String contents = readFile(certificateChainFile);
        return readCertificateChain(contents);
    }
    
    
    public static List<X509Certificate> readCertificateChain(String contents)
            throws IOException, GeneralSecurityException {
        Matcher matcher = CERT_PATTERN.matcher(contents);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        
        int start = 0;
        while (matcher.find(start)) {
            byte[] buffer = base64Decode(matcher.group(1));
            certificates.add((X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(buffer)));
            start = matcher.end();
        }
        
        return certificates;
    }
    
    public static PKCS8EncodedKeySpec readPrivateKey(File keyFile, Option<String> keyPassword)
            throws IOException, GeneralSecurityException {
        String content = readFile(keyFile);
        return readPrivateKey(content, keyPassword);
    }
    
    public static PKCS8EncodedKeySpec readPrivateKey(String content, Option<String> keyPassword)
            throws IOException, GeneralSecurityException {
        Matcher matcher = KEY_PATTERN.matcher(content);
        if (!matcher.find()) {
            throw new KeyStoreException("found no private key");
        }
        byte[] encodedKey = base64Decode(matcher.group(1));
        
        if (!keyPassword.isDefined()) {
            return new PKCS8EncodedKeySpec(encodedKey);
        }
        
        EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(encodedKey);
        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
        SecretKey secretKey = keyFactory.generateSecret(new PBEKeySpec(keyPassword.get().toCharArray()));
        
        Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
        cipher.init(DECRYPT_MODE, secretKey, encryptedPrivateKeyInfo.getAlgParameters());
        
        return encryptedPrivateKeyInfo.getKeySpec(cipher);
    }
    
    public static byte[] base64Decode(String base64) {
        return Base64.getMimeDecoder().decode(base64.getBytes(US_ASCII));
    }
    
    public static String readFile(File file)
            throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), US_ASCII)) {
            StringBuilder stringBuilder = new StringBuilder();
            
            CharBuffer buffer = CharBuffer.allocate(2048);
            while (reader.read(buffer) != -1) {
                buffer.flip();
                stringBuilder.append(buffer);
                buffer.clear();
            }
            return stringBuilder.toString();
        }
    }
    
    public static String writeCertificate(X509Certificate certificate) throws IOException, CertificateEncodingException {
        StringBuilder encodedChain = new StringBuilder();
        StringWriter sw = new StringWriter();
        encodedChain.append("-----BEGIN CERTIFICATE-----\n");
        encodedChain.append(DatatypeConverter.printBase64Binary(certificate.getEncoded()).replaceAll("(.{64})", "$1\n"));
        encodedChain.append("\n-----END CERTIFICATE-----\n");
        return encodedChain.toString();
    }
    
    public static String writeCertificates(List<X509Certificate> certificateChain) throws IOException, CertificateEncodingException {
        StringBuilder encodedChain = new StringBuilder();
        for (X509Certificate certificate : certificateChain) {
            encodedChain.append("-----BEGIN CERTIFICATE-----\n");
            encodedChain.append(DatatypeConverter.printBase64Binary(certificate.getEncoded()).replaceAll("(.{64})", "$1\n"));
            encodedChain.append("\n-----END CERTIFICATE-----\n");
        }
        return encodedChain.toString();
    }
    
    public static String writePrivateKey(PrivateKey key, char[] password) throws IOException, OperatorCreationException {
        JcaPKCS8Generator pkcs8;
        if (password != null) {
            JceOpenSSLPKCS8EncryptorBuilder encryptorBuilder = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.PBE_SHA1_RC2_128);
            encryptorBuilder.setRandom(new SecureRandom());
            encryptorBuilder.setPasssword(password); // password
            OutputEncryptor encryptor = encryptorBuilder.build();
            pkcs8 = new JcaPKCS8Generator(key, encryptor);
        } else {
            pkcs8 = new JcaPKCS8Generator(key, null);
        }
        
        PemObject obj1 = pkcs8.generate();
        StringWriter sw1 = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw1)) {
            pw.writeObject(obj1);
        }
        return sw1.toString();
    }
}
