package com.strapdata.strapkop.ssl.utils;

public class CertManagerConfiguration {
    
    public String keyAlgorithm = "RSA";
    public String signatureAlgorithm = "SHA256withRSA";
    public String storetype = "PKCS12";
    
    public int caKeysize = 2048;
    public int caValidityInDays = 365 * 5; // 5 years
    public String caSubjectSuffix = "OU=ElassandraOperator, O=Strapdata, C=FR";
    
    public int keysize = 1024;
    public int validityInDays = 1096; // 3 years
    public String subjectSuffix = "OU=Elassandra Operator, O=Strapdata, C=FR";
}
