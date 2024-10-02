/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static java.lang.annotation.ElementType.METHOD;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
class BytecodeAnalyzerTest {

   @Retention(RetentionPolicy.RUNTIME)
   @Target(METHOD)
   public @interface ReturnValueNullability {
      Nullability value();
   }

   @ReturnValueNullability(Nullability.NULLABLE)
   static @Nullable Object returningAConstNull1() {
      return null;
   }

   @ReturnValueNullability(Nullability.NULLABLE)
   static @Nullable Object returningAConstNull2() {
      if (System.currentTimeMillis() == 123)
         return "Hey";
      return null;
   }

   @ReturnValueNullability(Nullability.NULLABLE)
   static @Nullable Object returningAConstNull3() {
      return System.currentTimeMillis() == 123 ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.NULLABLE)
   static @Nullable Object returningAConstNull4(final boolean condition) {
      return condition ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.NULLABLE)
   static @Nullable Object returningAConstNull5() {
      final String foo = null;
      return foo;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningDynamicNull1() {
      return new @Nullable Object[] {null}[0];
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningDynamicNull2() {
      final @Nullable String env = System.getProperty("Abcdefg1234567");
      @SuppressWarnings("unused")
      final var unused = new Object();
      return env;
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static Object neverReturningNull1() {
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static Object neverReturningNull2() {
      return new Object();
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static Object neverReturningNull3() {
      return new String("Test");
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static Object neverReturningNull4() {
      return new Object() + " test";
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   public Object neverReturningNull5(final boolean condition) {
      if (condition)
         return new Object();
      return "Constant String";
   }

   /* test method to ensure that `return null` in lambdas are not mistaken as null returns */
   @ReturnValueNullability(Nullability.NON_NULL)
   static Object neverReturningNull6() {
      final Supplier<@Nullable String> foo = () -> {
         System.out.print("Ho");
         return null;
      };
      foo.get(); // use foo to avoid potential dead code elimination
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static void neverReturningNull7() {
      // nothing to do
   }

   @ReturnValueNullability(Nullability.NON_NULL)
   static int neverReturningNull8() {
      return 1;
   }

   @Test
   @SuppressWarnings("null")
   void testDetermineMethodReturnTypeNullability() {
      final var className = BytecodeAnalyzerTest.class.getName();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .enableSystemJarsAndModules() //
         .acceptClasses(className) //
         .scan()) {

         final var classInfo = scanResult.getClassInfo(className);
         assert classInfo != null;

         final var analyzer = new BytecodeAnalyzer(classInfo);

         for (final Method m : this.getClass().getDeclaredMethods()) {
            final var anno = m.getAnnotation(ReturnValueNullability.class);
            if (anno != null) {
               assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo(m.getName()).get(0))).describedAs(m
                  .getName()).isEqualTo(anno.value());
            }
         }
      }
   }
}
