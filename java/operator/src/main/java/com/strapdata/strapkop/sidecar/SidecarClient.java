package com.strapdata.strapkop.sidecar;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.sidecar.BackupResponse;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import io.micronaut.http.client.RxHttpClient;
import io.reactivex.Completable;
import io.reactivex.Single;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;

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
    
    public Single<ElassandraNodeStatus> status() {
        return httpClient.retrieve(GET("/status"), ElassandraNodeStatus.class).singleOrError();
    }
    
    public Completable decommission() {
        return httpClient.exchange(POST("/operations/decommission", "")).ignoreElements();
    }
    
    public Completable cleanup(String keyspace) throws UnsupportedEncodingException {
        return httpClient.exchange(POST("/operations/cleanup?keyspace=" + URLEncoder.encode(keyspace,"UTF-8"), "")).ignoreElements();
    }

    public Completable repairPrimaryRange(String keyspace) throws UnsupportedEncodingException {
        return httpClient.exchange(POST("/operations/repair?keyspace=" + URLEncoder.encode(keyspace,"UTF-8"), "")).ignoreElements();
    }

    public Single<BackupResponse> backup(BackupArguments backupArguments) {
        return httpClient.retrieve(POST("/backups", backupArguments), BackupResponse.class).singleOrError();
    }

    public boolean isRunning() {
        return httpClient.isRunning();
    }
    
    public void close() {
        httpClient.close();
    }
}
