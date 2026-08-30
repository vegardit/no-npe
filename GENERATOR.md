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

- `validate` checks stored EEA files, including ownership marker syntax, against the scanned classes without updating them.
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
| any form with `@Keep` | `@Keep` protects the complete member from generator updates, regardless of ownership metadata. |

### Manual evidence and protection

| Marker&nbsp;form | Meaning |
|-|-|
| `@PolyNull` | Manually maintained PolyNull evidence when no bare generator or relationship marker claims the complete contract and no `PolyNull` token claims it explicitly. It survives generator silence, but current conflicting evidence can replace it during `generate`. |
| `@Keep` | Protects the complete manually maintained member from later input sources and generator updates. Use it when a manual value intentionally disagrees with current evidence, or when the member must remain although it is absent from the scanned library version. |

PolyNull means that a method returns `null` only when a particular input is `null`.
The EEA format cannot express that dependency directly, so the top-level return remains unqualified and the source comment
preserves the evidence.
Use standalone `@PolyNull` for manual evidence and `@Generated(PolyNull)` for generator-produced evidence.

Place `@Generated`, `@PolyNull`, `@Inherited(...)`, and `@Overrides(...)` in the comment on the annotated-signature line,
which is the third line of a member entry.
ECJ does not support comments after the member name or raw signature, so the generator rejects files that place them there.
Place `@Keep` on the annotated-signature line as well.
It still protects the complete member rather than an individual position.

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

Propagation observes ownership.
A successor's earlier input values win conflicts, while its predecessor can fill positions that are still unqualified.
A manual predecessor position survives when analysis of the successor version is silent.
A generated-owned predecessor position is withdrawn by `generate` when analysis of the successor version is silent, so it is
not propagated to the next version.
`generate-additive` retains that generated-owned position and its ownership instead.

## Layered input directory precedence

The generator reads `input.dirs` first, followed by `input.dirs.extra`.
Directory order has different effects depending on why an EEA file is loaded:

| Operation | Precedence rule |
|-|-|
| Generate a class selected by `packages.include` | At each nullness position, the first explicit value wins and later inputs fill only unqualified positions. The first concrete return or PolyNull contract wins their shared return meaning. The earliest contributing input supplies the retained explanatory comment. `@Keep` from any contributing input protects the complete merged member. |
| Load an ancestor only for inheritance | The first directory containing that ancestor's EEA file supplies the complete file. Later copies of the same ancestor are not layered. |
| Minimize multiple source trees | The first explicit member contract wins. Later inputs fill missing files, members, or contracts but do not replace an existing explicit contract. A metadata-only PolyNull contract can accept later markers at independent positions, but its unqualified top-level return remains authoritative. |

A member contributes stored input when its annotated signature contains `0` or `1` or has an annotated-signature comment.
This includes source-only `@Generated`, `@PolyNull`, `@Inherited(...)`, `@Overrides(...)`, and `@Keep` metadata.
When several selected-class inputs contribute to the same member, layering combines independent nullness positions before the
generator update rules run.
Ownership follows each accepted position, so a generated-owned predecessor position remains generated-owned while an unmarked
position remains manual.
Conflicting later values and later explanatory text do not replace the earlier input.

`input.dirs.extra` is therefore a fallback for selected-class conflicts, ancestor lookup, and minimization, but it can add
missing selected-class positions.
The configured version chains intentionally rely on selected-class layering to feed the predecessor contract into the
successor's update rules.
For other uses, review overlapping files and put a directory earlier when its ancestor or minimized contracts must take
precedence.

## Generator update rules

`generate` reconciles each raw-signature position independently.
Current evidence wins at its exact position.
Current silence withdraws only evidence recorded as generated-owned; manual evidence survives.

`generate-additive` accepts compatible additions and matching evidence but never removes or changes a stored nullness value.
On a conflict it preserves the stored value and its existing ownership, while still accepting independent compatible
positions.
Generated-owned evidence also remains generated-owned during additive silence so a later full run can withdraw it.

`@Keep` bypasses all reconciliation rules below and preserves the complete stored member in both modes, regardless of
current evidence.
Without `@Keep`, the positional cases are:

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

Inheritance is evaluated against the current parent-plus-local contract and then reconciled by the same positional and
PolyNull rules:

| Current relationship state | Result |
|-|-|
| Named parent EEA is available | Recompute the parent-plus-local evidence, reconcile each stored position, and recalculate `@Inherited(...)` or `@Overrides(...)`. |
| Named parent is still an ancestor but its EEA is unavailable | Preserve the complete stored relationship because missing data is unknown, not an empty parent contract. |
| Named parent is no longer applicable and another annotated ancestor is available | Rebase to the most specific applicable ancestor, reconcile its contract with current local evidence, and write the new relationship marker. |
| Named parent EEA is available but has no applicable contract | Remove the stale relationship and reconcile against current local evidence. In full mode, silent generated-owned positions are withdrawn while manual positions survive. |
| Multiple annotated interfaces in one inheritance chain | Use the most specific annotated subinterface; its transitive ancestors do not create a conflict. |
| Unrelated interfaces provide conflicting contracts | Select no inheritance base and reconcile a replaceable stored relationship against current local evidence. |

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
| any of the above plus `@Keep` | Preserve the complete member in both modes. |

