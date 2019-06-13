package com.strapdata.strapkop.sidecar;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.sidecar.BackupResponse;
import com.strapdata.model.sidecar.NodeStatus;
import io.micronaut.http.client.RxHttpClient;
import io.reactivex.Completable;
import io.reactivex.Single;

import java.net.URL;

import static io.micronaut.http.HttpRequest.GET;
import static io.micronaut.http.HttpRequest.POST;

/**
 * Currently @Client annotation advice that generates the client code from an interface is totally static and cannot
 * be used to configure client with dynamic urls. See {@link io.micronaut.http.client.interceptor.HttpClientIntroductionAdvice}
 */
public class SidecarClient {

    private RxHttpClient httpClient;
    
    public SidecarClient(URL url) {
        httpClient = RxHttpClient.create(url);
    }
    
    public Single<NodeStatus> status() {
        return httpClient.retrieve(GET("/status"), NodeStatus.class).singleOrError();
    }
    
    public Completable decommission() {
        return httpClient.exchange(POST("/decommission", "")).ignoreElements();
    }
    
    public Single<BackupResponse> backup(BackupArguments backupArguments) {
        return httpClient.retrieve(POST("/backups", backupArguments), BackupResponse.class).singleOrError();
    }
}
