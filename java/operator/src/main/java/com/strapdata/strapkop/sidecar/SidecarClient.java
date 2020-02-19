package com.strapdata.strapkop.sidecar;

import com.strapdata.strapkop.model.backup.BackupArguments;
import com.strapdata.strapkop.model.sidecar.BackupResponse;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
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
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(POST("/operations/cleanup" +qs, "")).ignoreElements();
    }

    public Completable rebuild(String sourceDcName, String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(POST("/operations/rebuild/"+sourceDcName+ qs, "")).ignoreElements();
    }

    public Completable flush(String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(POST("/operations/flush" + qs, "")).ignoreElements();
    }

    public Completable repairPrimaryRange(String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(POST("/operations/repair" + qs, "")).ignoreElements();
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
