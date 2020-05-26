# Cassandra Reaper

Build the Cassandra reaper docker image including the driver-addons AddressTranslator.

## Build

Local registry build:

```bash
 ./gradlew reaper:pushReaper -PregistryUrl="localhost:5000/" -PregistryInsecure
```
