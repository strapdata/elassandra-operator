package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.strapdata.elasticsearch.plugin.license.License;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@With
@Getter
@Setter
public class CqlLicense extends License implements CqlReconciliable {

    boolean reconcilied = false;

    @Override
    public boolean reconcilied() { return reconcilied; }

    public static License fromRow(Row row) {
        License lic = new CqlLicense();

        lic.setId(row.getUUID("id"));
        lic.setIssuer(row.getString("issuer"));
        lic.setCompany(row.getString("company"));
        lic.setEmail(row.getString("email"));
        lic.setType(Type.fromString(row.getString("type")));
        lic.setProduction(row.getBool("production"));

        List<Feature> features = new ArrayList<>();
        for(String f : row.getList("features", String.class)) {
            features.add(Feature.fromString(f));
        }
        lic.setFeatures(features);

        lic.setGenerated(row.getTimestamp("generated"));
        lic.setStart(row.getTimestamp("start"));
        lic.setExpire(row.getTimestamp("expire"));

        lic.setDatacenters(row.getList("datacenters", String.class));
        lic.setClustername(row.getString("clustername"));

        lic.setMaxnodes(row.getInt("maxnodes"));

        lic.setBinarySignature(row.getBytes("signature"));
        return lic;
    }
}
