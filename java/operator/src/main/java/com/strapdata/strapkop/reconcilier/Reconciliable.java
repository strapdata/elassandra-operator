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

package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.K8sWatchEvent;
import io.reactivex.Completable;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@With
public class Reconciliable {

    @ToString.Exclude
    Completable completable;

    Kind kind;

    K8sWatchEvent.Type type;

    String resourceVersion;

    Long submitTime;
    Long startTime;

    public static enum Kind  {
        TASK,
        DATACENTER,
        STATEFULSET,
        ELASSANDRA_POD,
        NODE;
    }
}
