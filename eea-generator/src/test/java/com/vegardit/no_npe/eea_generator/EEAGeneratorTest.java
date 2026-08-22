/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
@SuppressWarnings("null")
class EEAGeneratorTest {

   public static class HiddenFieldParent {
      public String value = "";
   }

   @SuppressWarnings("hiding") // Intentional fixture for verifying that hidden fields do not inherit annotations.
   public static final class HiddenFieldChild extends HiddenFieldParent {
      public String value = "";
   }

   public static final class ComparableType implements Comparable<String> {

      @Override
      public int compareTo(final String other) {
         return other.length();
      }

      public int unrelated(final String value) {
         return value.length();
      }
   }

   @Test
   void testValidateValidEEAFiles() throws IOException {
      final var rootPath = Path.of("src/test/resources/valid");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      assertThat(EEAGenerator.validateEEAFiles(config)).isEqualTo(2);
   }

   @Test
   void testAnnotationMerge() throws IOException {
      final var eeaFiles = EEAGenerator.computeEEAFiles("com.vegardit.no_npe.eea_generator", classInfo -> classInfo.getName().equals(
         "com.vegardit.no_npe.eea_generator.EEAFileTest$TestEntity"));
      assertThat(eeaFiles).hasSize(1);
      final var computedEeaFile = eeaFiles.values().iterator().next();
      final var existingEeaFile = EEAFile.load(Path.of(
         "src/test/resources/valid/com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity.eea"));

      assertThat(computedEeaFile.renderFileContent(Set.of(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE))).isEqualTo(List.of(
         "class com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity", //
         "", //
         "STATIC_STRING", //
         " Ljava/lang/String;", //
         " L1java/lang/String;", //
         "", //
         "keepTest1", //
         " ()Ljava/lang/String;", //
         " ()L0java/lang/String;" //
      ));

      final var keepTestMethodComputed = computedEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst().get();
      final var keepTestMethodExisting = existingEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst().get();
      assertThat(keepTestMethodComputed.annotatedSignature.value).contains("L0java/lang/String");
      assertThat(keepTestMethodExisting.annotatedSignature.value).contains("Ljava/lang/String");
      assertThat(keepTestMethodExisting.annotatedSignature.comment).contains("@Keep");
      assertThat(keepTestMethodComputed.annotatedSignature.value).isNotEqualTo(keepTestMethodExisting.annotatedSignature.value);
      assertThat(keepTestMethodComputed.annotatedSignature.comment).isNotEqualTo(keepTestMethodExisting.annotatedSignature.comment);

      computedEeaFile.applyAnnotationsAndCommentsFrom(existingEeaFile, true, false);
      final var keepTestMethodComputedUpdated = computedEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst()
         .get();
      assertThat(keepTestMethodComputedUpdated.annotatedSignature.value).contains("Ljava/lang/String");
      assertThat(keepTestMethodComputedUpdated.annotatedSignature.comment).contains("@Keep");

      assertThat(computedEeaFile.renderFileContent(Set.of(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE))).isEqualTo(List.of(
         "class com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity # a class comment", //
         "", //
         "STATIC_STRING", //
         " Ljava/lang/String;", //
         " L1java/lang/String; # an annotated signature comment", //
         "", //
         "name", //
         " Ljava/lang/String;", //
         " L1java/lang/String;", //
         "", //
         "keepTest1", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing generator from changing it to L0", //
         "keepTest2", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing removal on minimization" //
      ));
   }

