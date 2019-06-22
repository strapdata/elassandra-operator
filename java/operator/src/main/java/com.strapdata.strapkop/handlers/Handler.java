package com.strapdata.strapkop.handlers;

import javax.inject.Qualifier;
import javax.inject.Singleton;
import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Singleton
@Qualifier
@Retention(RUNTIME)
public @interface Handler {
}
