/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
@SuppressWarnings("null")
class EEAGeneratorTest {

   @Test
   void testVadilateValidEEAFiles() throws IOException {
      final var rootPath = Path.of("src/test/resources/valid");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      assertThat(EEAGenerator.validateEEAFiles(config)).isEqualTo(2);
   }

   @Test
   void testVadilateInvalidEEAFiles() {
      final var rootPath = Path.of("src/test/resources/invalid");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      final var wrongTypePath = rootPath.resolve(EEAFileTest.WRONG_TYPE_NAME_WITH_SLASHES + ".eea");
      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalStateException.class) //
         .hasMessage("Type [com.vegardit.no_npe.eea_generator.EEAFileTest$WrongType] defined in [" + wrongTypePath
               + "] no found on classpath.");
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
         "STATIC_STRING # a field comment", //
         " Ljava/lang/String; # an original signature comment", //
         " L1java/lang/String; # an annotated signature comment", //
         "", //
         "name # a field comment", //
         " Ljava/lang/String;", //
         " L1java/lang/String;", //
         "", //
         "keepTest1 # a metod comment", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing generator from changing it to L0", //
         "keepTest2", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing removal on minimization" //
      ));
   }

   @Test
   void testPackageMissingOnClasspath() {
      final var rootPath = Path.of("src/test/resources/invalid");
      final var config = new EEAGenerator.Config(rootPath, "org.no_npe.foobar");
      config.inputDirs.add(rootPath);

      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessage("No classes found for package [org.no_npe.foobar] on classpath");
   }
}
