# Elassandra Datacenter Ctl

## Build 

Build jar:
```
./gradlew java:edctl:shadowJar
```

Build executable:
```
./gradlew java:edctl:buildExec
```

## Usage

```
java -jar java/edctl/build/libs/edctl.jar wait -p RUNNING
```