The ownership metadata has this lifecycle:

| Action | Behavior |
|-|-|
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

### Receiver-call parameter contracts

For a reference parameter, the generator can infer `NonNull` when every reachable normal return requires a successful
instance-method call on that parameter value.
A successful receiver call proves that its receiver was not `null`; this inference never produces `Nullable`.
If the proof is incomplete, the parameter remains unknown unless another evidence source supplies a contract.

The proof has these boundaries:

- `invokevirtual`, `invokeinterface`, and non-constructor `invokespecial` calls qualify.
  Static calls and constructor calls do not.
- A local alias or cast of exactly one original parameter still qualifies.
  A reassigned local or a receiver merged from multiple possible values does not prove any original parameter.
- The call must occur on every path that returns normally, and the method must have at least one reachable normal return.
  A conditional call followed by a normal return and an always-throwing method therefore remain unknown.
- A call inside a region protected by an explicit catch of `NullPointerException`, `RuntimeException`, `Exception`, or
  `Throwable` does not qualify, even when the handler rethrows.
  The analysis deliberately does not turn a failure handled by such a catch into a parameter contract.
- Facts established by a call are carried only along its normal control-flow edge.
  Exception handlers receive the facts that were known before the throwing instruction.
  This is deliberately conservative for checked-exception handlers that return normally.
- A synthetic catch-all used for `finally` is not treated as an explicit NPE-capable catch.
  A normal `finally` path can retain the proof, while a handler path that returns without the successful call cannot.
- Calls such as `System.arraycopy(...)` do not qualify because the parameter is an argument, not the invoked receiver.
- Abstract or native methods, unsupported bytecode, and methods outside the analysis budget remain unknown.

The analysis is independent of return analysis, so it applies to methods with void, primitive, and reference returns.
For a type-variable parameter, the marker is placed on the parameter use as described in
[Generic parameter nullness](#generic-parameter-nullness).

Evidence at the same parameter position is combined in this order:

| Evidence | Current generated contract at that position |
|-|-|
| Explicit recognized `@Nullable` annotation, with or without a qualifying receiver call | `0` - the explicit declaration overrides the inferred `1`. |
| Explicit recognized non-null annotation | `1`. |
| No explicit annotation and a qualifying receiver-call proof | `1`. |
| No explicit annotation and incomplete receiver-call proof | No receiver-call marker; another local heuristic or template can still supply the position. |
| Template marker and a qualifying receiver-call proof | Local `1` replaces the template value at that position. |

The resulting current value then follows the positional ownership rules under
[Generator update rules](#generator-update-rules).
For receiver-call evidence specifically, the complete stored-state behavior is:

| Stored position | Current receiver-call result | `generate` | `generate-additive` |
|-|-|-|-|
| no marker | inferred `1` | Add `1` as generated-owned. | Add `1` as generated-owned. |
| manual `0` | inferred `1` | Replace it with generated-owned `1`. | Preserve manual `0`. |
| manual `1` | inferred `1` | Keep `1` and record generated ownership. | Keep `1` and record generated ownership. |
| generated-owned `0` | inferred `1` | Replace it with generated-owned `1`. | Preserve generated-owned `0`. |
| generated-owned `1` | inferred `1` | Keep generated-owned `1`. | Keep generated-owned `1`. |
| manual `0` or `1` | unknown | Preserve the manual marker. | Preserve the manual marker. |
| generated-owned `0` or `1` | unknown | Remove the marker and its ownership. | Preserve the marker and its ownership. |
| complete member has `@Keep` | inferred `1` or unknown | Preserve the complete stored member. | Preserve the complete stored member. |

Explicit parameter annotations are part of the current generated contract, so an explicit `0` or `1` uses the same-value
and opposite-value rows in the general positional rules.
`@Keep` is needed only for an intentional disagreement with current evidence or to protect the complete member; it is not
needed when a manual marker merely fills a position for which analysis is unknown.

In a version chain, each version is analyzed independently.
If a predecessor's receiver-call marker is generated-owned but the successor's changed bytecode makes that parameter
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
| `input.dirs` | Comma-separated EEA root directories read during generation or minimization. Direct `minimize` requires at least one existing directory. | n/a |
| `input.dirs.extra` | Comma-separated EEA roots appended to `input.dirs`, normally immediate predecessor sources in a version chain. | n/a |
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
