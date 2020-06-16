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

package com.strapdata.strapkop.utils;

import io.kubernetes.client.custom.Quantity;

public class QuantityConverter {

    private static final int KB = 1024;
    private static final int MB = 1024*1024;

    public static Long toBytes(Quantity quantity) {
        return quantity.getNumber().longValue();
    }

    public static Long toKiloBytes(Quantity quantity) {
        return quantity.getNumber().longValue() / KB;
    }

    public static Long toMegaBytes(Quantity quantity) {
        return quantity.getNumber().longValue() / MB;
    }

    public static int toCpu(Quantity quantity) {
        return Math.max(1, quantity.getNumber().intValue());
    }

}
