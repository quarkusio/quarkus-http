Quarkus HTTP
============

[![Version](https://img.shields.io/maven-central/v/io.quarkus.http/quarkus-http-core?logo=apache&style=for-the-badge)](https://search.maven.org/artifact/io.quarkus.http/quarkus-http-core)
[![GitHub Actions Status](<https://img.shields.io/github/workflow/status/quarkusio/quarkus-http/Build?logo=GitHub&style=for-the-badge>)](https://github.com/quarkusio/quarkus-http/actions?query=workflow%3A%22Build%22)

A Vert.x based Servlet implementation.

## Release

```bash
# Bump version and create the tag
mvn release:prepare -Prelease
# Build the tag and push to OSSRH
mvn release:perform -Prelease
```

The staging repository is automatically closed. The sync with Maven Central should take ~30 minutes.
