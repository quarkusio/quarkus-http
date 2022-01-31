Quarkus HTTP
============

![Maven Central](https://img.shields.io/maven-central/v/io.quarkus.http/quarkus-http-core?logo=apache&style=for-the-badge)
![GitHub Workflow Status](https://img.shields.io/github/workflow/status/quarkusio/quarkus-http/Build?logo=github&style=for-the-badge)

A Vert.x based Servlet implementation.

## Release

```bash
# Bump version and create the tag
mvn release:prepare -Prelease
# Build the tag and push to OSSRH
mvn release:perform -Prelease
```

The staging repository is automatically closed. The sync with Maven Central should take ~30 minutes.
