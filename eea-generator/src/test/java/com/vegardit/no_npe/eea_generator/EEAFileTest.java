/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.vegardit.no_npe.eea_generator.EEAFile.ClassMember;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class EEAFileTest {

   public static final class TestEntity {
      public static final String STATIC_STRING = "MyStaticString";

      public String name;

      public TestEntity(final String name) {
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
      final var eeaFile = new EEAFile(TestEntity.class.getName());
      eeaFile.load(Path.of("src/test/resources/valid"));
      assertThat(eeaFile.className).isEqualTo(TestEntity.class.getName());
      assertThat(eeaFile.relativePath).isEqualTo(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));
      assertThat(eeaFile.getClassMembers()).isNotEmpty();
      assertThat(eeaFile.containsMember(new ClassMember("STATIC_STRING", "Ljava/lang/String;"))).isTrue();
      assertThat(eeaFile.getAnnotatedSignature(new ClassMember("STATIC_STRING", "Ljava/lang/String;"))).isEqualTo("L1java/lang/String;");
   }

   @Test
   void testWrongTypeHeader() {
      final var eeaFile = new EEAFile(WRONG_TYPE_NAME);
      assertThatThrownBy(() -> eeaFile.load(Path.of("src/test/resources/invalid"))) //
         .isInstanceOf(java.io.IOException.class).hasMessage("mismatching class name in annotation file, expected "
            + WRONG_TYPE_NAME_WITH_SLASHES + ", but header said " + TEST_ENTITY_NAME_WITH_SLASHES);
   }

   @Test
   void testLoadAnnotations() throws IOException {
      final var eeaFiles = EEAGenerator.computeEEAFiles(EEAFileTest.class.getPackageName(), null);
      final var eeaFile = eeaFiles.get(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));
      assertThat(eeaFile).isNotNull();

      final var entry = new EEAFile.ClassMember("name", "Ljava/lang/String;");
      assertThat(eeaFile.getClassMembers()).contains(entry);
      assertThat(eeaFile.getAnnotatedSignature(entry)).isNull();

      eeaFile.load(Path.of("src/test/resources/valid"));
      assertThat(eeaFile.getAnnotatedSignature(entry)).isEqualTo("L1java/lang/String;");
   }
}
