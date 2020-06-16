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
