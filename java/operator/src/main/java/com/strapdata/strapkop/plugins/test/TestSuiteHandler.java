package com.strapdata.strapkop.plugins.test;

public interface TestSuiteHandler {

    void onTimeout(int nbOfSteps);

    void onEnd(int nbOfSteps);

    void onFailure(int nbOfSteps, String message);
}
