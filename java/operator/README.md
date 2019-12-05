# Operator Test Suite

## How to develop a test suite

// TODO

## How to enable/disable the TestSuiteTask

To enable/disable the TestSuite plugin, you have to update the TESTSUITE_ENABLE env variable on the Operator Deployment spec.

```
kubectl set env $(kubectl get deploy -l operator=elassandra -o name) TESTSUITE_ENABLE=true
```