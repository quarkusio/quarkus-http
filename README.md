Quarkus HTTP
============

A Vert.x based Servlet implementation.

## Release

```bash
# Bump version and create the tag
mvn release:prepare -Prelease
# Checkout the created tag and deploy the artifacts to Nexus
git checkout $TAG
mvn deploy -Prelease
```

The staging repository is automatically closed. The sync with Maven Central should take a few hours.
