# Elassandra Operator K8s data model

## Build

```bash
 ./gradlew clean java:model:publishToMavenLocal
```

```bash
./gradlew clean java:model:publish -PrepoUsername=$NEXUS_USERNAME -PrepoPassword=$NEXUS_PASSWORD
```