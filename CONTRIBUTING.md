# Contributing to No NPE!

Thank you for your interest in contributing to this project!
This guide explains how to get ready to contribute and submit changes.
For instructions on creating and packaging your own EEA artifact, see the
[standalone generator guide](GENERATOR.md) instead.

## Contents

- [Contributor responsibilities](#contributor-responsibilities)
- [Issues and pull requests](#issues-and-pull-requests)
- [Development setup](#development-setup)
- [Validate or update EEA sources](#validate-or-update-eea-sources)
- [Add a library module](#add-a-library-module)
- [Maintain versioned module chains](#maintain-versioned-module-chains)
- [Review packaged output](#review-packaged-output)
- [Source code formatting](#source-code-formatting)
- [Licensing](#licensing)

## Contributor responsibilities

By submitting a contribution, you confirm that:

- You are the sole author of the contributed content or have the required rights and permissions.
- If employed, you have obtained any required permission from your employer.
- Your contribution may be distributed under the project's license.

## Issues and pull requests

Use [GitHub Issues](https://github.com/vegardit/no-npe/issues) for bugs and feature requests.
Provide a clear description and reproduction steps when applicable.

Before making a substantial contribution, open an issue so the approach can be discussed.
When submitting a pull request:

- Document user-visible or generator behavior changes.
- Add tests for new features and significant changes.
- Reference the relevant issue.

## Development setup

The project inherits fixed plugin versions and build defaults from
[vegardit-maven-parent](https://github.com/vegardit/vegardit-maven-parent).
It uses the
[Maven Toolchains Plugin](https://maven.apache.org/plugins/maven-toolchains-plugin/)
to select the JDK required by each module independently from the JDK running Maven.

Install JDK 11, 17, 21, and 25.
For example, use the corresponding
[Eclipse Temurin downloads](https://adoptium.net/temurin/releases/):

- [Java 11](https://adoptium.net/temurin/releases/?version=11)
- [Java 17](https://adoptium.net/temurin/releases/?version=17)
- [Java 21](https://adoptium.net/temurin/releases/?version=21)
- [Java 25](https://adoptium.net/temurin/releases/?version=25)

Create `.m2/toolchains.xml` in your user home directory:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 https://maven.apache.org/xsd/toolchains-1.1.0.xsd">
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>11</version>
      <vendor>default</vendor>
    </provides>
    <configuration>
      <jdkHome>[PATH_TO_YOUR_JDK_11]</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
      <vendor>default</vendor>
    </provides>
    <configuration>
      <jdkHome>[PATH_TO_YOUR_JDK_17]</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>21</version>
      <vendor>default</vendor>
    </provides>
    <configuration>
      <jdkHome>[PATH_TO_YOUR_JDK_21]</jdkHome>
    </configuration>
  </toolchain>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>25</version>
      <vendor>default</vendor>
    </provides>
    <configuration>
      <jdkHome>[PATH_TO_YOUR_JDK_25]</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

Replace all four `[PATH_TO_YOUR_JDK_*]` placeholders.
Then clone the repository and run the complete build from its root:

```bash
git clone https://github.com/vegardit/no-npe
cd no-npe
./mvnw clean verify
```

On Windows, use `.\mvnw.cmd clean verify` and replace `./mvnw` with `.\mvnw.cmd` in the commands below.
The build compiles, runs unit and integration tests, validates EEA sources, and packages all artifacts.

## Validate or update EEA sources

The repository's parent POM validates EEA sources by default:

```bash
# validate every EEA module
./mvnw compile

# validate one module and its required reactor projects
./mvnw compile -am -pl <MODULE_NAME>
./mvnw compile -am -pl libs/eea-commons-io-2

# regenerate every EEA module
./mvnw compile -Deea-generator.action=generate

# add compatible evidence without replacing stored nullness values
./mvnw compile -Deea-generator.action=generate-additive

# regenerate one module
./mvnw compile -Deea-generator.action=generate -am -pl <MODULE_NAME>
./mvnw compile -Deea-generator.action=generate -am -pl libs/eea-commons-io-2
```

Both generation modes add new declarations and remove declarations absent from the scanned library.
`generate-additive` limits contract changes; it does not disable structural synchronization.
See the [generator actions and ownership rules](GENERATOR.md#generator-actions) before reviewing generated changes.

For a non-destructive view of contracts inferred without the current module's stored sources, generate into a temporary
directory:

```bash
./mvnw compile -am -pl <MODULE_NAME> -Deea-generator.action=generate -Deea-generator.input.dirs= -Deea-generator.output.dir=<ABSOLUTE_TEMP_DIR>
```

This clears the standard source inputs but leaves the module's configured `input.dirs.extra` active.
Compare the temporary tree with `src/main/resources` and review each difference.
The [clean-generation comparison](GENERATOR.md#clean-generation-comparison) explains how ownership affects the result.

## Add a library module

1. Create `libs/eea-[LIBRARY_NAME]-[LIBRARY_MAJOR_VERSION|latest]`.

2. Add a `pom.xml` using the current parent snapshot and the target library as a provided dependency:

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

     <modelVersion>4.0.0</modelVersion>

     <parent>
       <groupId>com.vegardit.no-npe</groupId>
       <artifactId>no-npe-parent</artifactId>
       <version>[CURRENT_SNAPSHOT_VERSION]</version>
       <relativePath>../..</relativePath>
     </parent>

     <artifactId>no-npe-eea-[LIBRARY_NAME]-[LIBRARY_MAJOR_VERSION]</artifactId>

     <dependencies>
       <dependency>
         <groupId>[LIBRARY_GROUP_ID]</groupId>
         <artifactId>[LIBRARY_ARTIFACT_ID]</artifactId>
         <version>[LIBRARY_VERSION]</version>
         <scope>provided</scope>
       </dependency>
     </dependencies>
   </project>
   ```

   For a hypothetical Cool Library 2, use an artifact such as `no-npe-eea-cool-library-2` and valid Java coordinates such
   as `org.example.coollibrary:cool-library:2.0.2`.

3. Add `eea-generator.properties` beside the module POM:

   ```properties
   packages.include=org.example.coollibrary.api,org.example.coollibrary.spi
   ```

   The [configuration reference](GENERATOR.md#configuration-reference) lists all supported options.

4. Add the module path to the root POM's `<modules>` section.

5. Generate its source EEA files:

   ```bash
   ./mvnw compile -Deea-generator.action=generate -am -pl libs/eea-cool-library-2
   ```

6. Review every generated change.
   Use `generate-additive` for the first update of existing sources.
   Use `generate` when current evidence should replace conflicts and withdraw obsolete generated evidence.
   If a manual value intentionally disagrees with current evidence, restore it and add `@Keep` to protect the complete
   member.
   Manually add missing nullness markers and leave their gaps out of `@Generated(...)` so later generator silence preserves
   them as manual evidence.

## Maintain versioned module chains

The repository uses `input.dirs.extra` to feed each versioned module the reviewed EEA source tree of its immediate
predecessor:

- Java 11 -> Java 17 -> Java 21 -> Java 25
- Spring 5 -> Spring 6 -> Spring 7
- JUnit 5 -> JUnit 6
- SLF4J 1 -> SLF4J 2

This means a manual customization made once in Java 11 can propagate through Java 17 and Java 21 into Java 25.
The Spring modules use the same model.
Generate the modules in chain order after changing an earlier version.

Configure the immediate predecessor in the successor's `eea-generator.properties`:

```properties
input.dirs.extra=../eea-java-11/src/main/resources
```

By default, the parent POM supplies the current module and the Java 11 source tree through `input.dirs`.
Modules can override that baseline; Spring 6 and Spring 7 use Java 17.
`input.dirs.extra` appends to the effective inputs instead of replacing them.
Configure it in the properties file.
Passing `-Deea-generator.input.dirs.extra=...` to Maven does not forward the value to the forked generator.
Relative paths in the properties file resolve against that file's directory.

Propagation follows contract ownership.
The current module's earlier input value wins a conflict, while the predecessor can fill an unqualified position.
A manual predecessor position survives when successor analysis is silent.
A generated-owned predecessor position is withdrawn by `generate` when successor analysis is silent and therefore does not
continue to the next version.
`generate-additive` retains that position and its generated ownership.
See [versioned artifact propagation](GENERATOR.md#versioned-artifact-propagation) and
[layered input precedence](GENERATOR.md#layered-input-directory-precedence) for the complete merge rules.

## Review packaged output

`process-resources` automatically minimizes each module's EEA sources into `target/classes`.

- Do not select `minimize` through `eea-generator.action`.
  That would run the source execution with `src/main/resources` as its output and minimize source files in place.
- Minimization removes source comments, including ownership markers.
  It can discard metadata-only members after removing their comments.
- Packaging treats `src/main/resources` as the completed source tree, including propagated contracts.
  The packaging execution therefore clears `input.dirs.extra` and minimizes only that tree.
- A direct `minimize` invocation still honors `input.dirs.extra` when several source trees should be merged intentionally.

## Source code formatting

Before committing, format Java sources with the
[vegardit.com Eclipse formatter rules](https://github.com/vegardit/vegardit-maven-parent/blob/main/src/etc/eclipse-formatter.xml).
IntelliJ users can import them with the
[Eclipse Code Formatter](https://plugins.jetbrains.com/plugin/6546-eclipse-code-formatter) plugin.

## Licensing

Contributions and the project are licensed under the [Eclipse Public License 2.0](LICENSE.txt).
