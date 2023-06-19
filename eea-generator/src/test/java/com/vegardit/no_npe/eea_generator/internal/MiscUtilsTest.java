/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;
import static org.assertj.core.api.Assertions.*;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
class MiscUtilsTest {

   @Test
   void testReplaceAll() {
      assertThat(replaceAll("", Pattern.compile("o"), 0, w -> "a")).isEmpty();
      assertThat(replaceAll("o", Pattern.compile("o"), 0, w -> "a")).isEqualTo("a");
      assertThat(replaceAll("ooo", Pattern.compile("o"), 0, w -> "a")).isEqualTo("aaa");
      assertThat(replaceAll("oX", Pattern.compile("o"), 0, w -> "a")).isEqualTo("aX");
      assertThat(replaceAll("oooX", Pattern.compile("o"), 0, w -> "a")).isEqualTo("aaaX");
      assertThat(replaceAll("Xo", Pattern.compile("o"), 0, w -> "a")).isEqualTo("Xa");
      assertThat(replaceAll("Xooo", Pattern.compile("o"), 0, w -> "a")).isEqualTo("Xaaa");
      assertThat(replaceAll("aXcaXc", Pattern.compile("a(X)c"), 1, w -> "b")).isEqualTo("abcabc");
   }
}
