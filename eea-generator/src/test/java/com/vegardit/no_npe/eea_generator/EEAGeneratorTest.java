/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
class EEAGeneratorTest {

   @Test
   void testVadilateValidEEAFiles() throws IOException {
      final var config = new EEAGenerator.Config();
      config.packages = new String[] {EEAGeneratorTest.class.getPackageName()};
      config.outputDir = Path.of("src/test/resources/valid");
      assertThat(EEAGenerator.validateEEAFiles(config)).isEqualTo(2);
   }

   @Test
   void testVadilateInvalidEEAFiles() {
      final var config = new EEAGenerator.Config();
      config.packages = new String[] {EEAGeneratorTest.class.getPackageName()};
      config.outputDir = Path.of("src/test/resources/invalid");
      assertThatThrownBy(() -> EEAGenerator.validateEEAFiles(config)) //
         .isInstanceOf(java.io.IOException.class).hasMessage("mismatching class name in annotation file, expected "
            + EEAFileTest.WRONG_TYPE_NAME_WITH_SLASHES + ", " + "but header said " + EEAFileTest.TEST_ENTITY_NAME_WITH_SLASHES);
   }

   @Test
   void testPackageMissingOnClasspath() {
      final var config = new EEAGenerator.Config();
      config.packages = new String[] {"org.no_npe.foobar"};
      config.outputDir = Path.of("src/test/resources/invalid");
      assertThatThrownBy(() -> EEAGenerator.validateEEAFiles(config)) //
         .isInstanceOf(IllegalArgumentException.class).hasMessage("No classes found for package [org.no_npe.foobar] on classpath");
   }
}
