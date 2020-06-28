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

package com.strapdata.strapkop.model;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@ToString
@EqualsAndHashCode
public class Key {

    @EqualsAndHashCode.Include
    public final String name;

    @EqualsAndHashCode.Include
    public final String namespace;

    public Key() {
        namespace = null;
        name = null;
    }

    public Key(String namespace, String name) {
        this.namespace = namespace;
        this.name = name;
    }

    public Key(final V1ObjectMeta metadata) {
        this.name = metadata.getName();
        this.namespace = metadata.getNamespace();
    }

    public String id() {
        return namespace + "/" +name;
    }
}
