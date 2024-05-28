/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.normalizeNewLines;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
@SuppressWarnings("null")
class EEAFileTest {

   public static final class TestEntity {
      public static final String STATIC_STRING = "MyStaticString";

      public String name;

      public TestEntity(final String name) { // CHECKSTYLE:IGNORE RedundantModifier
         this.name = name;
      }
   }

   public static final class TestEntity2 {
      public static final String STATIC_STRING = "MyStaticString";
   }

   static final String TEST_ENTITY_NAME_WITH_SLASHES = TestEntity.class.getName().replace('.', '/');
   static final String WRONG_TYPE_NAME = EEAFileTest.class.getName() + "$WrongType";
   static final String WRONG_TYPE_NAME_WITH_SLASHES = WRONG_TYPE_NAME.replace('.', '/');

   @Test
   void testEEAFile() throws IOException {
      final var eeaFile = EEAFile.load(Path.of("src/test/resources/valid"), TestEntity.class.getName());

      assertThat(eeaFile.className.value).isEqualTo(TestEntity.class.getName());
      assertThat(eeaFile.className.comment).isEqualTo("# a class comment");
      assertThat(eeaFile.className).hasToString(TestEntity.class.getName() + " # a class comment");
      assertThat(eeaFile.relativePath).isEqualTo(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));

      assertThat(eeaFile.getClassMembers()).isNotEmpty();
      final var field = eeaFile.findMatchingClassMember("STATIC_STRING", "Ljava/lang/String;");
      assert field != null;
      assertThat(field.name.comment).isEqualTo("# a field comment");
      assertThat(field.originalSignature.value).isEqualTo("Ljava/lang/String;");
      assertThat(field.originalSignature.comment).isEqualTo("# an original signature comment");

      final var annotatedSignature = field.annotatedSignature;
      assertThat(annotatedSignature).isNotNull();
      assert annotatedSignature != null;

      assertThat(annotatedSignature.value).isEqualTo("L1java/lang/String;");
      assertThat(annotatedSignature.comment).isEqualTo("# an annotated signature comment");

      assertThat(normalizeNewLines(eeaFile.renderFileContent(false, false))) //
         .isEqualTo(normalizeNewLines(Files.readString(Path.of("src/test/resources/valid").resolve(eeaFile.relativePath))));

      assertThat(normalizeNewLines(eeaFile.renderFileContent(true, false))) //
         .isNotEqualTo(normalizeNewLines(Files.readString(Path.of("src/test/resources/valid").resolve(eeaFile.relativePath))));
   }

   @Test
   void testWrongTypeHeader() {
      final var wrongTypePath = Path.of("src/test/resources/invalid/" + WRONG_TYPE_NAME_WITH_SLASHES + ".eea");
      assertThatThrownBy(() -> { //
         EEAFile.load(wrongTypePath);
      }) //
         .isInstanceOf(java.io.IOException.class) //
         .hasMessage("Mismatch between file path of [" + wrongTypePath + "] and contained class name definition [" + TestEntity.class
            .getName() + "]");
   }

   @Test
   void testApplyAnnotationsAndCommentsFrom() throws IOException {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAFileTest.class.getPackageName(), c -> true);
      final var computedEEAFile = computedEEAFiles.get(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));
      assertThat(computedEEAFile).isNotNull();
      assert computedEEAFile != null;

      final var method = computedEEAFile.findMatchingClassMember("name", "Ljava/lang/String;");
      assert method != null;
      assertThat(method.annotatedSignature).isNull();
      assertThat(method.name.comment).isNull();

      final var loadedEEAFile = EEAFile.load(Path.of("src/test/resources/valid"), computedEEAFile.className.value);
      computedEEAFile.applyAnnotationsAndCommentsFrom(loadedEEAFile, false);
      final var annotatedSignature = method.annotatedSignature;
      assert annotatedSignature != null;
      assertThat(annotatedSignature.value).isEqualTo("L1java/lang/String;");
      assertThat(method.name.comment).isEqualTo("# a method comment");
   }
}
