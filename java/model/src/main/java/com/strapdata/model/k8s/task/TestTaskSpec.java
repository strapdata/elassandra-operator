package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TestTaskSpec {

    @SerializedName("timeOut")
    @Expose
    private int timeOut;

    @SerializedName("testSuite")
    @Expose
    private String testSuite;
}
