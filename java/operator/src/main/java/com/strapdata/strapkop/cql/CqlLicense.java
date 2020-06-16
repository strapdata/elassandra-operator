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
