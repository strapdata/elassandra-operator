# Cassandra Reaper

Build the Cassandra reaper docker image including our Cassandra AddressTranslator (see java:driver-addons).

## Build

Local registry build:

```bash
 ./gradlew reaper:pushReaper -PregistryUrl="localhost:5000" -PregistryInsecure
```
