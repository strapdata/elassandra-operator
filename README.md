# Strapkop

Elassandra Kubernetes Operator

## Build

Compile the java module :
```bash
./gradlew build
```

Build the docker images (operator + sidecar + latest elassandra):
```bash
./gradlew dockerBuild
```

Publish the docker images (operator + sidecar + latest elassandra):
```bash
./gradlew dockerPush -PregistryUsername=$DOCKER_USERNAME -PregistryPassword=$DOCKER_PASSWORD -PregistryUrl=$DOCKER_URL
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

## Release & CI/CD

Check-out the [Jenkins CI](https://jenkins.azure.strapcloud.com/blue/organizations/jenkins/strapkop/activity).

### Docker images

When building manually, the images are created under `docker.repo.strapdata.com/elassandra-operator-dev` (jenkins use the suffix `-staging`).

To remove the suffix, use the cli option `-PdockerImageSuffix=""`.

When building the images with `./gradlew dockerBuild`, only the latest elassandra version is built.
There is also `dockerBuildAllVersions` and `dockerPushAllVersions` that build and push images for all supported
elassandra version, according to the file `docker/supportedElassandraVersions.txt` (first line should be the latest).