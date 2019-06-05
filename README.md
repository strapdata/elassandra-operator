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
./gradlew dockerBuild -PregistryUsername=barth -PregistryPassword=viande1994 -PregistryEmail=barth@strapdata.com
```

Build parameters are located in `gradle.properties`.