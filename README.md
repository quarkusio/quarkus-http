Quarkus HTTP
============

[![Version](https://img.shields.io/maven-central/v/io.quarkus.http/quarkus-http-core?logo=apache&style=for-the-badge)](https://search.maven.org/artifact/io.quarkus.http/quarkus-http-core)
[![GitHub Actions Status](<https://img.shields.io/github/actions/workflow/status/quarkusio/quarkus-http/build.yml?branch=main&logo=GitHub&style=for-the-badge>)](https://github.com/quarkusio/quarkus-http/actions?query=workflow%3A%22Build%22)

A Vert.x based Servlet implementation.

## Release

With Java 17:

```bash
./mvnw release:prepare release:perform -Prelease -DskipTests -Darguments=-DskipTests
```

The staging repository is automatically closed. The sync with Maven Central should take ~30 minutes.
