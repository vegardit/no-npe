/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import org.eclipse.jdt.annotation.Nullable;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public final class Either<Left, Right> {

   public static <L, R> Either<L, R> left(final L left) {
      return new Either<>(left, null);
   }

   public static <L, R> Either<L, R> right(final R right) {
      return new Either<>(null, right);
   }

   private @Nullable Left left;

   private @Nullable Right right;

   private Either(@Nullable final Left left, @Nullable final Right right) {
      this.left = left;
      this.right = right;
   }

   @SuppressWarnings("unchecked")
   public <T> T get() {
      return isLeft() ? (T) left : (T) right;
   }

   public boolean isLeft() {
      return left != null;
   }

   public boolean isRight() {
      return right != null;
   }
}
