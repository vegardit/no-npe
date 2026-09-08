# Generating Eclipse External Annotations

This guide is for maintainers who want to create and package an EEA artifact for a library.
For application setup, see the [project README](README.md).
For adding an artifact to this repository, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Contents

- [Create an artifact](#create-an-artifact)
- [EEA file format](#eea-file-format)
- [Generator actions](#generator-actions)
- [Contract ownership](#contract-ownership)
- [Versioned artifact propagation](#versioned-artifact-propagation)
- [Layered input directory precedence](#layered-input-directory-precedence)
- [Generator update rules](#generator-update-rules)
- [Inference boundaries](#inference-boundaries)
- [Clean-generation comparison](#clean-generation-comparison)
- [Configuration reference](#configuration-reference)

## Create an artifact

Create this layout:

```
your-eea-artifact/
|-- pom.xml
|-- eea-generator.properties
`-- src/main/resources/
```

The following standalone Maven configuration scans one target library, validates source EEA files by default, and minimizes
them into the packaged JAR during `process-resources`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

  <modelVersion>4.0.0</modelVersion>

  <groupId>org.example</groupId>
  <artifactId>cool-library-eea</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <eea-generator.version>[NO_NPE_VERSION]</eea-generator.version>
    <eea-generator.action>validate</eea-generator.action>
    <eea-generator.input.dirs>${project.basedir}/src/main/resources</eea-generator.input.dirs>
    <eea-generator.output.dir>${project.basedir}/src/main/resources</eea-generator.output.dir>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.example.coollibrary</groupId>
      <artifactId>cool-library</artifactId>
      <version>2.0.2</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <resources>
      <resource>
        <directory>src/main/resources</directory>
        <excludes>
          <exclude>**/*.eea</exclude>
        </excludes>
      </resource>
    </resources>

    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.6.3</version>
        <executions>
          <execution>
            <id>${eea-generator.action}-eeas</id>
            <phase>generate-resources</phase>
            <goals>
              <goal>exec</goal>
            </goals>
            <configuration>
              <classpathScope>provided</classpathScope>
              <includePluginDependencies>true</includePluginDependencies>
              <executable>java</executable>
              <arguments>
                <argument>-Deea-generator.action=${eea-generator.action}</argument>
                <argument>-Deea-generator.input.dirs=${eea-generator.input.dirs}</argument>
                <argument>-Deea-generator.output.dir=${eea-generator.output.dir}</argument>
                <argument>-classpath</argument>
                <classpath />
                <argument>com.vegardit.no_npe.eea_generator.EEAGenerator</argument>
                <argument>${project.basedir}/eea-generator.properties</argument>
              </arguments>
            </configuration>
          </execution>
          <execution>
            <id>minimize-eeas</id>
            <phase>process-resources</phase>
            <goals>
              <goal>exec</goal>
            </goals>
            <configuration>
              <classpathScope>provided</classpathScope>
              <includePluginDependencies>true</includePluginDependencies>
              <executable>java</executable>
              <arguments>
                <argument>-Deea-generator.action=minimize</argument>
                <argument>-Deea-generator.input.dirs=${project.basedir}/src/main/resources</argument>
                <argument>-Deea-generator.input.dirs.extra=</argument>
                <argument>-Deea-generator.output.dir.default=${project.build.outputDirectory}</argument>
                <argument>-classpath</argument>
                <classpath />
                <argument>com.vegardit.no_npe.eea_generator.EEAGenerator</argument>
                <argument>${project.basedir}/eea-generator.properties</argument>
              </arguments>
            </configuration>
          </execution>
        </executions>
        <dependencies>
          <dependency>
            <groupId>com.vegardit.no-npe</groupId>
            <artifactId>no-npe-eea-generator</artifactId>
            <version>${eea-generator.version}</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
</project>
```

Replace the target-library coordinates and use a generator version available from
[Maven Central](https://central.sonatype.com/artifact/com.vegardit.no-npe/no-npe-eea-generator).
Run Maven with a JDK that can load the target library.

Configure the packages to scan in `eea-generator.properties`:

```properties
packages.include=org.example.coollibrary.api,org.example.coollibrary.spi
```

Generate the initial source tree:

```bash
mvn generate-resources -Deea-generator.action=generate
```

Review the EEA files under `src/main/resources`, then add contracts the analysis could not establish.
Use `generate-additive` for the first update of existing hand-maintained sources because it does not replace stored
nullness values:

```bash
mvn generate-resources -Deea-generator.action=generate-additive
```

Use `generate` after ownership metadata has been reviewed and current evidence should replace conflicts or withdraw obsolete
generated evidence.
`mvn package` validates the source tree and writes compact, comment-free EEA files to the output JAR.
The dedicated minimization execution clears `input.dirs.extra` because propagated contracts are already present in the
completed source tree.

If the artifact is consumed as an OSGi bundle, also add
`Eclipse-ExportExternalAnnotations: true` to its manifest.

## EEA file format

An EEA member entry has three lines: the member name, the raw JVM signature, and the annotated signature.
For example:

```
substring
 (II)Ljava/lang/String;
 (II)L1java/lang/String; # return value is non-null
```

The annotated signature copies the raw signature and inserts `0` for nullable or `1` for non-null at the qualified type
position.
An EEA file starts with a class header such as `class java/lang/String`.
The format can also qualify array dimensions, generic arguments, type-variable uses, and bounds.
See Eclipse's
[type signature API](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/Signature.html)
and [external annotation format](https://help.eclipse.org/latest/rtopic/org.eclipse.jdt.doc.user/tasks/task-using_external_null_annotations.htm)
before editing complex signatures manually.

## Generator actions

- `validate` checks supplied class type-parameter signatures, supertype declarations, members, and ownership marker syntax
  against the scanned classes without updating files.
  Omitted declarations are allowed; stored EEA files need not describe every declaration.
- `generate` synchronizes declarations and reconciles every contract position with current evidence.
- `generate-additive` synchronizes declarations but does not remove or replace stored nullness values.
- `minimize` merges configured inputs and writes compact artifact content without source comments or redundant members.

With the Maven configuration above, select a source action through the Maven property:

```bash
mvn generate-resources
mvn generate-resources -Deea-generator.action=generate
mvn generate-resources -Deea-generator.action=generate-additive
```

Do not select `minimize` through `eea-generator.action` in this setup.
That property controls the in-place source execution.
The separate `process-resources` execution minimizes the completed source tree into `target/classes`.

Updating EEA files will:

- add new types/fields/methods found
- remove obsolete declarations from the EEA files
- update existing contracts according to the ownership rules described below

Both generation actions synchronize declarations with the scanned library.
`generate-additive` limits contract updates, not structural additions and removals.

## Contract ownership

Marker-free contract evidence is manual.
Generator markers can instead assign ownership to the complete stored contract or to selected nullness positions.
Positional ownership lets generated evidence and manual additions share one signature without requiring `@Keep` merely
because current analysis has no evidence for a manual position.

### Generated ownership markers

| Marker form | Meaning |
|-|-|
| `@Generated` | Unscoped ownership: every stored nullness value is generated-owned. The generator writes this compact form only when it can establish that the complete signature has one legal `0` or `1` position and that position is generated-owned. |
| `@Generated(2,20)` | The `0` or `1` markers at raw-signature gaps 2 and 20 are generated-owned. Other stored positions are manual. |
| `@Generated(PolyNull)` | Declares generated PolyNull evidence. This marker records both the evidence and its ownership; do not add a separate `@PolyNull`. |
| `@Generated(2,20,PolyNull)` | The positional values and PolyNull evidence are generated-owned. |

### Generated relationship markers

| Marker form | Meaning |
|-|-|
| `@Inherited(parent.Type)` | The resulting contract has the same positional nullness markers as the selected parent. Without an explicit `@Generated(...)`, the relationship also has unscoped ownership. |
| `@Overrides(parent.Type)` | The resulting child contract differs from the selected parent. Without an explicit `@Generated(...)`, the relationship also has unscoped ownership. |

These generator-written markers describe where the effective contract came from.
A bare relationship makes the complete stored child contract generator-owned, even when its evidence came from a manual
parent contract.
An accompanying non-empty `@Generated(...)` marker takes precedence and narrows ownership to the listed positions or
PolyNull evidence.
All unlisted child evidence is then manual.

The ownership precedence is:

| Markers on the annotated-signature line | Ownership |
|-|-|
| no generator or relationship marker | Every stored `0`, `1`, and standalone `@PolyNull` is manual. |
| bare `@Generated` | Every stored contract element is generated-owned. |
| bare `@Inherited(...)` or `@Overrides(...)` | Every stored contract element is generated-owned. A redundant bare `@Generated` does not change this. |
| non-empty `@Generated(...)`, with or without a relationship | Only the listed positions and `PolyNull` token are generated-owned. Other stored evidence is manual. |
| any generator or relationship form with `@Keep` | `@Keep` protects the complete locally maintained member from generator updates, regardless of ownership metadata. |
| `@Imported` | The complete member is a protected copy of an upstream `@Keep` or `@Imported` contract. Its current value is refreshed or removed from the upstream source before generator reconciliation. |

### Manual evidence and whole-member protection

| Marker&nbsp;form | Meaning |
|-|-|
| `@PolyNull` | Manually maintained PolyNull evidence when no bare generator or relationship marker claims the complete contract and no `PolyNull` token claims it explicitly. It survives generator silence, but current conflicting evidence can replace it during `generate`. |
| `@Keep` | Protects the complete locally maintained member from upstream input sources and generator updates. Use it when a manual value intentionally disagrees with current evidence, or when the member must remain although it is absent from the scanned library version. |
| `@Imported` | Records that the complete protected member was copied from an upstream source. The generator writes this marker; do not add it manually. A later run refreshes the member from the last applicable upstream `@Keep` or `@Imported`, or removes the stale imported contract when no such source remains. |

`@Keep` and `@Imported` are mutually exclusive.
Use `@Keep` for a local decision and let the generator write `@Imported` in successors.
To replace an imported contract only in one successor, edit the complete member there and change `@Imported` to `@Keep`.

PolyNull means that a method returns `null` only when a particular input is `null`.
The EEA format cannot express that dependency directly, so the top-level return remains unqualified and the source comment
preserves the evidence.
Use standalone `@PolyNull` for manual evidence and `@Generated(PolyNull)` for generator-produced evidence.

For generator-produced PolyNull evidence, bytecode analysis retains the exact dependent parameters only during the current
run.
After local, inherited, and layered parameter evidence has been reconciled, the generator writes a non-null return instead
when every dependent parameter is effectively non-null.
An unrelated nullable parameter does not prevent this refinement.
If any dependent parameter remains nullable or unspecified, the return remains PolyNull.

Both generation modes apply this refinement to newly inferred contracts, including when the dependent parameter's non-null
contract is inherited.
`generate-additive` still preserves a conflicting stored PolyNull contract.
This protection applies to input contracts; intermediate results computed during the same run do not block refinement.

Stored manual or imported PolyNull evidence has no persisted dependency information and is therefore not refined merely
because some stored parameters are non-null.
It continues to follow the normal ownership and generation-mode rules.
In particular, `generate-additive` preserves a stored concrete return that conflicts with a refined current non-null return;
`generate` may replace that conflict.

Place `@Generated`, `@PolyNull`, `@Inherited(...)`, `@Overrides(...)`, `@Keep`, and `@Imported` in the comment on the
annotated-signature line, which is the third line of a member entry.
ECJ does not support comments after the member name or raw signature, so the generator rejects files that place them there.
`@Keep` and `@Imported` protect the complete member rather than an individual position.

### Positional ownership

The position numbers identify gaps in the raw JVM signature before any `0` or `1` markers are inserted.
For `(Ljava/lang/String;Ljava/lang/Object;)V`, gap 2 is after the first `L` and gap 20 is after the second `L`:

```
(L1java/lang/String;L0java/lang/Object;)V # @Generated(20)
```

The ownership consequences are:

- The first parameter is manual, and the second is generated-owned.
- If current analysis later becomes silent for both parameters, `generate` preserves the first marker and withdraws the
  second.
- If a user adds another marker, its gap is not automatically added to `@Generated(...)`.
  It remains manual until current analysis produces the same value at that position.

`@Generated(...)` accepts comma-separated raw-signature gap numbers and the exact token `PolyNull`; surrounding whitespace
is allowed.
At least one position or `PolyNull` token is required, so `@Generated()` is invalid.
The generator also rejects unsupported or empty tokens, negative or out-of-range positions, positions without a `0` or `1`
marker, duplicate positions, and duplicate `PolyNull` tokens.

### Compact singleton ownership

When the complete signature has only one legal position, the position number carries no additional ownership information.
The generator therefore uses compact output for simple signatures it can classify conservatively:

```
Ljava/lang/String;
L1java/lang/String; # @Generated

()Ljava/lang/String;
()L1java/lang/String; # @Inherited(java.lang.Object)

(Ljava/lang/Object;)Z
(L0java/lang/Object;)Z # @Inherited(java.lang.Object)
```

The last two examples are fully generator-managed because a relationship without non-empty `@Generated(...)` is unscoped.
A fully manual member therefore cannot retain `@Generated`, `@Inherited(...)`, or `@Overrides(...)`.
Remove those markers when maintaining the complete contract manually, and use `@Keep` when that manual contract must resist
current conflicting generator evidence.

Every array dimension is a legal nullness position, and a reference component adds another.
Consequently `()[I` and `([I)V` each have one position, while `()[[I`, `()[Ljava/lang/String;`, and
`([I)Ljava/lang/Object;` have more than one and retain positional ownership.
Generic signatures, inner-class signatures, and signatures with trailing throws clauses also retain positional ownership.
Singleton compaction is deliberately limited to the simple forms above.

### Compatibility and other comments

Unscoped ownership predates positional ownership, so bare `@Generated`, `@Inherited(...)`, and `@Overrides(...)` are also
accepted on signatures with several positions.
Every stored `0`, `1`, and standalone `@PolyNull` value on such a line is generated-owned.
The older paired form `@Generated(PolyNull) @PolyNull` is also accepted.
The next generation run preserves the ownership behavior, removes that redundant standalone `@PolyNull`, and writes exact
positions unless the signature qualifies for singleton compaction.
If a legacy unscoped contract contains a manual addition, convert it to the appropriate positional form or add `@Keep` before
the first generation run.

Explanatory comment text is independent from ownership and relationship markers and is retained when those markers are
rewritten.

## Versioned artifact propagation

`input.dirs` contains the artifact's normal EEA inputs.
`input.dirs.extra` appends an additional source tree without replacing those inputs.
Use it to chain reviewed customizations through successive library versions without copying the original customization into
every configuration:

```properties
packages.include=org.example.coollibrary
input.dirs.extra=../cool-library-1-eea/src/main/resources
```

Configure each version with only its immediate predecessor.
For example, version 1 feeds version 2, version 2 feeds version 3, and a manual contract added to version 1 reaches version 3
through the intermediate generated source tree.
Generate the versions in order after changing an earlier artifact so every successor reads the updated predecessor output.

Ordinary predecessor evidence follows positional ownership.
A predecessor can fill a position that the successor leaves unqualified, and a manual predecessor position survives when
successor analysis is silent.
A generated-owned predecessor position is withdrawn by `generate` when successor analysis is silent, so it does not continue
to the next version.
`generate-additive` retains that generated-owned position and its ownership.

`@Keep` uses whole-member propagation instead:

| Current artifact state | Applicable upstream protected state | Result in the current artifact |
|-|-|-|
| local `@Keep` | any | Preserve the complete local member. A local decision always wins. |
| local `@Imported` | upstream `@Keep` | Replace the complete cached member with the upstream contract and retain it as `@Imported`. |
| local `@Imported` | upstream `@Imported` | Refresh the complete cached member and retain `@Imported`, allowing protection to cross another version hop. |
| local `@Imported` | none | Discard the stale imported member, then apply ordinary inputs and current generator evidence. |
| no local protected contract | upstream `@Keep` or `@Imported` | Copy the complete upstream contract as `@Imported`. |
| no local `@Keep` | several upstream protected contracts | The last protected contract in input order supplies the complete imported member. |

These import decisions happen before either generation mode reconciles bytecode evidence.
Consequently an upstream edit or removal refreshes `@Imported` during both `generate` and `generate-additive`.
When writing an import, the generator removes stale `@Generated(...)`, `@Inherited(...)`, and `@Overrides(...)` provenance.
It retains explanatory text and converts protected PolyNull evidence to standalone manual `@PolyNull` under `@Imported`.

## Layered input directory precedence

The generator reads `input.dirs` first, followed by `input.dirs.extra`.
For generation, the first `input.dirs` entry is the current artifact's local source.
Any remaining `input.dirs` entries and every `input.dirs.extra` entry are upstream sources.
If `input.dirs` is empty, no source is local; this is how a clean-generation comparison can still consume configured
predecessors.
Directory order has different effects depending on why an EEA file is loaded:

| Operation | Precedence rule |
|-|-|
| Generate a class selected by `packages.include` | For ordinary evidence, the first explicit value at each position wins and later inputs fill only unqualified positions. The first concrete return or PolyNull contract wins their shared return meaning. A local `@Keep` wins completely. A later upstream `@Keep` or `@Imported` replaces the complete accumulated member and is written as `@Imported`; a still later protected source can refresh it, but ordinary evidence cannot modify it. An unconfirmed local `@Imported` is discarded. |
| Load an ancestor only for inheritance | The first directory containing that ancestor's EEA file supplies the complete file. Later copies of the same ancestor are not layered. |
| Minimize multiple source trees | The first explicit member contract wins. Later inputs fill missing files, members, or contracts but do not replace an existing explicit contract. A metadata-only PolyNull contract can accept later markers at independent positions, but its unqualified top-level return remains authoritative. |

A member contributes stored input when its annotated signature contains `0` or `1` or has an annotated-signature comment.
This includes source-only `@Generated`, `@PolyNull`, `@Inherited(...)`, `@Overrides(...)`, `@Keep`, and `@Imported` metadata.
When several selected-class inputs contribute to the same member, layering combines independent nullness positions before the
generator update rules run.
Ownership follows each accepted position, so a generated-owned predecessor position remains generated-owned while an unmarked
position remains manual.
For ordinary evidence, conflicting later values and later explanatory text do not replace the earlier input.

`input.dirs.extra` is normally a fallback for selected-class positions, ancestor lookup, and minimization.
Its protected contracts are the deliberate exception: they replace the complete selected-class member so changes to a
predecessor's `@Keep` cannot be hidden by a stale successor value.
The configured version chains intentionally rely on selected-class layering to feed the predecessor contract into the
successor's update rules.
For other uses, review overlapping files and put a directory earlier when its ancestor or minimized contracts must take
precedence.

## Generator update rules

`generate` reconciles each raw-signature position independently.
After the inheritance refinements below, current evidence wins at its exact position.
Current silence withdraws only evidence recorded as generated-owned; manual evidence survives.

`generate-additive` accepts compatible additions and matching evidence but never removes or changes a stored nullness value.
On a conflict it preserves the stored value and its existing ownership, while still accepting independent compatible
positions.
Generated-owned evidence also remains generated-owned during additive silence so a later full run can withdraw it.

`@Keep` bypasses all reconciliation rules below and preserves the complete local member in both modes, regardless of current
evidence.
A confirmed `@Imported` also bypasses reconciliation because its applicable upstream source controls the complete member.
An unconfirmed local `@Imported` is discarded during input layering and therefore never reaches these rules.
Without either protected state, the positional cases are:

| Stored position | Current generated position | `generate` | `generate-additive` |
|-|-|-|-|
| no marker | no marker | Leave the position unqualified. | Leave the position unqualified. |
| no marker | `0` or `1` | Add it as generated-owned. | Add it as generated-owned. |
| manual `0` or `1` | no marker | Preserve it as manual. | Preserve it as manual. |
| generated-owned `0` or `1` | no marker | Remove it and its ownership. | Preserve it and its ownership. |
| either source, same value | same value | Keep it and record generated ownership. | Keep it and record generated ownership. |
| manual value | opposite value | Replace it and record generated ownership. | Preserve the manual value. |
| generated-owned value | opposite value | Replace it and retain generated ownership. | Preserve the stored value and generated ownership. |

For example, a conflict on one parameter does not discard an independent manual return marker:

```
# stored: first parameter and return are manual
(L1java/lang/String;Ljava/lang/Object;)L1java/lang/String;

# current generator evidence
(L0java/lang/String;L1java/lang/Object;)Ljava/lang/String;

# generate: current parameters win, manual return survives
(L0java/lang/String;L1java/lang/Object;)L1java/lang/String; # @Generated(2,20)

# generate-additive: conflict is preserved, compatible second parameter is added
(L1java/lang/String;L1java/lang/Object;)L1java/lang/String; # @Generated(20)
```

Add `@Keep` when the manual value at a position must disagree with current generator evidence.
It is not needed merely to preserve a manual position for which current analysis is silent.

Source-only PolyNull evidence is reconciled like a separate ownership position, but it conflicts with a concrete
top-level return marker:

| Stored return evidence | Current generator evidence | `generate` | `generate-additive` |
|-|-|-|-|
| manual `@PolyNull` | silent | Preserve it as manual. | Preserve it as manual. |
| generated `@Generated(PolyNull)` | silent | Remove it. | Preserve it. |
| no concrete return marker | PolyNull | Add `@Generated(PolyNull)`. | Add `@Generated(PolyNull)`. |
| concrete return marker | PolyNull | Remove that return marker and add `@Generated(PolyNull)`. | Preserve the concrete return marker and do not add PolyNull evidence. |
| manual or generated PolyNull | concrete return marker | Remove the PolyNull evidence and add the generated return marker. | Preserve the PolyNull evidence and do not add the concrete return marker. |
| manual or generated PolyNull | PolyNull | Keep it and write `@Generated(PolyNull)`. | Keep it and write `@Generated(PolyNull)`. |

In every row, compatible parameter, component, nested-type, and other independent positions still follow the positional
rules above.

Inheritance combines the current parent and local evidence before applying the positional and PolyNull rules above.
A nullable parent return permits an override with a stronger, manually maintained non-null return.
When the override's own analysis supplies no return evidence, both generation modes preserve that top-level return as manual.
The generator uses the original input ownership, so provisional inheritance passes cannot change whether the return qualifies.

This refinement does not protect generated-owned returns from replacement during `generate`.
Local concrete or PolyNull return evidence still follows the normal reconciliation rules.
Parameter, array-component, and generic-argument positions also keep their normal positional precedence.
`@Keep` is not needed solely to retain this compatible manual return.

Relationship selection and refresh follow these rules:

| Current relationship state | Result |
|-|-|
| Named parent EEA is available | Recompute the parent-plus-local evidence, reconcile each stored position, and recalculate `@Inherited(...)` or `@Overrides(...)`. |
| Named parent is still an ancestor but its EEA is unavailable | Preserve the complete stored relationship because missing data is unknown, not an empty parent contract. |
| Named parent is no longer applicable and another annotated ancestor is available | Rebase to the most specific applicable ancestor, reconcile its contract with current local evidence, and write the new relationship marker. |
| Named parent EEA is available but has no applicable contract | Remove the stale relationship and reconcile against current local evidence. In full mode, silent generated-owned positions are withdrawn while manual positions survive. |
| Multiple annotated interfaces in one inheritance chain | Use the most specific annotated subinterface; its transitive ancestors do not create a conflict. |
| Unrelated interfaces provide conflicting contracts | Select no inheritance base and reconcile a replaceable stored relationship against current local evidence. |

Object defaults are inherited from the current run's Object contract after configured packages and their inputs have been
processed.
They are not saved as declaration-local evidence during scanning, so changing package order does not change which Object
contract an override receives.
Explicit annotations and bytecode evidence on the override still take precedence, and additive mode still preserves
conflicting stored child values.

PolyNull evidence is local analysis metadata and is not copied from a parent to a child.
It still makes that parent the nearest inheritance boundary, and independent parent positions remain inheritable.
When the resulting child has a bare relationship marker, all copied and local child evidence is generator-owned, including
evidence that was manual in the parent.
When the child also has non-empty `@Generated(...)`, only the listed child evidence is generator-owned and unlisted inherited
or local evidence remains manual.
In additive mode, a relationship update is accepted only when all stored nullness evidence remains unchanged.
The accepted contract remains the inheritance base for descendants during the same run.

When applicable parent and local analysis completes without evidence, relationship ownership affects later runs as follows.
An unavailable parent remains unknown and follows the preservation rule in the relationship-state table above.

| Stored child markers | Current evidence is silent |
|-|-|
| no generator or relationship marker | Preserve all stored evidence as manual. |
| bare `@Inherited(...)` or `@Overrides(...)` | Withdraw stored `0`, `1`, and standalone `@PolyNull` evidence during `generate`; retain it during `generate-additive`. |
| non-empty `@Generated(...)` plus a relationship | During `generate`, withdraw only listed evidence and preserve unlisted evidence as manual. Preserve both during `generate-additive`. |
| any of the above plus local `@Keep` | Preserve the complete member in both modes. |
| confirmed upstream `@Imported` contract | Preserve the complete imported member in both modes until layering refreshes or removes it. |

The ownership metadata has this lifecycle:

| Action | Behavior |
|-|-|
| Layer upstream inputs | Convert upstream `@Keep` and `@Imported` to a canonical current-artifact `@Imported`; refresh or remove an earlier import before generation. |
| `generate` | Applies current evidence, withdraws silent generated-owned evidence, preserves silent manual evidence, and writes canonical ownership metadata. |
| `generate-additive` | Adds compatible evidence and updates canonical ownership metadata without removing or replacing stored nullness evidence. |
| Maven build (`process-resources` or later) | Automatically removes source-only comments, including ownership markers, from packaged EEA artifacts. |

## Inference boundaries

### Explicit nullness annotation semantics

The generator reads recognized nullable and non-null declaration annotations on fields, method returns, and parameters.
It also reads type-use annotations that qualify the top-level value, including direct JSpecify `@Nullable` and `@NonNull`
annotations.
If malformed metadata supplies both contracts at the same position, nullable takes precedence.

For JSR-305 `@Nonnull`, the generator interprets `ALWAYS` as non-null, `MAYBE` and `NEVER` as nullable, and `UNKNOWN` as no
evidence.
It applies the same rules to annotations explicitly declared with `@TypeQualifierNickname`, including CLASS-retained
nickname metadata.
Scoped defaults such as JSpecify `@NullMarked` are not expanded into per-member contracts.

Only the annotation on the value itself is used for this decision.
An annotation on a generic argument, type-variable bound, array component, or enclosing segment of a nested type qualifies
that nested position and is not promoted to the whole value.
For a nested class signature such as `Lpkg/Outer<TT;>.Inner;`, ECJ interprets the top-level marker after the initial `L` as
the nullness of the complete `Inner` value.
The generator therefore writes a leaf type-use annotation at that position; it does not promote annotations from the
enclosing `Outer` segment.

An explicit parameter annotation replaces a template or heuristic marker at that parameter position while preserving
independent positions in the signature.
Primitive parameters do not receive EEA nullness markers.

### Non-returning method calls

The bytecode analyses recursively inspect helper methods when normal control flow depends on whether a call returns.
This recognizes guards such as `if (value == null) error(); return value;` when `error()` always throws, even if it delegates
through several helpers.
The call's normal successor is removed, but its exception edges remain reachable.
Consequently, a handler that catches the helper's exception and returns normally still contributes to the inferred contract.

The proof requires one fixed runtime target: static, private, and final calls qualify, as do calls on final classes.
Constructors also qualify.
A non-constructor `invokespecial` call qualifies only when its declaration is on the current class or immediate superclass.
An indirect symbolic ancestor can select an intermediate declaration instead, even when the named method is private and
accessible through the same nest.
A package-private virtual helper also qualifies when it is invoked from its declaring class and no same-package subclass in
the accepted package scan overrides it.
This closed-package rule supports internal library guards without treating public or protected extension points as fixed.

Abstract, native, unresolved, over-budget, and recursively cyclic helpers remain unknown.
The existing exact `Unsafe.throwException(Throwable)` contract is also recognized as a terminal call.

### Bytecode return contracts

Return inference imports a helper's bytecode summary only when the call has one fixed runtime target.
An overridable virtual call qualifies when every possible receiver is allocated as the exact declared owner.
Conditionally replacing an incoming receiver does not establish that proof, even after a non-null check or a copy to an alias.
Non-constructor `invokespecial` calls use the same current-class or immediate-superclass declaration restriction as
non-returning method proofs.
An indirect symbolic ancestor does not qualify, even when its body already has a cached non-null summary.

Helper summaries preserve return dependencies across classes.
For example, calling an identity helper with a non-null constant can prove a non-null return, while forwarding a caller
parameter preserves its `PolyNull` dependency.
Dependencies are mapped through each call's actual arguments, including reordered arguments and nested calls.
Unknown or cyclic helpers remain unknown, and a cached body does not bypass the call's dispatch restrictions.

An explicit null argument also reaches the caller's return when a fixed-target helper is proven to return that exact
entry argument on every normal return.
This proof supports copies and casts; an ordinary `PolyNull` dependency alone is insufficient because the helper may
replace null with a non-null fallback.
Null forwarding uses static or special calls and final dispatch, without depending on receiver-allocation facts from
unfinished flow analysis.

A conditional assignment must not hide a nullable original argument that can still reach the return.
If the analysis cannot preserve that possible value, the helper summary stays unknown.
This also applies when value analysis proves the assignment unreachable.

Standard `LambdaMetafactory` bootstrap calls prove that a lambda or method-reference object is non-null.
This applies to ordinary, capturing, and serializable lambdas; it does not prove that invoking the lambda returns non-null.
Standard string-concatenation factories also qualify, while arbitrary `invokedynamic` bootstraps remain unknown.

A successful `instanceof` test proves the tested reference non-null on its true edge.
The exact `Objects.isNull(Object)` and `Objects.nonNull(Object)` predicates establish nullness on both Boolean outcomes.
This recognizes returns such as `value instanceof String ? (String) value : ""` and `Objects.isNull(value) ? "" : value`.
A saved test result retains the original reference's identity; it does not refine a replacement assigned to the same local.
A failed `instanceof` test supplies no nullness fact.
A merged test of different references or different predicates does not qualify for value refinement.

All Java 11 `List.of(...)` overloads supply non-null return evidence, including the varargs form.
`getClass()` supplies the same evidence when its exact signature resolves to the final `Object.getClass()` method,
including inherited and array calls.
These contracts describe normal completion; a reachable handler that returns null still contributes null evidence.

`Object.clone()` has a separate proof that checks the selected method through the caller's superclass chain.
A superclass call must reach `Object` without an intervening clone declaration, and the receiver's class must implement
`Cloneable` directly or through its hierarchy.
Only then can the call supply non-null return evidence and exclude an otherwise unreachable `CloneNotSupportedException`
handler.
Naming `Object.clone()` in the instruction alone does not establish either fact.

### Bytecode parameter contracts

The generator derives parameter contracts from bytecode:

- It infers `NonNull` when every reachable normal return requires the parameter to be non-null.
  A successful instance-method call, field access, array access, or monitor operation proves its receiver or array non-null.
  The non-null edge of an explicit `null` guard or recognized `Objects` predicate, and the true edge of an `instanceof`
  test, supply the same fact.
  A call to an exactly resolved helper can also prove that an argument must be non-null for the call to return normally.
- It infers `Nullable` when a direct guard at method entry sends `null` through a straight, side-effect-free path to a
  normal return.
  This recognizes patterns such as `if (value == null) return false` without treating every conditional null return as a
  nullable contract.

If either proof is incomplete, the parameter remains unknown unless another evidence source supplies a contract.
The analysis is independent of return analysis, so it applies to methods with void, primitive, and reference returns.

The proofs have these boundaries:

- `IFNULL` and `IFNONNULL` guards qualify when the tested value resolves to exactly one original parameter.
  A local alias or cast still qualifies, while a reassigned local or a value merged from several producers does not.
  If the jump and fall-through share one successor, the guard supplies no edge-specific nullness fact.
- A successful `instanceof` test also qualifies when its operand resolves to one original parameter.
  The exact `Objects.isNull(Object)` and `Objects.nonNull(Object)` predicates qualify on their non-null outcome.
  Copies saved in ordinary Boolean locals qualify, while ambiguous or computed Boolean values do not.
  Opposite predicates merged into one Boolean do not qualify.
  A reassigned Boolean parameter remains unknown because a caller-supplied value can bypass the test.
- Guard facts are kept only on the edge proving non-nullness.
  A method with a normal path that accepts null therefore does not receive `NonNull`.
- The `Nullable` proof is intentionally narrower.
  A recognized `Objects` predicate and its saved Boolean copies can prepare the initial guard.
  Only an initial guard qualifies, and its null arm may prepare a constant or local return value but may not call, take a
  conditional branch, throw, or enter an exception handler before returning.
  Later checks, conditional null returns, and null arms containing cleanup or other executable behavior remain unknown.
- `invokevirtual`, `invokeinterface`, and non-constructor `invokespecial` calls qualify as non-null receiver evidence.
- Instance-field reads and writes, array length, primitive and reference array reads and writes, and monitor entry and exit
  qualify as direct dereferences.
  They prove the container non-null, without qualifying a field value, array element, or value being stored.
- The three Java 11 `Objects.requireNonNull(...)` overloads qualify as non-null evidence for their first argument.
  That intrinsic does not qualify message arguments, `requireNonNullElse(...)`, or `requireNonNullElseGet(...)`.
- Other calls, including constructors, propagate non-null parameter requirements proven from the helper's bytecode.
  Static, private, and final methods and methods declared on final classes qualify.
  A non-constructor `super` call qualifies only when its declaration is on the immediate superclass, including private calls
  allowed by nest access.
  Overridable virtual calls and inherited symbolic owners remain unknown; no declaration-contract or closed-package
  assumption is used for argument requirements.
- A helper argument must resolve to exactly one original caller parameter.
  Aliases, casts, reordered arguments, and repeated arguments qualify; reassigned locals and ambiguous producers do not.
  Receiver and argument evidence are independent, and nullable acceptance is not propagated through calls.
- Non-null evidence must occur on every path that returns normally, and the method must have at least one reachable normal
  return.
  A conditional call followed by a normal return and an always-throwing method therefore remain unknown.
- A call or direct dereference inside a region protected by an explicit catch of `NullPointerException`, `RuntimeException`,
  `Exception`, or `Throwable` does not qualify, even when the handler rethrows.
  The analysis deliberately does not turn a failure handled by such a catch into a parameter contract.
- Facts established by a call or direct dereference are carried only along its normal control-flow edge.
  Exception handlers receive the facts that were known before the throwing instruction.
  This is deliberately conservative for checked-exception handlers that return normally.
- A synthetic catch-all used for `finally` is not treated as an explicit NPE-capable catch.
  A normal `finally` path can retain the proof, while a handler path that returns without the successful operation cannot.
- Native calls such as `System.arraycopy(...)` supply no bytecode proof for their arguments.
- Abstract or native methods, unsupported bytecode, and methods outside the analysis budget remain unknown.
- Helper traversal has its own depth budget and stops at active recursion cycles.
  Calls in one root analysis also share a work budget for estimated frame and exception-handler work and for visiting
  local-check and helper-argument producers and their dependency edges, including edges to cached producers.
  Failed proofs also consume this allowance.
  Each admitted method computes its local checks once before traversing helpers.
  Those local checks finish even if they exhaust the allowance, so independent local facts survive the cutoff.
  Exhaustion skips further helper proofs and logs one warning for that root.
- Cached summaries preserve the remaining depth budget and consume the same work allowance as uncached proofs.
  Results that encounter a cycle or reach a depth or work cutoff are not reused.
  A cutoff can hide a cycle, so reusing its partial proof could change contracts with cache warmth.

For a type-variable parameter, the marker is placed on the parameter use as described in
[Generic parameter nullness](#generic-parameter-nullness).

Evidence at the same parameter position is combined in this order:

| Evidence | Current generated contract at that position |
|-|-|
| Explicit recognized `@Nullable` annotation | `0`, overriding bytecode and template evidence. |
| Explicit recognized non-null annotation | `1`, overriding bytecode and template evidence. |
| No explicit annotation and a bytecode proof | The inferred `0` or `1`. |
| No explicit annotation and incomplete bytecode proof | No bytecode marker; another local heuristic or template can still supply the position. |
| Template marker and a bytecode proof | The local inferred value replaces the template value at that position. |

The resulting current value then follows the positional ownership rules under
[Generator update rules](#generator-update-rules):

| Stored position | Current bytecode result | `generate` | `generate-additive` |
|-|-|-|-|
| no marker | inferred `0` or `1` | Add the inferred value as generated-owned. | Add the inferred value as generated-owned. |
| manual marker | same inferred value | Keep the value and record generated ownership. | Keep the value and record generated ownership. |
| manual marker | opposite inferred value | Replace it with the inferred generated-owned value. | Preserve the manual value. |
| generated-owned marker | same inferred value | Keep the generated-owned value. | Keep the generated-owned value. |
| generated-owned marker | opposite inferred value | Replace it with the inferred generated-owned value. | Preserve the stored generated-owned value. |
| manual `0` or `1` | unknown | Preserve the manual marker. | Preserve the manual marker. |
| generated-owned `0` or `1` | unknown | Remove the marker and its ownership. | Preserve the marker and its ownership. |
| complete member has local `@Keep` or confirmed `@Imported` | inferred value or unknown | Preserve the complete stored member. | Preserve the complete stored member. |

Explicit parameter annotations are part of the current generated contract, so an explicit `0` or `1` uses the same-value
and opposite-value rows above.
Local `@Keep` is needed only for an intentional disagreement with current evidence or to protect the complete member; it is
not needed when a manual marker merely fills a position for which analysis is unknown.

In a version chain, each version is analyzed independently.
If a predecessor's bytecode-derived marker is generated-owned but the successor's changed bytecode makes that parameter
unknown, full generation withdraws the marker instead of propagating stale generated evidence.
Additive generation retains it, and a manual predecessor marker survives either mode while the successor is silent.

### Generic parameter nullness

When a generator heuristic establishes only that a parameter value must be non-null, the nullness marker is placed on
the parameter use. This remains true when the parameter is the method's only type variable. For example:

```
# <T extends EventListener> void addListener(T listener)
<T::Ljava/util/EventListener;>(TT;)V
<T::Ljava/util/EventListener;>(T1T;)V
```

The generator deliberately does not produce `<1T::Ljava/util/EventListener;>(TT;)V` (a declaration marker) or
`<T::L1java/util/EventListener;>(TT;)V` (a bound marker). Both forms constrain which nullness-qualified types may
substitute for `T`; they can reject an explicitly `@Nullable` type argument even when the value passed to `listener` is
proven non-null. A parameter heuristic does not provide evidence for that broader generic constraint.

### Static-final field contracts

A static-final reference field is marked non-null only when a recognized annotation declares it non-null or bytecode
analysis proves that every normal completion of the class initializer assigns a non-null value.
Finality alone is not evidence because an initializer such as `System.getProperty(...)` can return `null`.
When initializer analysis is unsupported or inconclusive, the field remains unspecified rather than being marked nullable.
Standard lambda and string-concatenation factories supply non-null initializer values under the same bootstrap checks used
for return inference.
Primitive-wrapper `valueOf` factories and all Java 11 `List.of(...)` overloads use the same exact call contracts as return
inference.
A non-null factory result does not prove the final field value when another normal path assigns null, including a caught
factory failure.

This proof models the field after successful class initialization, matching ordinary source-level nullness contracts.
Code reached recursively while `<clinit>` is still running can observe the JVM default `null` before assignment; that
initialization window is outside the generated field contract.
Explicit recognized nullable and non-null annotations remain authoritative.

Full generation preserves a manual stored field marker when current analysis is silent and withdraws it when its gap is
listed in `@Generated(...)`.
When changing a field heuristic, review these ownership-based removals with a
[clean-generation comparison](#clean-generation-comparison).

### PolyNull return contracts

`@Generated(PolyNull)` records that bytecode analysis found a return value whose nullness depends on an input value. The EEA format
cannot express that dependency. The generator therefore leaves the top-level return type unqualified instead of marking
it nullable or non-null:

```
# boolean[] toPrimitive(Boolean[] array, boolean valueForNull)
([Ljava/lang/Boolean;Z)[Z
([Ljava/lang/Boolean;Z)[Z # @Generated(PolyNull)
```

Bytecode analysis recognizes exact forwarding, such as `Object identity(Object value) { return value; }`, without
requiring a separate branch that returns `null`.
Every reachable normal return must forward the same original reference parameter; local aliases and casts preserve this
identity.
A proven non-null return, such as forwarding an argument after rejecting `null`, retains its stronger non-null contract.

Complete helper summaries can carry the same parameter dependencies through calls within or across classes.
A complete dependency on one caller parameter does not need a separate local null-return branch.
Competing parameter dependencies still need evidence of a null return or an independent non-null alternative.
Loads from reassigned entry-parameter slots remain unknown when the original value and a replacement merge ambiguously,
including when that value is passed through an identity helper.

Bytecode analysis also recognizes guarded fallback control flow.
When one return alternative depends on an input and every other reachable alternative is proven non-null, the result can
be null only when that input is null.
This covers implementations such as `ConcurrentHashMap.getOrDefault`, which returns either a retrieved value proven
non-null by its guard or the supplied default value.

This missing return marker is intentional evidence, not an unknown position. During `generate`, current PolyNull evidence removes an
existing or inherited `0`/`1` marker from the top-level return type. Markers on parameters, array components, and generic
type arguments remain unchanged because they describe independent contracts.

A standalone manual `@PolyNull` survives current silence.
Generated `@Generated(PolyNull)` is withdrawn by full generation when current analysis becomes silent, while additive generation
retains it.
The exact preservation, conflict, and ownership cases are listed under
[Generator update rules](#generator-update-rules).

## Clean-generation comparison

To see which contracts the current generator can produce without using the artifact's existing EEA sources as input, write a
fresh result to a temporary directory:

```bash
mvn generate-resources -Deea-generator.action=generate -Deea-generator.input.dirs= -Deea-generator.output.dir=<ABSOLUTE_TEMP_DIR>
```

The empty `eea-generator.input.dirs` Maven property removes the standard source input configured by the sample POM.
The absolute temporary output path keeps the comparison non-destructive; `src/main/resources` is not updated.
Configured `input.dirs.extra` entries remain enabled because the sample launcher reads them from
`eea-generator.properties`.
The comparison therefore excludes the current artifact's stored EEA sources while retaining contracts intentionally
propagated from a predecessor or related artifact.
An upstream protected contract appears as `@Imported`; a stale `@Imported` that exists only in the excluded current artifact
does not appear.

Compare the temporary EEA tree with `src/main/resources`.
A stored marker absent from the temporary result is not currently established by the generator itself.
Review such differences individually.
To retain an intentional manual value during generator silence, remove its gap from `@Generated(...)`.
Add `@Keep` when that manual value conflicts with current evidence or the complete member must be protected.

## Configuration reference

| Name | Description | Default |
|---|---|---|
| `packages.include` | Comma-separated packages to scan recursively. Required for direct invocation of every action except `minimize`. | n/a |
| `classes.exclude` | Comma-separated regular expressions matched against fully qualified class names to exclude them. | n/a |
| `action` | One of `validate`, `generate`, `generate-additive`, or `minimize`. Required for direct invocation. | n/a |
| `output.dir` | Root directory containing or receiving EEA files. Required unless the launcher supplies `output.dir.default`. | n/a |
| `output.dir.default` | Fallback output root used only when `output.dir` is not set. Launchers can use this without overriding an explicit properties-file output. | n/a |
| `input.dirs` | Comma-separated EEA root directories read during generation or minimization. During generation, the first entry is local and later entries are upstream. Direct `minimize` requires at least one existing effective input directory. | n/a |
| `input.dirs.extra` | Comma-separated upstream EEA roots appended to `input.dirs`, normally immediate predecessor sources in a version chain. | n/a |
| `deleteIfEmpty` | Delete or skip generated files with no remaining members after rendering. Minimization always deletes empty output. | `true` |
| `omitClassMembersWithoutNullAnnotation` | Omit generated declarations without null markers unless they contain source-only contract metadata. Minimization always omits them. | `false` |
| `omitRedundantAnnotatedSignatures` | Omit redundant generated annotated-signature lines unless they contain source-only contract metadata. Minimization always omits them. | `false` |

`minimize` always writes compact artifacts.
It omits source comments, members without null annotations, and redundant annotated-signature lines, and it deletes empty
output.
A member retained only by source ownership or relationship metadata can disappear after those comments are removed.
The generation rendering options do not weaken this fixed minimization policy.

The sample Maven launcher forwards `action`, `input.dirs`, and `output.dir` as JVM system properties.
Set their Maven-side values with `-Deea-generator.action=...`, `-Deea-generator.input.dirs=...`, and
`-Deea-generator.output.dir=...`.
These forwarded values override options with the same names in `eea-generator.properties`.

Configure `input.dirs.extra` in `eea-generator.properties` when using the sample POM.
Passing `-Deea-generator.input.dirs.extra=...` to Maven does not forward it because the sample source execution does not map
that Maven property.
Relative paths in the properties file resolve against the directory containing that file.
See [Versioned artifact propagation](#versioned-artifact-propagation) and
[Layered input directory precedence](#layered-input-directory-precedence).

When invoking `EEAGenerator` directly, every option can instead be a JVM system property prefixed with `eea-generator.`,
for example `-Deea-generator.omitRedundantAnnotatedSignatures=true`.
A JVM system property overrides the corresponding properties-file value.
Relative paths supplied through JVM properties resolve against the process working directory.

The automatic packaging execution intentionally clears `input.dirs.extra` and minimizes only the completed
`src/main/resources` tree.
A direct `minimize` invocation still honors `input.dirs.extra` when several source trees should be merged without first
materializing their combined result.
