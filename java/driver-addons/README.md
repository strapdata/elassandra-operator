# Driver Addons

Cassandra driver address translator allowing to use Cassandra/Elassandra from a private network.

## Install

```bash
 ./gradlew clean java:driver-addons:publishToMavenLocal
```

## Publish to Strapdata maven repo

    ./gradlew publish -PrepoUsername=$NEXUS_USERNAME -PrepoPassword="$NEXUS_PASSWORD"