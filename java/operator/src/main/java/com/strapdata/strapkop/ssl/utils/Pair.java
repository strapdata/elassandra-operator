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

import com.google.common.base.Objects;

public class Pair<T1, T2> {
    public final T1 left;
    public final T2 right;
    
    protected Pair(T1 left, T2 right) {
        this.left = left;
        this.right = right;
    }
    
    @Override
    public final int hashCode() {
        int hashCode = 31 + (left == null ? 0 : left.hashCode());
        return 31 * hashCode + (right == null ? 0 : right.hashCode());
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public final boolean equals(Object o) {
        if (!(o instanceof Pair))
            return false;
        
        Pair<?, ?> that = (Pair) o;
        // handles nulls properly
        return Objects.equal(left, that.left) && Objects.equal(right, that.right);
    }
    
    @Override
    public String toString() {
        return "(" + left + "," + right + ")";
    }
    
    public static <X, Y> Pair<X, Y> create(X x, Y y) {
        return new Pair<X, Y>(x, y);
    }
}
