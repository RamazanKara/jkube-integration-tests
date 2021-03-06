# JKube Integration Tests

This project hosts Integration test suites for https://github.com/eclipse/jkube.

## Test Structure

In order to be able to run the tests in a CI environment without hogging the resources
and to provide specific tests for OpenShift clusters, test suites are divided in the following
way.

### Tags

There are three [tags](src/test/java/org/eclipse/jkube/integrationtests/Tags.java) which match with a maven profile
(`Kubernetes`, `OpenShift` & `Windows`).

Each of these tags should be applied to a specific test case suite in order to execute
the given tests only when that profile is specified.

e.g. when running with a standard k8s cluster `mvn verify -PKubernetes`

### Suite groups

Tests are divided into several groups in order to run only a set of tests.

As of now, test groups are: `dockerfile`, `other`, `quarkus`, `quarkus-native`, `springboot`, `webapp`

Following the same principle as with tags, in order to activate a given set of tests,
a profile matching the group name defined above must be specified-

e.g. Spring Boot tests when running in an Open Shift cluster `mvn verify -POpenShift,springboot`

**Warning:** If you've run any other suite in your local environment run a "profile-less" `mvn clean`
command prior to anything else so that all `target` directories get deleted,
else other profiles will get triggered.

