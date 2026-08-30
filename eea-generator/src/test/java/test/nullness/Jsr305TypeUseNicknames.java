/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package test.nullness;

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.annotation.Nonnull;
import javax.annotation.meta.TypeQualifierNickname;
import javax.annotation.meta.When;

/**
 * Test fixture supplying nullable and non-null JSR-305 type-use nicknames whose declarations are outside the generator's
 * accepted package scan.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public final class Jsr305TypeUseNicknames {

   @Nonnull
   @TypeQualifierNickname
   @Retention(RUNTIME)
   @Target(TYPE_USE)
   public @interface NonNull {
   }

   @Nonnull(when = When.MAYBE)
   @TypeQualifierNickname
   @Retention(RUNTIME)
   @Target(TYPE_USE)
   public @interface Nullable {
   }

   private Jsr305TypeUseNicknames() {
   }
}
