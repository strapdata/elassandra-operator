# Strapkop

Elassandra Kubernetes Operator

## Build

Compile the java module :
```bash
./gradlew build
```

Build the docker images :
```bash
./gradlew dockerBuild
```

Publish the docker images :
```bash
./gradlew dockerPush -PregistryUsername=user -PregistryPassword=password -PregistryEmail=user@example.com
```

Build parameters are located in `gradle.properties`.

## Integration testing

Deploy the operator and a datacenter (3 nodes) for manual experimentation :
```bash
./test/basic-setup dc1 3
```

Run a full test that spawn a 1-node dc, scale to 3 nodes then cleanup everything :
```bash
./test/test-scale dc1 1 3
```

To cleanup all resources created in the k8s cluster :
```bash
./test/full-cleanup
```

Running all the main test scenarios from gradle :
```bash
./gradlew :test:test
```

There are plenty of test and helpers script located in `./test`, `./test/lib` and `./test/aks`.
They are parameterized from env variables with consistent default (see `./test/config`).

## Debugging

The operator image can be built we debug enabled :
```bash
./gradlew dockerBuild -PoperatorEnabledDebug=true -PoperatorDebugSuspend=false
```

The sidecar image is by now build with debug enable on port 5005.

## CI/CD

Check-out the [Jenkins CI](https://jenkins.azure.strapcloud.com/blue/organizations/jenkins/strapkop/activity).

Currently we use Jenkins CI only for building staging image and testing. We do not build release images yet.

## Backlogs... (move this to github trello-like)

- [] Migrate existing to Micronaut / RxJava
- [] Refactoring of the watch / controller logic according to specs
- [] Refactoring of CRD interface
- [] Implement ElassandraTask mechanism
- [] Using NodePort, public broadcast addr and, hard anti-affinity to prepare for multi-dc
- [] Operator Peering, cross-cluster discovery and custom seed service
- [] Backup using ElassandraTasks and sstableloader
- [] Secure sidecar





