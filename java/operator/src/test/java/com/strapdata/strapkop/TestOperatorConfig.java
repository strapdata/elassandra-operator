package com.strapdata.strapkop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestOperatorConfig {

    @Test
    public void testTaskRetentionHours() {
        OperatorConfig.TasksConfig t = new OperatorConfig.TasksConfig();
        t.retentionPeriod = "2H";
        Assertions.assertEquals(2*3600*1000, t.convertRetentionPeriodInMillis());
        t.retentionPeriod = "2h";
        Assertions.assertEquals(2*3600*1000, t.convertRetentionPeriodInMillis());
    }

    @Test
    public void testTaskRetentionMinutes() {
        OperatorConfig.TasksConfig t = new OperatorConfig.TasksConfig();
        t.retentionPeriod = "2M";
        Assertions.assertEquals(2*60*1000, t.convertRetentionPeriodInMillis());
        t.retentionPeriod = "2m";
        Assertions.assertEquals(2*60*1000, t.convertRetentionPeriodInMillis());
    }

    @Test
    public void testTaskRetentionDays() {
        OperatorConfig.TasksConfig t = new OperatorConfig.TasksConfig();
        t.retentionPeriod = "2D";
        Assertions.assertEquals(2*24*3600*1000, t.convertRetentionPeriodInMillis());
        t.retentionPeriod = "2d";
        Assertions.assertEquals(2*24*3600*1000, t.convertRetentionPeriodInMillis());
    }

    @Test
    public void testTaskRetentionInvalid() {
        OperatorConfig.TasksConfig t = new OperatorConfig.TasksConfig();
        t.retentionPeriod = "2Days";
        Assertions.assertThrows(StrapkopException.class, () -> t.convertRetentionPeriodInMillis());
        t.retentionPeriod = "P2Days";
        Assertions.assertThrows(StrapkopException.class, () -> t.convertRetentionPeriodInMillis());
        t.retentionPeriod = "P2s";
        Assertions.assertThrows(StrapkopException.class, () -> t.convertRetentionPeriodInMillis());
    }
}
