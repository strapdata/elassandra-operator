# Elassandra operator integration tests

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