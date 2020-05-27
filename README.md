[![Build Status](https://travis-ci.com/strapdata/strapkop.svg?token=PzEdBQpdXSgcm2zGdxUn&branch=ele-gke-develop-vr2)](https://travis-ci.com/strapdata/strapkop)

# Elassandra Operator

Elassandra Kubernetes Operator

## Build

Compile the java module :
```bash
./gradlew build
```

Build the docker images (operator + latest elassandra):
```bash
./gradlew dockerBuild
```

Publish the docker images (operator + latest elassandra):
```bash
./gradlew dockerPush -PregistryUsername=$DOCKER_USERNAME -PregistryPassword=$DOCKER_PASSWORD -PregistryUrl=$DOCKER_URL
```

Publish in local insecure registry
```bash
./gradlew dockerPush -PregistryUrl="localhost:5000/" -PregistryInsecure
```

Build parameters are located in `gradle.properties`.

## Tests

M_K8S_FLAVOR=kind M_INTEG_DIR=scale_up mage integ:run



There are plenty of test and helpers script located in `./test`, `./test/lib` and `./test/aks`.
They are parameterized from env variables with consistent default (see `./test/config`).

## Debugging

The operator image can be built we debug enabled :
```bash
./gradlew dockerBuild -PoperatorEnabledDebug=true -PoperatorDebugSuspend=false
```

The sidecar image is by now build with debug enable on port 5005.

### Docker images

When building manually, the images are created under `docker.repo.strapdata.com/elassandra-operator-dev` (jenkins use the suffix `-staging`).

To remove the suffix, use the cli option `-PdockerImageSuffix=""`.

When building the images with `./gradlew dockerBuild`, only the latest elassandra version is built.
There is also `dockerBuildAllVersions` and `dockerPushAllVersions` that build and push images for all supported
elassandra version, according to the file `docker/supportedElassandraVersions.txt` (first line should be the latest).

## License Report

Generate a [license report](build/reports/dependency-license/index.html):
```bash
./gradlew generateLicenseReport
```

Upload the license report the the strapdata azure blobstore (available on web):
```bash
./gradlew uploadLicenseReport
./gradlew uploadLicenseNotices
```