   @Test
   void testComparableHeuristicOnlyAnnotatesCompareTo() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ComparableType.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      final var compareTo = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("compareTo")).findFirst()
         .orElseThrow();
      final var unrelated = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("unrelated")).findFirst()
         .orElseThrow();

      assertThat(compareTo.annotatedSignature.value).contains("L1java/lang/String;");
      assertThat(unrelated.annotatedSignature.value).isEqualTo(unrelated.originalSignature.value);
   }

   @Test
   void testGenerateDoesNotInheritAnnotationsOntoHiddenFields(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");

      final var parentEEAFile = new EEAFile(HiddenFieldParent.class.getName());
      final var parentField = parentEEAFile.addMember("value", "Ljava/lang/String;");
      parentField.annotatedSignature.value = "L1java/lang/String;";
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(HiddenFieldParent.class.getName()) || classInfo.getName().equals(
         HiddenFieldChild.class.getName());
      config.deleteIfEmpty = false;

      EEAGenerator.generateEEAFiles(config);

      final var childEEAFile = EEAFile.load(outputDir, HiddenFieldChild.class.getName());
      final var childField = childEEAFile.findMatchingClassMember("value", "Ljava/lang/String;");
      assertThat(childField).isNotNull();
      assert childField != null;
      assertThat(childField.annotatedSignature.value).isEqualTo(childField.originalSignature.value);
      assertThat(childField.annotatedSignature.comment).doesNotContain("@Inherited");
   }

   @Test
   void testMainAcceptsBarePropertiesFilePath(@TempDir final Path tempDir) throws Exception {
      final Path inputDir = Files.createDirectory(tempDir.resolve("input"));
      final Path propertiesFile = Files.createTempFile(Path.of("."), "eea-generator-test-", ".properties");
      final var properties = new Properties();
      properties.setProperty(EEAGenerator.PROPERTY_ACTION, "minimize");
      properties.setProperty(EEAGenerator.PROPERTY_INPUT_DIRS, inputDir.toString());
      properties.setProperty(EEAGenerator.PROPERTY_OUTPUT_DIR, "target/" + propertiesFile.getFileName() + "-output");
      try (var writer = Files.newBufferedWriter(propertiesFile)) {
         properties.store(writer, null);
      }

      try {
         assertThatCode(() -> EEAGenerator.main(propertiesFile.getFileName().toString())).doesNotThrowAnyException();
      } finally {
         Files.deleteIfExists(propertiesFile);
      }
   }

   @Test
   void testInputDirsExtraIsRelativeToItsPropertiesFile(@TempDir final Path tempDir) throws Exception {
      final Path configDir = Files.createDirectory(tempDir.resolve("config"));
      final Path inputDir = Files.createDirectory(configDir.resolve("input"));
      final Path outputDir = configDir.resolve("output");
      final Path propertiesFile = configDir.resolve("eea-generator.properties");

      final var inputEEAFile = new EEAFile("test.Type");
      final var inputField = inputEEAFile.addMember("value", "Ljava/lang/String;");
      inputField.annotatedSignature.value = "L1java/lang/String;";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var properties = new Properties();
      properties.setProperty(EEAGenerator.PROPERTY_ACTION, "minimize");
      properties.setProperty(EEAGenerator.PROPERTY_INPUT_DIRS_EXTRA, "input");
      properties.setProperty(EEAGenerator.PROPERTY_OUTPUT_DIR, "output");
      try (var writer = Files.newBufferedWriter(propertiesFile)) {
         properties.store(writer, null);
      }

      EEAGenerator.main(propertiesFile.toString());

      assertThat(outputDir.resolve(inputEEAFile.relativePath)).exists();
   }

   @Test
   void testMinimizeRejectsMissingInputDirsWithoutDeletingOutput(@TempDir final Path tempDir) throws IOException {
      final Path outputDir = Files.createDirectory(tempDir.resolve("output"));
      final Path existingOutput = Files.createFile(outputDir.resolve("existing.eea"));
      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(tempDir.resolve("missing"));

      assertThatThrownBy(() -> EEAGenerator.minimizeEEAFiles(config)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessage("None of the specified input.dirs exist!");
      assertThat(existingOutput).exists();
   }

   @Test
   void testMinimizeTreatsExistingEmptyInputDirAsEmptySource(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = Files.createDirectory(tempDir.resolve("input"));
      final Path outputDir = Files.createDirectory(tempDir.resolve("output"));
      final Path existingOutput = Files.createFile(outputDir.resolve("existing.eea"));
      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(inputDir);

      assertThat(EEAGenerator.minimizeEEAFiles(config)).isOne();
      assertThat(existingOutput).doesNotExist();
   }

   @Test
   void testPackageMissingOnClasspath() {
      final var rootPath = Path.of("src/test/resources/valid");
      final var config = new EEAGenerator.Config(rootPath, "org.no_npe.foobar");

      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessage("No classes found for package [org.no_npe.foobar] on classpath");
   }

   @Test
   void testTypeMissingOnClasspath() {
      final var rootPath = Path.of("src/test/resources/nonexistant_type");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalStateException.class) //
         .hasMessageMatching("Type .*NonexistantType.* defined in .* not found on classpath.");
   }
}
