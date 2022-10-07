# No NPE!

[![Build Status](https://github.com/vegardit/no-npe/workflows/Build/badge.svg "GitHub Actions")](https://github.com/vegardit/no-npe/actions?query=workflow%3A%22Build%22)
[![License](https://img.shields.io/github/license/vegardit/no-npe.svg?color=blue)](LICENSE.txt)
[![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-v2.1%20adopted-ff69b4.svg)](CODE_OF_CONDUCT.md)
[![Maven Central](https://img.shields.io/maven-central/v/com.vegardit.no-npe/no-npe-parent)](https://search.maven.org/artifact/com.vegardit.no-npe/no-npe-parent)


**Feedback and high-quality pull requests are highly welcome!**

1. [What is it?](#what-is-it)
1. [Usage](#usage)
1. [Building from Sources](#building)
   1. [Validating/Updating EEA files](#validate_update)
   1. [Adding a new Maven module](#new_module)
1. [Acknowledgement](#acknowledgement)
1. [License](#license)


## <a name="what-is-it"></a>What is it?

**No NPE!** is a repository of [Eclipse External Null Annotations](https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations) for
better static Null Analysis with the Eclipse Java Compiler (ecj).


## <a name="usage"></a>Usage

Usage of External Null Annotations in Eclipse is documented at
https://help.eclipse.org/latest/index.jsp?topic=/org.eclipse.jdt.doc.user/tasks/task-using_external_null_annotations.htm

### Binaries

**Release** binaries of this project are available at Maven Central https://search.maven.org/search?q=g:com.vegardit.no-npe

Latest **Snapshot** binaries are available via the [mvn-snapshots-repo](https://github.com/vegardit/no-npe/tree/mvn-snapshots-repo) git branch.
You need to add this repository configuration to your Maven `settings.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
   xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <profiles>
    <profile>
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


## <a id="building"></a>Building from Sources

To ensure reproducible builds this Maven project inherits from the [vegardit-maven-parent](https://github.com/vegardit/vegardit-maven-parent)
project which declares fixed versions and sensible default settings for all official Maven plug-ins.

The project also uses the [maven-toolchains-plugin](http://maven.apache.org/plugins/maven-toolchains-plugin/) which decouples the JDK that is
used to execute Maven and it's plug-ins from the target JDK that is used for compilation and/or unit testing. This ensures full binary
compatibility of the compiled artifacts with the runtime library of the required target JDK.

To build the project follow these steps:

1. Download and install Java 11 **AND** Java 17 SDKs, e.g. from:
   - Java 11: https://adoptium.net/releases.html?variant=openjdk11 or https://www.azul.com/downloads/?version=java-11-lts&package=jdk#download-openjdk
   - Java 17: https://adoptium.net/releases.html?variant=openjdk17 or https://www.azul.com/downloads/?version=java-17-lts&package=jdk#download-openjdk

1. Download and install the latest [Maven distribution](https://maven.apache.org/download.cgi).

1. In your user home directory create the file `.m2/toolchains.xml` with the following content:

   ```xml
   <?xml version="1.0" encoding="UTF8"?>
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
   </toolchains>
   ```

   Set the `[PATH_TO_YOUR_JDK_11]`/`[PATH_TO_YOUR_JDK_17]` parameters accordingly.

1. Checkout the code using one of the following methods:

    - `git clone https://github.com/vegardit/no-npe`
    - `svn co https://github.com/vegardit/no-npe no-npe`

1. Run `mvn clean verify` in the project root directory. This will execute compilation, unit-testing, integration-testing and
   packaging of all artifacts.


### <a name="validate_update"></a>Validating/Updating EEA files

The EEA files can be validated/updated using:

```bash
# validate all EEA files of all eea-* modules
mvn compile

# validate all EEA files of a specific module
mvn compile -am -pl <MODULE_NAME>
mvn compile -am -pl libs/eea-commons-io-2

# update/regenerate all EEA files of all eea-* modules
mvn compile -Deea-generator.action=generate

# update/regenerate all EEA files of a specific module
mvn compile -Deea-generator.action=generate -am -pl <MODULE_NAME>
mvn compile -Deea-generator.action=generate -am -pl libs/eea-commons-io-2
```

Updating EEA files will:
- add new types/fields/methods found
- remove obsolete declarations from the EEA files
- preserve null/non-null annotations specified for existing fields/methods


### <a name="new_module"></a>Adding a new Maven module

1. Create a new sub-directory under `libs/` using the name pattern `eea-[LIBRARY_NAME]-[LIBRARY_MAJOR_VERSION|latest]`

1. Create a pom.xml in the new directory like this:

    ```xml
    <?xml version="1.0"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

      <modelVersion>4.0.0</modelVersion>

      <parent>
        <groupId>com.vegardit.no-npe</groupId>
        <artifactId>no-npe-parent</artifactId>
        <version>[CURRENT_SNAPSHOT_VERSION]</version>
      </parent>

      <artifactId>no-npe-eea-[LIBRARY_NAME]-[LIBRARY_MAJOR_VERSION]</artifactId>

      <dependencies>
        <dependency>
          <groupId>[LIBRARY_GROUP_ID]</groupId>
          <artifactId>[LIBRARY_ARTIFACT_ID]</artifactId>
          <version>[LIBRARY_VERSION]</version>
          <optional>true</optional>
        </dependency>
      </dependencies>
    </project>
    ```

    For example:

    ```xml
    <?xml version="1.0"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
      xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

      <modelVersion>4.0.0</modelVersion>

      <parent>
        <groupId>com.vegardit.no-npe</groupId>
        <artifactId>no-npe-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
      </parent>

      <artifactId>no-npe-eea-cool-library4</artifactId>

      <dependencies>
        <dependency>
          <groupId>org.example.cool-library</groupId>
          <artifactId>cool-library</artifactId>
          <version>2.0.2</version>
          <optional>true</optional>
        </dependency>
      </dependencies>
    </project>
    ```

1. Add an eea-generator.properties in the same directory where you specify the relevant packages of the library.

   ```properties
   packages.include=package1,package2
   ```

   E.g.

   ```properties
   packages.include=org.example.cool-library.api,org.example.cool-library.spi
   ```

   The following options are available:
   |name|description|default|
   |-|-|-|
   |`packages.include`| The comma separated packages to recursively scan for class files| n/a
   |`classes.exclude`| A comma separated list of regex patterns that are matched against fully qualified class names to exclude them from scanning| n/a
   |`action`| The default action (`validate` or `generate` to perform during `mvn compile`) | `validate`
   |`output.dir`| Path to the root directory containing the .eea files. Used for validation and generation.| `src/main/resources`
   |`input.dirs`| Comma separated additional paths of root directories containing .eea files to read null annotations from on `generate`.| n/a
   |`omitRedundantAnnotatedSignatures`| If `true` lines with annotated signatures are not written to the file if they don't contain any null annotations | `false`

   These options can also be specified in the command line as system properties like `-Deea-generator.<OPTION_NAME>=`,
   e.g. `-Deea-generator.omitRedundantAnnotatedSignatures=true`

1. In the parent project's pom reference the module in the `<modules>` section.

1. Run the generator inside parent project to create the EEA files

   ```bash
   mvn compile -Deea-generator.action=generate -am -pl <MODULE_NAME>
   ```

   E.g.

   ```bash
   mvn compile -Deea-generator.action=generate -am -pl libs/eea-cool-library-2
   ```

1. Manually add missing null/non-null annotations in the generated EEA files under `src/main/resources`


## <a name="acknowledgement"></a>Acknowledgement

**No NPE!** was created by [Sebastian Thomschke](https://sebthom.de) and is sponsored by [Vegard IT GmbH](https://www.vegardit.com).

Creation of this project was inspired by https://github.com/lastnpe/eclipse-null-eea-augments/ but a different approach in
generating/packaging/validating of EEA files/archives has been taken.

**No NPE!** would not have been possible in its current form without the following technologies and learning resources:

**Technologies/Libraries**
- [ClassGraph](https://github.com/classgraph/classgraph) - fast Java classpath scanner
- [gmavenplus-plugin](https://groovy.github.io/GMavenPlus/) - Maven plugin to execute arbitrary Groovy code during Maven lifecycle phases
- [exec-maven-plugin](https://www.mojohaus.org/exec-maven-plugin/) - toolchain aware Maven plugin to execute custom Java code during Maven lifecycle phases
- [AssertJ](https://github.com/assertj/assertj) - strongly-typed assertions for unit testing

**Tutorials**
- Null Analysis/External Annotations https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations
- Using external null annotations https://help.eclipse.org/latest/index.jsp?topic=/org.eclipse.jdt.doc.user/tasks/task-using_external_null_annotations.htm
- Type Signature Syntax https://help.eclipse.org/latest/index.jsp?topic=/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/Signature.html
- NullPointerException presentation given at EclipseCon Europe 2016 https://www.slideshare.net/mikervorburger/the-end-of-the-world-as-we-know-it-aka-your-last-nullpointerexception-1b-bugs
- Field Descriptors https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-4.html#jvms-4.3.2


## <a name="license"></a>License

All files are released under the [Eclipse Public License 2.0](LICENSE.txt).

Individual files contain the following tag instead of the full license text:
```
SPDX-License-Identifier: EPL-2.0
```

This enables machine processing of license information based on the SPDX License Identifiers that are available here: https://spdx.org/licenses/.
