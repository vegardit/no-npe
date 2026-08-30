/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;

/**
 * Exercises bytecode nullness inference with focused source and generated-bytecode fixtures.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
class BytecodeAnalyzerTest {

   static final class ExternalStaticFields {
      static final Integer INTEGER_ONE = Integer.valueOf(1);
      static final Integer INTEGER_ZERO = Integer.valueOf(0);

      // The negative control ensures that resolving another class does not turn static final into non-null evidence.
      static final @Nullable Integer MAYBE_NULL_INTEGER = System.nanoTime() == 0 ? null : Integer.valueOf(2);
   }

   static final class ExternalNonNullFactory {
      String create() {
         final var buffer = new StringBuilder();
         return buffer.toString();
      }
   }

   static final class ExternalRecursiveFactory {
      Object create() {
         return create();
      }
   }

   static class ExternalOverridableFactory {
      String create() {
         return new StringBuilder().toString();
      }
   }

   static final class ResettableCache {
      private @Nullable Object cached = new Object();

      @Nullable
      Object readAfterClear() {
         final @Nullable Object cachedValue = cached;
         final @Nullable Object sentinel;
         if (cachedValue == null) {
            sentinel = new Object();
         } else {
            sentinel = null;
            clear();
         }
         if (sentinel == null)
            return cached;
         cached = sentinel;
         return sentinel;
      }

      private void clear() {
         cached = null;
      }
   }

   static final class FinalDispatchOwner {
      @Nullable
      Object helper(final @Nullable Object arg) {
         return arg == null ? null : new Object();
      }

      @Nullable
      Object returningHelperResult(final @Nullable Object arg) {
         if (arg == null)
            return null;
         return helper(arg);
      }
   }

   static class VirtualDispatchBase {
      final @Nullable Object finalHelper(final @Nullable Object arg) {
         return arg == null ? null : new Object();
      }

      @Nullable
      Object overridableHelper(final @Nullable Object arg) {
         return arg == null ? null : new Object();
      }

      @Nullable
      Object returningFinalHelperResult(final @Nullable Object arg) {
         if (arg == null)
            return null;
         return finalHelper(arg);
      }

      @Nullable
      Object returningOverridableHelperResult(final @Nullable Object arg) {
         if (arg == null)
            return null;
         return overridableHelper(arg);
      }
   }

   static final class VirtualDispatchChild extends VirtualDispatchBase {
      @Override
      @Nullable
      Object overridableHelper(final @Nullable Object arg) {
         return null;
      }
   }

   @Retention(RetentionPolicy.RUNTIME)
   @Target(ElementType.METHOD)
   public @interface ReturnValueNullability {
      Nullability value();
   }

   @Retention(RetentionPolicy.RUNTIME)
   @Target(ElementType.METHOD)
   public @interface NonNullParameterIndexes {
      int[] value();
   }

   interface CheckedReceiver {
      void invoke() throws IOException;
   }

   static final class NonNullParameterFixtures {
      @NonNullParameterIndexes({0})
      static void alias(final Object value) {
         final Object alias = value;
         alias.toString();
      }

      @NonNullParameterIndexes({})
      static void arraycopyIsNotReceiverEvidence(final Object source, final Object destination) {
         System.arraycopy(source, 0, destination, 0, 0);
      }

      @NonNullParameterIndexes({})
      static void catchesException(final Object value) {
         try {
            value.toString();
         } catch (final Exception ex) {
            throw new IllegalStateException(ex);
         }
      }

      @NonNullParameterIndexes({})
      static void catchesNullPointerException(final Object value) {
         try {
            value.toString();
         } catch (final NullPointerException ex) {
            throw new IllegalStateException(ex);
         }
      }

      @NonNullParameterIndexes({})
      static void catchesRuntimeException(final Object value) {
         try {
            value.toString();
         } catch (final RuntimeException ex) {
            throw new IllegalStateException(ex);
         }
      }

      @SuppressWarnings("all") // Catching Throwable is intentional because the proof must recognize this exact boundary.
      @NonNullParameterIndexes({})
      static void catchesThrowable(final Object value) {
         try {
            value.toString();
         } catch (final Throwable ex) { // CHECKSTYLE:IGNORE IllegalCatch
            throw new IllegalStateException(ex);
         }
      }

      @NonNullParameterIndexes({})
      static void checkedHandlerReturns(final CheckedReceiver value) {
         try {
            value.invoke();
         } catch (final IOException ex) {
            // The conservative proof does not cross an exception edge, even when that handler cannot catch the receiver NPE.
         }
      }

      @NonNullParameterIndexes({0})
      static void checkedHandlerThrows(final CheckedReceiver value) {
         try {
            value.invoke();
         } catch (final IOException ex) {
            throw new IllegalStateException(ex);
         }
      }

      @NonNullParameterIndexes({0})
      static void callsOnEveryBranch(final Object value, final boolean firstBranch) {
         if (firstBranch) {
            value.toString();
         } else {
            value.hashCode();
         }
      }

      @NonNullParameterIndexes({})
      static void earlyReturn(final Object value, final boolean skipCall) {
         if (skipCall)
            return;
         value.toString();
      }

      @NonNullParameterIndexes({})
      static void noNormalReturn(final Object value) {
         value.toString();
         throw new IllegalStateException();
      }

      @NonNullParameterIndexes({0})
      static void onlyFirst(final Object first, final Object second) {
         first.toString();
      }

      @NonNullParameterIndexes({0})
      static int primitiveReturn(final Object value) {
         return value.hashCode();
      }

      @NonNullParameterIndexes({})
      static void reassigned(final Object value) {
         Object replacement = value;
         replacement = new Object();
         replacement.toString();
      }

      @NonNullParameterIndexes({0})
      static Object referenceReturn(final Object value) {
         value.toString();
         return new Object();
      }

      @NonNullParameterIndexes({})
      static void staticCallIsNotReceiverEvidence(final Object value) {
         String.valueOf(value);
      }

      @NonNullParameterIndexes({0})
      static void tryFinally(final Object value) {
         try {
            value.toString();
         } finally {
            System.nanoTime();
         }
      }

      @NonNullParameterIndexes({0})
      static void voidReturn(final Object value) {
         value.toString();
      }
   }

   static final String STATIC_NONNULL_STRING = "HI";

   static final double[] STATIC_NONNULL_DOUBLE_ARRAY = {};

   static final @Nullable Object STATIC_MAYBE_NULL_OBJECT = System.nanoTime() == 0 ? null : new Object();

   static @Nullable Object staticMutableNonNullObject = new Object();

   static @Nullable Object staticNullableObject;

   private final @Nullable Object mixedReceiverField = System.nanoTime() == 0 ? null : new Object();

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
   static Object neverReturningNull13() {
      return STATIC_NONNULL_DOUBLE_ARRAY;
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static String neverReturningNull15() {
      // The final external owner makes this virtual call monomorphic; its method body proves the normal result non-null.
      return new ExternalNonNullFactory().create();
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static String neverReturningNull16(final TemporalAccessor temporal) {
      // This is the JDK call shape used by XStream's ISO8601JavaTimeConverter.toString implementation.
      return DateTimeFormatter.ISO_DATE_TIME.format(temporal);
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static String returningUnknownThroughExternalOverridableMethod(final ExternalOverridableFactory factory) {
      // The analyzed classpath cannot exclude a consumer subclass whose override returns null.
      return factory.create();
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable String returningNullIfArgIsNullThroughExactExternalReceiver(final @Nullable Object arg) {
      if (arg == null)
         return null;
      final var factory = new ExternalOverridableFactory();
      // The receiver's runtime class is exact despite create() being overridable on arbitrary instances.
      return factory.create();
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static String returningUnknownThroughMixedExternalReceivers(final boolean useFresh, final ExternalOverridableFactory suppliedFactory) {
      final var factory = useFresh ? new ExternalOverridableFactory() : suppliedFactory;
      // One exact producer cannot make the receiver exact when another path accepts a caller-provided subclass.
      return factory.create();
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable String returningNullIfArgOrMixedExternalReceiverIsNull(final @Nullable Object arg, final boolean useFresh,
         final ExternalOverridableFactory suppliedFactory) {
      if (arg == null)
         return null;
      final var factory = useFresh ? new ExternalOverridableFactory() : suppliedFactory;
      return factory.create();
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static Object returningUnknownThroughExternalRecursion() {
      // Recursive summaries require a fixed point; partial results must remain unknown.
      return new ExternalRecursiveFactory().create();
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

   @ReturnValueNullability(Nullability.UNKNOWN)
   static Object returningUnknownAfterConditionalReplacement(final Object supplied, final boolean replace) {
      Object result = supplied;
      if (replace) {
         result = new Object();
      }
      // There is no ACONST_NULL in this method, but the caller-provided path still prevents a non-null proof.
      return result;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningMaybeNull5() {
      return STATIC_MAYBE_NULL_OBJECT;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningMaybeNull6() {
      // An initializer is not a lasting non-null guarantee for a mutable static field.
      return staticMutableNonNullObject;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   @Nullable
   Object returningUnknownFromMixedReceiver(final boolean useThis) {
      final BytecodeAnalyzerTest receiver = useThis ? this : new BytecodeAnalyzerTest();
      if (receiver.mixedReceiverField == null)
         return new Object();
      // The preceding test may have inspected the fresh receiver, so it cannot prove this field non-null.
      return mixedReceiverField;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNull1() {
      return null;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNull2() {
      if (System.currentTimeMillis() == 123)
         return "Hey";
      return null;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNull3() {
      return System.currentTimeMillis() == 123 ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNull4(final boolean condition) {
      return condition ? "Hey" : null;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
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

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
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

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNull7(final boolean condition) {
      // javac places the null branch before the non-null branch and merges both values at a single ARETURN.
      return condition ? null : "Hey";
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable String returningNullThroughConditionallyAssignedLocal(final boolean hasValue) {
      // Console.readLine uses this shape: null is the default, and only one branch replaces it before the shared return.
      @Nullable
      String result = null;
      if (hasValue) {
         result = "Hey";
      }
      return result;
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static String neverReturningNullWithUnrelatedNullLocal() {
      // A null constant elsewhere in the method is not evidence unless its value can reach ARETURN.
      @SuppressWarnings("unused")
      final @Nullable Object unrelated = null;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static @Nullable Object neverReturningNullAfterSentinelIsReplaced() {
      // The explicit null is a temporary value; only its non-null replacement can reach ARETURN.
      @Nullable
      Object result = System.nanoTime() == 0 ? null : new Object();
      if (result == null) {
         result = new Object();
      }
      return result;
   }

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static @Nullable Object neverReturningNullAfterSentinelIsReplacedInTry() {
      try {
         @Nullable
         Object result = System.nanoTime() == 0 ? null : new Object();
         if (result == null) {
            result = new Object();
         }
         return result;
      } catch (final RuntimeException ex) {
         // Exception handlers must receive ASM's pre-instruction frame, without facts from either normal branch.
         return new Object();
      }
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

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNullThroughMergedParameter(final @Nullable Object arg) {
      // Both branches share one ARETURN, so PolyNull evidence must come from flow state rather than instruction adjacency.
      return arg == null ? arg : new Object();
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull5(final Object @Nullable [] array) {
      if (array == null)
         return null;
      if (array.length == 0)
         return STATIC_NONNULL_DOUBLE_ARRAY;
      return new double[array.length];
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static Object @Nullable [] returningNullIfArgIsNull6(final Object @Nullable [] array) {
      // javac merges the null constant and clone result at one ARETURN, unlike the early-return cases above.
      return array == null ? null : array.clone();
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static Object @Nullable [] returningNullIfArgIsNull7(final int index, final Object @Nullable [] array, final Object... values) {
      if (array == null)
         return null;
      if (values.length == 0)
         return returningNullIfArgIsNull6(array);
      final Object[] result = neverReturningNull14(Object.class, array.length + values.length);
      System.arraycopy(array, 0, result, 0, index);
      System.arraycopy(values, 0, result, index, values.length);
      return result;
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   @SuppressWarnings("unused")
   static @Nullable Object returningNullIfArgIsNull8(final long ignored, final @Nullable Object arg) {
      // The reference parameter starts after the two local-variable slots occupied by the long parameter.
      if (arg == null)
         return null;
      return "Hey";
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNullIfArgOrUnknownIsNull(final @Nullable Object arg) {
      if (arg == null)
         return null;
      return System.getProperty("Abcdefg1234567");
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNullAfterParameterIsReassigned(@Nullable Object arg) {
      // Reusing a parameter slot does not preserve a dependency on the original argument value.
      arg = System.getProperty("Abcdefg1234567");
      if (arg == null)
         return null;
      return new Object();
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Object returningNullOnException(final @Nullable Object arg) {
      try {
         if (arg == null)
            return null;
         if (System.nanoTime() == 0)
            throw new IllegalStateException();
         return new Object();
      } catch (final IllegalStateException ex) {
         // This return is independent of arg, so the earlier guarded null return does not make the method poly-null.
         return null;
      }
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Object returningUnknownThroughPolyHelper(final @Nullable Object arg, final boolean useArg) {
      final @Nullable Object helperArg = useArg ? arg : System.getProperty("Abcdefg1234567");
      return returningNullIfArgIsNull9(helperArg);
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Object returningNullIfArgIsNull9(final @Nullable Object arg) {
      return arg == null ? null : new Object();
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Integer returningNullIfArgIsNullThroughIntegerDecode(final @Nullable String arg) {
      return arg == null ? null : Integer.decode(arg);
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Long returningNullIfArgIsNullThroughLongDecode(final @Nullable String arg) {
      return arg == null ? null : Long.decode(arg);
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Number returningNullIfArgIsNullThroughWrapperDecoders(final @Nullable String arg, final boolean useLong) {
      if (arg == null)
         return null;
      return useLong ? (Number) Long.decode(arg) : (Number) Integer.decode(arg);
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Integer returningNullIfArgIsNullThroughSubstringAndPolyHelper(final @Nullable String arg) {
      if (arg == null)
         return null;
      // NumberUtils.createNumber parses the same proven-non-null substring through its poly-null helper methods.
      return returningNullIfArgIsNullThroughIntegerDecode(arg.substring(0));
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static @Nullable Integer returningNullIfArgOrCustomValueOfIsNull(final @Nullable String arg) {
      // Matching valueOf(int) is insufficient: only the exact JDK wrapper owners have the trusted non-null contract.
      return arg == null ? null : valueOf(arg.length());
   }

   static @Nullable Integer valueOf(final int value) {
      return System.nanoTime() == value ? null : Integer.valueOf(value);
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Boolean returningBooleanSingletonIfArgIsNonNull(final @Nullable Boolean arg) {
      if (arg == null)
         // Returning null is the contract under test, so the normal Boolean-return warning is intentionally inapplicable.
         return null; // CHECKSTYLE:IGNORE ReturnNullInsteadOfBoolean
      // This mirrors code such as BooleanUtils.negate, where javac loads the boxed results from external static fields.
      return arg ? Boolean.FALSE : Boolean.TRUE;
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static @Nullable Integer returningExternalIntegerSingletonIfArgIsNonNull(final @Nullable Integer arg) {
      if (arg == null)
         return null;
      // NumberUtils uses the same external GETSTATIC shape for Integer constants initialized through valueOf(int).
      return arg.intValue() == 0 ? ExternalStaticFields.INTEGER_ZERO : ExternalStaticFields.INTEGER_ONE;
   }

   @ReturnValueNullability(Nullability.UNKNOWN)
   static @Nullable Integer returningExternalMaybeNullInteger() {
      return ExternalStaticFields.MAYBE_NULL_INTEGER;
   }

   @ReturnValueNullability(Nullability.POLY_NULL)
   static Object @Nullable [] returningNullIfArgIsNull10(final Object @Nullable [] array) {
      if (array == null)
         return null;
      return copyWithFactory(array, Object[]::new);
   }

   static Object[] copyWithFactory(final Object[] source, final Function<Integer, Object[]> factory) {
      return copyToValidatedDestination(source, factory.apply(source.length));
   }

   static Object[] copyToValidatedDestination(final Object[] source, final Object[] destination) {
      System.arraycopy(source, 0, destination, 0, source.length);
      return destination;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static Object @Nullable [] returningNullIfArgOrUnvalidatedDestinationIsNull(final @Nullable Object arg, final boolean validate) {
      if (arg == null)
         return null;
      return copyToMaybeValidatedDestination(new Object[0], returningUnknownArray(), validate);
   }

   static Object[] copyToMaybeValidatedDestination(final Object[] source, final Object[] destination, final boolean validate) {
      if (validate) {
         System.arraycopy(source, 0, destination, 0, source.length);
      }
      return destination;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static Object @Nullable [] returningNullIfArgOrCaughtDestinationIsNull(final @Nullable Object arg) {
      if (arg == null)
         return null;
      return copyToCaughtDestination(new Object[0], returningUnknownArray());
   }

   static Object[] copyToCaughtDestination(final Object[] source, final Object[] destination) {
      try {
         System.arraycopy(source, 0, destination, 0, source.length);
      } catch (final NullPointerException ex) { /* ignore */}
      return destination;
   }

   @ReturnValueNullability(Nullability.DEFINITELY_NULL)
   static Object @Nullable [] returningNullIfArgOrReassignedDestinationIsNull(final @Nullable Object arg) {
      if (arg == null)
         return null;
      return copyToReassignedDestination(new Object[0], new Object[0]);
   }

   static Object[] copyToReassignedDestination(final Object[] source, Object[] destination) {
      System.arraycopy(source, 0, destination, 0, source.length);
      destination = returningUnknownArray();
      return destination;
   }

   // A body-less fixture gives the analyzer an unknown value without source annotations that make ECJ reject the arraycopy call.
   static native Object[] returningUnknownArray();

   @ReturnValueNullability(Nullability.NEVER_NULL)
   static Object[] neverReturningNull14(final Class<?> componentType, final int length) {
      return (Object[]) Array.newInstance(componentType, length);
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

   @SuppressWarnings("null")
   private static byte[] createClassWithExternalFieldRead(final String className, final String fieldOwner) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
      final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
      method.visitCode();
      method.visitFieldInsn(Opcodes.GETSTATIC, fieldOwner, "VALUE", "Ljava/lang/Object;");
      method.visitInsn(Opcodes.ARETURN);
      method.visitMaxs(1, 0);
      method.visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithStaticFinalField(final String className) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
      classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "VALUE", "Ljava/lang/Object;", null, null)
         .visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithDeepStaticFieldProvenance(final String className) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
      classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "VALUE", "Ljava/lang/Object;", null, null)
         .visitEnd();

      final var initializer = classWriter.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      initializer.visitCode();
      initializer.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
      initializer.visitInsn(Opcodes.DUP);
      initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      initializer.visitVarInsn(Opcodes.ASTORE, 0);
      /* Each load/store pair adds two provenance links. This remains valid bytecode but is deep enough to overflow a
       * recursive proof on ordinary JVM stack sizes. */
      for (int i = 0; i < 10_000; i++) {
         initializer.visitVarInsn(Opcodes.ALOAD, 0);
         initializer.visitVarInsn(Opcodes.ASTORE, 0);
      }
      initializer.visitVarInsn(Opcodes.ALOAD, 0);
      initializer.visitFieldInsn(Opcodes.PUTSTATIC, className, "VALUE", "Ljava/lang/Object;");
      initializer.visitInsn(Opcodes.RETURN);
      initializer.visitMaxs(2, 1);
      initializer.visitEnd();

      final var reader = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
      reader.visitCode();
      reader.visitFieldInsn(Opcodes.GETSTATIC, className, "VALUE", "Ljava/lang/Object;");
      reader.visitInsn(Opcodes.ARETURN);
      reader.visitMaxs(1, 0);
      reader.visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithDeepReturnProvenance(final String className) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "identity",
         "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
      method.visitCode();
      method.visitVarInsn(Opcodes.ALOAD, 0);
      method.visitVarInsn(Opcodes.ASTORE, 1);
      // Every load/store pair adds another valid provenance edge without changing the returned value.
      for (int i = 0; i < 10_000; i++) {
         method.visitVarInsn(Opcodes.ALOAD, 1);
         method.visitVarInsn(Opcodes.ASTORE, 1);
      }
      method.visitVarInsn(Opcodes.ALOAD, 1);
      method.visitInsn(Opcodes.ARETURN);
      method.visitMaxs(1, 2);
      method.visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithDeepNullCheckProvenance(final String className) {
      final var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      final var nonNull = new Label();
      final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nullOrNew",
         "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
      method.visitCode();
      method.visitVarInsn(Opcodes.ALOAD, 0);
      method.visitVarInsn(Opcodes.ASTORE, 1);
      // The null check must trace the alias back to the parameter to establish the PolyNull dependency.
      for (int i = 0; i < 10_000; i++) {
         method.visitVarInsn(Opcodes.ALOAD, 1);
         method.visitVarInsn(Opcodes.ASTORE, 1);
      }
      method.visitVarInsn(Opcodes.ALOAD, 1);
      method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
      method.visitInsn(Opcodes.ACONST_NULL);
      method.visitInsn(Opcodes.ARETURN);
      method.visitLabel(nonNull);
      method.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
      method.visitInsn(Opcodes.DUP);
      method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      method.visitInsn(Opcodes.ARETURN);
      method.visitMaxs(0, 0);
      method.visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithDeepMethodSummaryChain(final String className, final int methodCount) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      for (int i = 0; i < methodCount; i++) {
         final String methodName = "value" + i;
         final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()Ljava/lang/Object;", null,
            null);
         method.visitCode();
         if (i + 1 < methodCount) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, className, "value" + (i + 1), "()Ljava/lang/Object;", false);
         } else {
            method.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            method.visitInsn(Opcodes.DUP);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
         }
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(2, 0);
         method.visitEnd();
      }

      final var nonNull = new Label();
      final var nullableValue = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nullableValue",
         "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
      nullableValue.visitCode();
      nullableValue.visitVarInsn(Opcodes.ALOAD, 0);
      nullableValue.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
      nullableValue.visitInsn(Opcodes.ACONST_NULL);
      nullableValue.visitInsn(Opcodes.ARETURN);
      nullableValue.visitLabel(nonNull);
      nullableValue.visitMethodInsn(Opcodes.INVOKESTATIC, className, "value0", "()Ljava/lang/Object;", false);
      nullableValue.visitInsn(Opcodes.ARETURN);
      nullableValue.visitMaxs(1, 1);
      nullableValue.visitEnd();
      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithExternallyResettableCachedReturn(final String className, final boolean nativeReset) {
      /* Keep the null-sentinel control flow aligned with CachedReturnPlugin. Ordinary javac source does not retain the
       * same sentinel provenance, so it would not exercise the field-fact inference that regressed. */
      final var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
      classWriter.visitField(Opcodes.ACC_PRIVATE, "cached", "Ljava/lang/Object;", null, null).visitEnd();

      final var constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();

      final var computeValue = new Label();
      final var sentinelReady = new Label();
      final var storeValue = new Label();
      final var returnValue = new Label();
      final var reader = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null);
      reader.visitCode();
      reader.visitVarInsn(Opcodes.ALOAD, 0);
      reader.visitFieldInsn(Opcodes.GETFIELD, className, "cached", "Ljava/lang/Object;");
      reader.visitVarInsn(Opcodes.ASTORE, 1);
      reader.visitVarInsn(Opcodes.ALOAD, 1);
      reader.visitJumpInsn(Opcodes.IFNULL, computeValue);
      reader.visitInsn(Opcodes.ACONST_NULL);
      reader.visitJumpInsn(Opcodes.GOTO, sentinelReady);
      reader.visitLabel(computeValue);
      reader.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
      reader.visitInsn(Opcodes.DUP);
      reader.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      reader.visitLabel(sentinelReady);
      reader.visitVarInsn(Opcodes.ASTORE, 2);
      reader.visitVarInsn(Opcodes.ALOAD, 2);
      reader.visitJumpInsn(Opcodes.IFNONNULL, storeValue);
      reader.visitVarInsn(Opcodes.ALOAD, 0);
      reader.visitFieldInsn(Opcodes.GETFIELD, className, "cached", "Ljava/lang/Object;");
      reader.visitVarInsn(Opcodes.ASTORE, 2);
      reader.visitJumpInsn(Opcodes.GOTO, returnValue);
      reader.visitLabel(storeValue);
      reader.visitVarInsn(Opcodes.ALOAD, 0);
      reader.visitVarInsn(Opcodes.ALOAD, 2);
      reader.visitFieldInsn(Opcodes.PUTFIELD, className, "cached", "Ljava/lang/Object;");
      reader.visitLabel(returnValue);
      reader.visitVarInsn(Opcodes.ALOAD, 2);
      reader.visitInsn(Opcodes.ARETURN);
      reader.visitMaxs(0, 0);
      reader.visitEnd();

      if (nativeReset) {
         classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "clearFromAnotherThread", "()V", null, null).visitEnd();
      } else {
         final var clearer = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "clearFromAnotherThread", "()V", null, null);
         clearer.visitCode();
         clearer.visitVarInsn(Opcodes.ALOAD, 0);
         clearer.visitInsn(Opcodes.ACONST_NULL);
         clearer.visitFieldInsn(Opcodes.PUTFIELD, className, "cached", "Ljava/lang/Object;");
         clearer.visitInsn(Opcodes.RETURN);
         clearer.visitMaxs(0, 0);
         clearer.visitEnd();
      }

      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithNullableObjectOverrides(final String className) {
      /* ECJ rejects these source overrides against its non-null Object contract, although the JVM permits them. Emit
       * the legal bytecode directly so the regression test covers the analyzer's trust boundary. */
      final var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      final var constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();

      final var clone = classWriter.visitMethod(Opcodes.ACC_PROTECTED, "clone", "()Ljava/lang/Object;", null, null);
      clone.visitCode();
      clone.visitInsn(Opcodes.ACONST_NULL);
      clone.visitInsn(Opcodes.ARETURN);
      clone.visitMaxs(0, 0);
      clone.visitEnd();

      final var toString = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
      toString.visitCode();
      toString.visitInsn(Opcodes.ACONST_NULL);
      toString.visitInsn(Opcodes.ARETURN);
      toString.visitMaxs(0, 0);
      toString.visitEnd();

      final var stringValueOf = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "readStringValueOf", "()Ljava/lang/String;", null, null);
      stringValueOf.visitCode();
      stringValueOf.visitVarInsn(Opcodes.ALOAD, 0);
      stringValueOf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
      stringValueOf.visitInsn(Opcodes.ARETURN);
      stringValueOf.visitMaxs(0, 0);
      stringValueOf.visitEnd();

      final var stringValueOfWrapper = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readStringValueOfThroughHelper",
         "()Ljava/lang/String;", null, null);
      stringValueOfWrapper.visitCode();
      stringValueOfWrapper.visitTypeInsn(Opcodes.NEW, className);
      stringValueOfWrapper.visitInsn(Opcodes.DUP);
      stringValueOfWrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "<init>", "()V", false);
      stringValueOfWrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "readStringValueOf", "()Ljava/lang/String;", false);
      stringValueOfWrapper.visitInsn(Opcodes.ARETURN);
      stringValueOfWrapper.visitMaxs(0, 0);
      stringValueOfWrapper.visitEnd();

      for (final String methodName : new String[] {"readClone", "readToString"}) {
         final String calledMethod = "readClone".equals(methodName) ? "clone" : "toString";
         final String returnDescriptor = "readClone".equals(methodName) ? "Ljava/lang/Object;" : "Ljava/lang/String;";
         final var wrapper = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, "()" + returnDescriptor, null,
            null);
         wrapper.visitCode();
         wrapper.visitTypeInsn(Opcodes.NEW, className);
         wrapper.visitInsn(Opcodes.DUP);
         wrapper.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "<init>", "()V", false);
         wrapper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, calledMethod, "()" + returnDescriptor, false);
         wrapper.visitInsn(Opcodes.ARETURN);
         wrapper.visitMaxs(0, 0);
         wrapper.visitEnd();
      }

      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithOverBudgetMethod(final String className) {
      final var classWriter = new ClassWriter(0);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
      method.visitCode();
      for (int i = 0; i < 512; i++) {
         method.visitInsn(Opcodes.NOP);
      }
      method.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
      method.visitInsn(Opcodes.DUP);
      method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      method.visitInsn(Opcodes.ARETURN);
      /* Overdeclared max_locals values are legal class-file input. Keep this fixture only slightly above the generator's
       * analysis budget so the unfixed analyzer demonstrates the problem without consuming excessive test memory. */
      method.visitMaxs(2, 2_048);
      method.visitEnd();

      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   @SuppressWarnings("null")
   private static byte[] createClassWithOverBudgetPrivateFieldFacts(final String className, final int fieldCount) {
      final var classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
      for (int i = 0; i < fieldCount; i++) {
         classWriter.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "value" + i, "Ljava/lang/Object;", null, null).visitEnd();
      }

      final var constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();

      final var method = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null);
      method.visitCode();
      for (int i = 0; i < fieldCount; i++) {
         final var nonNull = new Label();
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitFieldInsn(Opcodes.GETFIELD, className, "value" + i, "Ljava/lang/Object;");
         method.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
         method.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
         method.visitInsn(Opcodes.DUP);
         method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
         method.visitInsn(Opcodes.ARETURN);
         method.visitLabel(nonNull);
      }
      method.visitVarInsn(Opcodes.ALOAD, 0);
      method.visitFieldInsn(Opcodes.GETFIELD, className, "value0", "Ljava/lang/Object;");
      method.visitInsn(Opcodes.ARETURN);
      method.visitMaxs(0, 0);
      method.visitEnd();

      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   private static void writeClass(final Path classpathRoot, final String className, final byte[] bytecode) throws IOException {
      final Path classFile = classpathRoot.resolve(className + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, bytecode);
   }

   @Test
   @SuppressWarnings("null")
   void testExternalStaticFieldParseFailureFallsBackToUnknown(@TempDir final Path tempDir) throws IOException {
      final String consumerClassName = "test/ExternalFieldConsumer";
      final String fieldOwnerClassName = "external/UnsupportedFieldOwner";
      writeClass(tempDir, consumerClassName, createClassWithExternalFieldRead(consumerClassName, fieldOwnerClassName));

      final byte[] unsupportedOwner = createClassWithStaticFinalField(fieldOwnerClassName);
      /* The class-file major version occupies bytes 6-7. Short.MAX_VALUE is deliberately beyond every supported ASM
       * version, while the rest of the class remains valid so the failure is isolated to optional owner parsing. */
      unsupportedOwner[6] = Byte.MAX_VALUE;
      unsupportedOwner[7] = -1;
      writeClass(tempDir, fieldOwnerClassName, unsupportedOwner);

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(consumerClassName.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(consumerClassName.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDeepStaticFieldProvenanceDoesNotOverflow(@TempDir final Path tempDir) throws IOException {
      final String className = "test/DeepStaticFieldProvenance";
      writeClass(tempDir, className, createClassWithDeepStaticFieldProvenance(className));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(
            Nullability.NEVER_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testExcessiveMethodAnalysisFallsBackToUnknown(@TempDir final Path tempDir) throws IOException {
      final String className = "test/OverBudgetMethod";
      writeClass(tempDir, className, createClassWithOverBudgetMethod(className));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testExcessivePrivateFieldFactAnalysisFallsBackToUnknown(@TempDir final Path tempDir) throws IOException {
      final String className = "test/OverBudgetPrivateFieldFacts";
      writeClass(tempDir, className, createClassWithOverBudgetPrivateFieldFacts(className, 1_000));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         /* Runtime behavior is non-null, but proving it carries a growing field set through every branch. Exceeding the
          * optional fact budget must lose precision instead of allowing that quadratic state to grow unchecked. */
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testMethodDependencySummaryRequiresMonomorphicDispatch() {
      assertThat(new VirtualDispatchChild().returningOverridableHelperResult(new Object())).isNull();

      final String virtualOwnerName = VirtualDispatchBase.class.getName();
      final String finalOwnerName = FinalDispatchOwner.class.getName();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .acceptClasses(virtualOwnerName, finalOwnerName) //
         .scan()) {
         final var virtualOwner = scanResult.getClassInfo(virtualOwnerName);
         final var finalOwner = scanResult.getClassInfo(finalOwnerName);
         assert virtualOwner != null;
         assert finalOwner != null;

         final var resolver = new BytecodeAnalyzer.StaticFieldResolver(scanResult);
         final var virtualAnalyzer = new BytecodeAnalyzer(virtualOwner, resolver);
         // Rejecting the unsafe summary leaves the explicit null-return path as conservative nullable evidence.
         assertThat(virtualAnalyzer.determineMethodReturnTypeNullability(virtualOwner.getMethodInfo("returningOverridableHelperResult").get(
            0))).isEqualTo(Nullability.DEFINITELY_NULL);
         assertThat(virtualAnalyzer.determineMethodReturnTypeNullability(virtualOwner.getMethodInfo("returningFinalHelperResult").get(0)))
            .isEqualTo(Nullability.POLY_NULL);

         final var finalAnalyzer = new BytecodeAnalyzer(finalOwner, resolver);
         assertThat(finalAnalyzer.determineMethodReturnTypeNullability(finalOwner.getMethodInfo("returningHelperResult").get(0))).isEqualTo(
            Nullability.POLY_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testExactReceiverSummaryDoesNotAuthorizeArbitraryReceiverCall() {
      final String callerName = BytecodeAnalyzerTest.class.getName();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .acceptClasses(callerName) //
         .scan()) {
         final var caller = scanResult.getClassInfo(callerName);
         assert caller != null;
         final var analyzer = new BytecodeAnalyzer(caller, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // Analyze the exact call first so the declared-body summary is cached before the unsafe call site is checked.
         assertThat(analyzer.determineMethodReturnTypeNullability(caller.getMethodInfo(
            "returningNullIfArgIsNullThroughExactExternalReceiver").get(0))).isEqualTo(Nullability.POLY_NULL);
         assertThat(analyzer.determineMethodReturnTypeNullability(caller.getMethodInfo("returningUnknownThroughExternalOverridableMethod")
            .get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testByteBuddyCachedReturnSentinelDoesNotReachReturn() {
      final String className = "net.bytebuddy.description.field.FieldDescription$ForLoadedField";
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .acceptClasses(className) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className);
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         /* CachedReturnPlugin emits ACONST_NULL as a control-flow sentinel when the cache is already populated. The
          * sentinel is replaced with the cached field before ARETURN and must not make the method nullable. */
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("getDeclaredAnnotations").get(0))).isEqualTo(
            Nullability.NEVER_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testCallInvalidatesPrivateFieldFact() {
      final String className = ResettableCache.class.getName();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .acceptClasses(className) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className);
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // The call clears the field after the sentinel is created, so the sentinel cannot restore that stale field fact.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("readAfterClear").get(0))).isEqualTo(
            Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDeepReturnProvenanceDoesNotOverflow(@TempDir final Path tempDir) throws IOException {
      final String className = "test/DeepReturnProvenance";
      writeClass(tempDir, className, createClassWithDeepReturnProvenance(className));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // Identity-style dependency evidence must remain unknown without a reachable null-return path.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("identity").get(0))).isEqualTo(
            Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDeepNullCheckProvenanceDoesNotOverflow(@TempDir final Path tempDir) throws IOException {
      final String className = "test/DeepNullCheckProvenance";
      writeClass(tempDir, className, createClassWithDeepNullCheckProvenance(className));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("nullOrNew").get(0))).isEqualTo(
            Nullability.POLY_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDeepMethodSummaryChainFallsBackWithoutPoisoningTheCache(@TempDir final Path tempDir) throws IOException {
      final String className = "test/DeepMethodSummaryChain";
      final int methodCount = 5_000;
      writeClass(tempDir, className, createClassWithDeepMethodSummaryChain(className, methodCount));

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         /* The explicit null return remains valid nullable evidence when the dependency walk exhausts its depth budget;
          * only the stronger PolyNull classification depends on completing that walk. */
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("nullableValue").get(0))).isEqualTo(
            Nullability.DEFINITELY_NULL);
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("value0").get(0))).isEqualTo(Nullability.UNKNOWN);
         // Budget exhaustion in the first analysis must not cache UNKNOWN for a method that is independently provable.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("value" + (methodCount - 1)).get(0))).isEqualTo(
            Nullability.NEVER_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testExternalMutationInvalidatesPrivateFieldFact(@TempDir final Path tempDir) throws IOException {
      final String className = "test/ExternallyResettableCachedReturn";
      writeClass(tempDir, className, createClassWithExternallyResettableCachedReturn(className, false));
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         /* A private field can still be cleared through another invocation on the same object. The analyzer must not
          * convert an earlier field test into a permanent non-null fact merely because this method contains no call. */
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNativeMutationInvalidatesPrivateFieldFact(@TempDir final Path tempDir) throws IOException {
      final String className = "test/NativeResettableCachedReturn";
      writeClass(tempDir, className, createClassWithExternallyResettableCachedReturn(className, true));
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // JNI can change any mutable instance field, so an absent Java method body provides no preservation proof.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("read").get(0))).isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNullableObjectOverridesAreNotKnownNonNull(@TempDir final Path tempDir) throws IOException {
      final String className = "test/NullableObjectOverrides";
      writeClass(tempDir, className, createClassWithNullableObjectOverrides(className));
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // Java permits both overrides to return null; their names alone cannot establish a non-null call contract.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("readClone").get(0))).isEqualTo(
            Nullability.UNKNOWN);
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("readToString").get(0))).isEqualTo(
            Nullability.UNKNOWN);
         // String.valueOf(Object) delegates to the virtual toString() result and cannot strengthen that result.
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("readStringValueOf").get(0))).isEqualTo(
            Nullability.UNKNOWN);
         assertThat(analyzer.determineMethodReturnTypeNullability(classInfo.getMethodInfo("readStringValueOfThroughHelper").get(0)))
            .isEqualTo(Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDetermineDefinitelyNonNullMethodParameters() {
      final String className = NonNullParameterFixtures.class.getName();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .acceptClasses(className) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className);
         assert classInfo != null;
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         Stream.of(NonNullParameterFixtures.class.getDeclaredMethods()).sorted(Comparator.comparing(Method::getName)).forEach(method -> {
            final var expected = method.getAnnotation(NonNullParameterIndexes.class);
            if (expected != null) {
               final Integer[] expectedIndexes = Arrays.stream(expected.value()).boxed().toArray(Integer[]::new);
               assertThat(analyzer.determineDefinitelyNonNullMethodParameters(classInfo.getMethodInfo(method.getName()).get(0)))
                  .describedAs(method.getName()).containsExactlyInAnyOrder(expectedIndexes);
            }
         });
      }
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

         // Only the consumer is accepted above; external field owners must be resolved without expanding generation scope.
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

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
