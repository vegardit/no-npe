/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
   @Target(ElementType.METHOD)
   public @interface ReturnValueNullability {
      Nullability value();
   }

   static final String STATIC_NONNULL_STRING = "HI";

   static @Nullable Object staticNullableObject;

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull1() {
      /*L0
         LDC "Hey"
         ARETURN */
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull10() {
      /*L0
         ICONST_0
         NEWARRAY T_INT
         ARETURN */
      return new int[0];
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull11() {
      /*L0
         ICONST_0
         ANEWARRAY java/lang/Object
         ARETURN */
      return new Object[0];
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static @Nullable String neverReturningNull12() {
      final Object str = "Hey";
      return (String) str;
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull2() {
      /*L0
         NEW java/lang/Object
         DUP
         INVOKESPECIAL java/lang/Object.<init>()V
         ARETURN */
      return new Object();
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull3() {
      /*L0
         NEW java/lang/String
         DUP
         LDC "Test"
         INVOKESPECIAL java/lang/String.<init>(Ljava/lang/String;)V
         ARETURN */
      return new String("Test");
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull4() {
      /*L0
         NEW java/lang/Object
         DUP
         INVOKESPECIAL java/lang/Object.<init>()V
         INVOKESTATIC java/lang/String.valueOf(Ljava/lang/Object;)Ljava/lang/String;
         INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String; [
           // handle kind 0x6 : INVOKESTATIC
           java/lang/invoke/StringConcatFactory.makeConcatWithConstants(
             Ljava/lang/invoke/MethodHandles$Lookup;
             Ljava/lang/String;Ljava/lang/invoke/MethodType;
             Ljava/lang/String;[Ljava/lang/Object;
           )Ljava/lang/invoke/CallSite;
           arguments:
             "\u0001 test"
         ]
         ARETURN */
      return new Object() + " test";
   }

   // test method to ensure that `return null` in lambdas are not mistaken as null returns
   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object neverReturningNull6() {
      ((Supplier<?>) () -> null).get();
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static void neverReturningNull7() {
      // nothing to do
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static boolean neverReturningNull8() {
      return true;
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static @Nullable Object neverReturningNull9() {
      return STATIC_NONNULL_STRING;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningMaybeNull1() {
      return new @Nullable Object[] {null}[0];
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningMaybeNull2() {
      final var env = System.getProperty("Abcdefg1234567");
      @SuppressWarnings("unused")
      final var unused = new Object();
      return env;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable String returningMaybeNull3() {
      final @Nullable Object env = System.getProperty("Abcdefg1234567");
      return (String) env;
   }

   static @Nullable Object returningMaybeNull4() {
      return staticNullableObject;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable Object returningNull1() {
      return null;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable Object returningNull2() {
      if (System.currentTimeMillis() == 123)
         return "Hey";
      return null;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable Object returningNull3() {
      return System.currentTimeMillis() == 123 ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable Object returningNull4(final boolean condition) {
      return condition ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable Object returningNull5() {
      /*L0
         ACONST_NULL
         ASTORE 0
        L1
         ALOAD 0
         ARETURN
        L2
         LOCALVARIABLE foo Ljava/lang/String; L1 L2 0 */
      final String foo = null;
      return foo;
   }

   @ReturnValueNullability(Nullability.DEFINITLY_NULL)
   static @Nullable String returningNull6() {
      /*L0
         ACONST_NULL
         ASTORE 0
        L1
         ALOAD 0
         CHECKCAST java/lang/String
         ARETURN
        L2
         LOCALVARIABLE str Ljava/lang/Object; L1 L2 0 */
      final Object str = null;
      return (String) str;
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull1(final @Nullable String arg1) {
      if (arg1 == null)
         return null;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull2(final @Nullable String arg1, final @Nullable String arg2) {
      if (arg1 == null || arg2 == null)
         return null;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull3(final @Nullable String arg1, final @Nullable String arg2) {
      if (arg1 == null && arg2 == null)
         return null;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull4(final @Nullable String arg1) {
      if (arg1 == null)
         return arg1;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   public Object neverReturningNull5(final boolean condition) {
      /*L0
         ILOAD 1
         IFEQ L1
        L2
         NEW java/lang/Object
         DUP
         INVOKESPECIAL java/lang/Object.<init>()V
         ARETURN
        L1
        FRAME SAME
         LDC "Constant String"
         ARETURN
        L3
         LOCALVARIABLE this Lcom/vegardit/no_npe/eea_generator/internal/BytecodeAnalyzerTest; L0 L3 0
         LOCALVARIABLE condition Z L0 L3 1 */
      if (condition)
         return new Object();
      return "Constant String";
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

         Stream.of(getClass().getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).forEach(m -> {
            final var anno = m.getAnnotation(ReturnValueNullability.class);
            if (anno != null) {
               assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo(m.getName()).get(0))).describedAs(m
                  .getName()).isEqualTo(anno.value());
            }
         });
      }
   }
}
