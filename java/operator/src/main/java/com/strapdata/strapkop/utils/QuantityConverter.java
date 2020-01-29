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
