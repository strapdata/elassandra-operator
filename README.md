# Strapkop

Elassandra Kubernetes Operator

## Build

Everything is now managed by gradle.

Compile the java projects :
```bash
./gradlew build
```

Build the docker images :
```bash
./gradlew dockerBuild
```

Publish the docker images :
```bash
./gradlew dockerPush -PregistryUsername=barth -PregistryPassword=viande1994 -PregistryEmail=barth@strapdata.com
```

Build parameters are located in `gradle.properties`.

## Integration testing

Deploy the operator and a datacenter (3 nodes) for manual experimentation :
```bash
./gradlew :test:basicSetup -PbasicSetupDc=dc1 -PbasicSetupReplicas=3
```

Examples of running the test scripts from gradle :
```bash
./gradlew :test:testBasic
./gradlew :test:testScale
./gradlew :test:testBackup
```

This scripts are parameterized (see `./test/gradle.properties`) :
```bash
./gradlew test:testScale -PscaleFrom=1 -PscaleTo=3 -PscaleDc=dc1
```

To cleanup all resources created in the k8s cluster :
```bash
./gradlew :test:cleanup
```