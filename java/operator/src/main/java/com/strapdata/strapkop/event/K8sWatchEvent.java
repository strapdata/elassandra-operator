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

package com.strapdata.strapkop.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.EnumSet;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class K8sWatchEvent<ResourceT> {

    public static final EnumSet<Type> creationEventTypes = EnumSet.of(Type.ADDED, Type.MODIFIED, Type.INITIAL);
    public static final EnumSet<Type> deletionEventTypes = EnumSet.of(Type.DELETED);

    public enum Type {
        INITIAL,
        ADDED,
        MODIFIED,
        DELETED,
        ERROR
    }

    private Type type;
    private ResourceT resource;
    private String lastResourceVersion = null;

    public boolean isUpdate() {
        return creationEventTypes.contains(this.type);
    }

    public boolean isDeletion() {
        return deletionEventTypes.contains(this.type);
    }

}