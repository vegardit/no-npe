# No NPE!

[![Build Status](https://img.shields.io/github/actions/workflow/status/vegardit/no-npe/build.yml?logo=github)](https://github.com/vegardit/no-npe/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/vegardit/no-npe.svg?color=blue)](LICENSE.txt)
[![Maven Central](https://img.shields.io/maven-central/v/com.vegardit.no-npe/no-npe-parent)](https://central.sonatype.com/artifact/com.vegardit.no-npe/no-npe-parent)

**Feedback and high-quality pull requests are highly welcome!**

## What is it?

**No NPE!** provides
[Eclipse External Null Annotations](https://github.com/eclipse-jdt/eclipse.jdt.core/wiki/Null-Analysis-External-Annotations)
for better static null analysis with the Eclipse Java Compiler (ECJ).
The published JARs add nullness contracts to Java APIs and third-party libraries without modifying those libraries.

This repository serves three groups:

- Application developers can use the published annotation artifacts in their projects.
- Library maintainers can use the generator to create and package their own EEA artifacts.
- Contributors can add and maintain artifacts in this repository.

## Use the annotations in your project

### 1. Choose an artifact

Prefer the artifact that matches the library and major version on your class path.
For example:

```xml
<dependency>
  <groupId>com.vegardit.no-npe</groupId>
  <artifactId>no-npe-eea-commons-io-2</artifactId>
  <version>[NO_NPE_VERSION]</version>
  <scope>provided</scope>
</dependency>
```

Available coordinates are listed in
[Maven Central](https://central.sonatype.com/search?q=g%3Acom.vegardit.no-npe&sort=name).
The Java runtime artifacts are version-specific, so select the one matching the Java API level targeted by your project.
For cross-compilation, use the `--release` target or Eclipse JRE System Library version, not necessarily the JDK that runs
the compiler.

`no-npe-eea-all` is a convenient alternative when one annotation JAR for all supported libraries is more useful than
exact version alignment.
It merges every versioned artifact.
If multiple artifacts contain an explicit contract for the same member, the first contract in the aggregate wins and later
artifacts only fill missing contracts.
The aggregate can therefore be less precise than the artifact matching the library version in your project.

### 2. Make the artifact visible to Eclipse JDT

Enable annotation-based null analysis under
**Java > Compiler > Errors/Warnings > Null analysis**.
Then use one of Eclipse JDT's supported lookup strategies:

- Add the EEA artifact to the project's build path and enable
  **Search for external annotations in all build path locations**.
  This is the simplest setup for the Maven dependency above.
- Alternatively, open the project's **Java Build Path**, select the corresponding library or JRE entry, and set its
  **External annotations** location to the downloaded EEA JAR.

Use the same annotation location for all workspace projects that refer to a given library.
See Eclipse's
[Using External Null Annotations](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.user/tasks/task-using_external_null_annotations.htm)
guide for the complete IDE setup.

For batch ECJ compilation, enable null analysis and add:

```text
-annotationpath CLASSPATH
```

`CLASSPATH` tells ECJ to search the compilation class path and source path for EEA files.
See the
[ECJ batch compiler options](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.user/tasks/task-using_batch_compiler.htm)
for the surrounding compiler configuration.

### Releases and snapshots

Release artifacts are available from
[Maven Central](https://central.sonatype.com/search?q=g%3Acom.vegardit.no-npe&sort=name).

Snapshot artifacts are published through the
[`mvn-snapshots-repo` branch](https://github.com/vegardit/no-npe/tree/mvn-snapshots-repo).
Add this repository to your Maven `settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
   xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <profiles>
    <profile>
      <id>no-npe-snapshots</id>
      <repositories>
        <repository>
          <id>no-npe-snapshots</id>
          <name>no-npe-snapshots</name>
          <url>https://raw.githubusercontent.com/vegardit/no-npe/mvn-snapshots-repo</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>no-npe-snapshots</activeProfile>
  </activeProfiles>
</settings>
```

## Create your own EEA artifact

The [generator guide](GENERATOR.md) explains how to create and package EEA artifacts for other libraries.
It covers Maven integration, generator actions, EEA syntax, manual ownership, version-to-version propagation, and merge
precedence.

## Contribute to this project

See [CONTRIBUTING.md](CONTRIBUTING.md) for the required JDK setup, build and validation commands, module layout, versioned
module chains, review workflow, and pull request guidelines.

## Acknowledgement

**No NPE!** was created by [Sebastian Thomschke](https://sebthom.de) and is sponsored by
[Vegard IT GmbH](https://www.vegardit.com).

Creation of this project was inspired by
[eclipse-null-eea-augments](https://github.com/lastnpe/eclipse-null-eea-augments/), but this project takes a different
approach to generating, packaging, and validating EEA files and archives.

**Technologies and libraries**

- [ClassGraph](https://github.com/classgraph/classgraph) - fast Java class path scanner
- [GMavenPlus](https://groovy.github.io/GMavenPlus/) - Maven plugin for executing Groovy during Maven lifecycle phases
- [Exec Maven Plugin](https://www.mojohaus.org/exec-maven-plugin/) - toolchain-aware execution of the generator
- [AssertJ](https://github.com/assertj/assertj) - strongly typed assertions for tests

**Further reading**

- [Null Analysis and External Annotations](https://github.com/eclipse-jdt/eclipse.jdt.core/wiki/Null-Analysis-External-Annotations)
- [Using External Null Annotations](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.user/tasks/task-using_external_null_annotations.htm)
- [Eclipse JDT type signature syntax](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/Signature.html)
- [The End of the World as We Know It, aka Your Last NullPointerException](https://www.slideshare.net/mikervorburger/the-end-of-the-world-as-we-know-it-aka-your-last-nullpointerexception-1b-bugs)
- [JVM field descriptors](https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.3.2)

## License

All files are released under the [Eclipse Public License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:

```text
SPDX-License-Identifier: EPL-2.0
```

This enables machine processing of license information using the
[SPDX License Identifiers](https://spdx.org/licenses/).
