package com.strapdata.strapkop.plugins.test.step;

import com.strapdata.strapkop.StrapkopException;

public class StepFailedException extends RuntimeException {

    StepFailedException(String message) {
        super(message);
    }

    public static void failed(String message) throws StepFailedException {
        throw new StepFailedException(message);
    }
}
