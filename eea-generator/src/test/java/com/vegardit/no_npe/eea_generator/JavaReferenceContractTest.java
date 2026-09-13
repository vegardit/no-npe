/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;

/**
 * Verifies that every Java EEA module keeps cleared reference reads nullable through generation, packaging, and ECJ
 * analysis.
 *
 * @author Vegard IT GmbH (https://vegardit.com)
 */
@SuppressWarnings("null")
class JavaReferenceContractTest {

   private static final List<Integer> JAVA_VERSIONS = List.of(11, 17, 21, 25);
   private static final List<String> REFERENCE_TYPES = List.of("Reference", "SoftReference", "WeakReference");

   private static Path sourceAnnotations(final int version) {
      return Path.of(System.getProperty("basedir", "."), "..", "libs", "eea-java-" + version, "src", "main", "resources").normalize();
   }

   private static void assertNullableGet(final Path annotations, final String type) throws Exception {
      final var member = EEAFile.load(annotations, "java.lang.ref." + type).findMatchingClassMember("get", "()TT;");
      assertThat(member).as("%s in %s", type, annotations).isNotNull();
      assert member != null;
      assertThat(member.annotatedSignature.value).as("%s in %s", type, annotations).isEqualTo("()T0T;");
   }

   private static void assertCompilation(final Path annotations, final Path source, final boolean expectedSuccess) throws Exception {
      // Isolate EEA lookup from the installed aggregate dependency so a released contract cannot hide a source
      // regression.
      final Path annotationJar = Path.of(NonNull.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      final Path compilerProperties = Path.of(System.getProperty("basedir", "."), ".settings", "org.eclipse.jdt.core.prefs");
      final var output = new StringWriter();
      final String[] arguments = { //
         "-properties", compilerProperties.toAbsolutePath().toString(), "-source", "11", "-target", "11", "-proc:none", //
         "-classpath", annotations + File.pathSeparator + annotationJar, "-annotationpath", "CLASSPATH", //
         "-d", "none", source.toString() //
      };
      final boolean compiled = BatchCompiler.compile(arguments, new PrintWriter(output), new PrintWriter(output), null);
      assertThat(compiled).as("%s using %s:%n%s", source, annotations, output).isEqualTo(expectedSuccess);
      if (expectedSuccess) {
         // Redundant-null-check and legacy-library warnings would also make the contract unsafe for non-null type
         // arguments.
         assertThat(output.toString()).as("%s using %s", source, annotations).isEmpty();
      } else {
         assertThat(output.toString()).contains("Null type mismatch", "@NonNull String", "@Nullable String");
      }
   }

   @Test
   void testEcjAcceptsNullableImplementationsAndCheckedReads(@TempDir final Path tempDir) throws Exception {
      final Path source = tempDir.resolve("ReferenceConsumer.java");
      Files.writeString(source, String.join(System.lineSeparator(), //
         "import java.lang.ref.*;", //
         "import org.eclipse.jdt.annotation.*;", //
         "@NonNullByDefault", // Includes the type arguments of the checked Reference<String> reads.
         "final class ReferenceConsumer {", //
         "  interface ValueReference<K, V> { @Nullable V get(); }", //
         "  static final class SoftValueReference<K, V> extends SoftReference<V> implements ValueReference<K, V> {", //
         "    SoftValueReference(V value) { super(value); }", //
         "  }", //
         "  static final class WeakValueReference<K, V> extends WeakReference<V> implements ValueReference<K, V> {", //
         "    WeakValueReference(V value) { super(value); }", //
         "  }", //
         "  static int referenceLength(Reference<String> reference) {", //
         "    final var value = reference.get();", //
         "    return value == null ? 0 : value.length();", //
         "  }", //
         "  static int softReferenceLength(SoftReference<String> reference) {", //
         "    final var value = reference.get();", //
         "    return value == null ? 0 : value.length();", //
         "  }", //
         "  static int weakReferenceLength(WeakReference<String> reference) {", //
         "    final var value = reference.get();", //
         "    return value == null ? 0 : value.length();", //
         "  }", //
         "}"));

      for (final int version : JAVA_VERSIONS) {
         final Path annotations = sourceAnnotations(version);
         assertCompilation(annotations, source, true);

         final Path input = tempDir.resolve(version + "/input");
         for (final String type : REFERENCE_TYPES) {
            EEAFile.load(annotations, "java.lang.ref." + type).save(input, SaveOption.REPLACE_EXISTING);
         }
         final var config = new EEAGenerator.Config(tempDir.resolve(version + "/minimized"));
         config.inputDirs.add(input);
         EEAGenerator.minimizeEEAFiles(config);

         assertNullableGet(config.outputDir, "Reference");
         assertNullableGet(config.outputDir, "SoftReference");
         assertCompilation(config.outputDir, source, true);
      }
   }

   @Test
   void testEcjRejectsUncheckedReadsOfNonNullReferents(@TempDir final Path tempDir) throws Exception {
      // A non-null type argument does not stop clearing. This control must fail even if both get() contracts become
      // unqualified.
      for (final String type : REFERENCE_TYPES) {
         final Path source = tempDir.resolve(type + "Unsafe.java");
         Files.writeString(source, String.join(System.lineSeparator(), //
            "import java.lang.ref." + type + ";", //
            "import org.eclipse.jdt.annotation.NonNull;", //
            "final class " + type + "Unsafe {", //
            "  static @NonNull String unchecked(@NonNull " + type + "<@NonNull String> reference) {", //
            "    return reference.get();", //
            "  }", //
            "}"));
         for (final int version : JAVA_VERSIONS) {
            assertCompilation(sourceAnnotations(version), source, false);
         }
      }
   }

   @Test
   void testGenerationPreservesNullableReferenceContracts(@TempDir final Path tempDir) throws Exception {
      for (int versionIndex = 0; versionIndex < JAVA_VERSIONS.size(); versionIndex++) {
         final int version = JAVA_VERSIONS.get(versionIndex);
         for (final var mode : EEAGenerator.GenerationMode.values()) {
            final var config = new EEAGenerator.Config(tempDir.resolve(version + "/" + mode), "java.lang.ref");
            config.classFilter = classInfo -> REFERENCE_TYPES.stream().anyMatch(type -> classInfo.getName().equals("java.lang.ref."
                  + type));
            config.generationMode = mode;
            config.inputDirs.add(sourceAnnotations(version));
            if (versionIndex > 0) {
               config.inputDirsExtra.add(sourceAnnotations(JAVA_VERSIONS.get(versionIndex - 1)));
            }

            EEAGenerator.generateEEAFiles(config);

            assertNullableGet(config.outputDir, "Reference");
            assertNullableGet(config.outputDir, "SoftReference");
            final var parentGet = EEAFile.load(config.outputDir, "java.lang.ref.Reference").findMatchingClassMember("get", "()TT;");
            assert parentGet != null;
            assertThat(EEAGenerator.generateEEAFiles(config)).as("Java %s, %s", version, mode).isZero();
         }
      }
   }
}
