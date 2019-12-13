package com.strapdata.strapkop;

public class StrapkopException extends RuntimeException {
    public StrapkopException() {
        super();
    }
    
    public StrapkopException(String message) {
        super(message);
    }
    
    public StrapkopException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public StrapkopException(Throwable cause) {
        super(cause);
    }
    
    public StrapkopException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
