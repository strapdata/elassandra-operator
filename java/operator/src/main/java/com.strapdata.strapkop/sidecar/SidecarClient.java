package com.strapdata.strapkop.sidecar;

import com.instaclustr.model.backup.BackupArguments;
import com.instaclustr.model.sidecar.BackupResponse;
import com.instaclustr.model.sidecar.NodeStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;
import io.reactivex.Completable;
import io.reactivex.Single;

@Client
public interface SidecarClient {

    @Get("/status")
    public Single<NodeStatus> status();

    @Post("/decommission")
    public Completable decommission();

    @Post("/backups")
    public Single<BackupResponse> backup(@Body BackupArguments backupArguments);

}
