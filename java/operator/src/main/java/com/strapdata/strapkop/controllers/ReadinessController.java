package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.preflight.PreflightService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import javax.inject.Inject;


@Controller("/ready")
public class ReadinessController {

    @Inject
    PreflightService preflightService;

    public ReadinessController() {
    }

    /**
     * @return OK whene preflight service are applied (CA and CRD installation)
     */
    @Get("/")
    public HttpStatus index() {
        return (this.preflightService.isExecuted()) ? HttpStatus.OK : HttpStatus.NOT_FOUND;
    }

}
