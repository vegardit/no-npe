/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import com.vegardit.no_npe.eea_generator.EEAFile;
import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.EEAGenerator;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Checks local JVM nullness evidence and return dependencies across classes, including cases where extra inference is unsafe.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeInferenceExtensionsTest {

   @NonNullByDefault({}) // Fixtures retain their runtime null behavior instead of relying on compiler nullness defaults.
   public static final class Dereferences {
      Object value;
      long wide;

      public static int length(final int[] values) {
         return values.length;
      }

      public static Object element(final Object[] values) {
         return values[0];
      }

      public static long wideElement(final long[] values) {
         return values[0];
      }

      public static void storeElement(final Object[] values, final Object value) {
         values[0] = value;
      }

      public static void storeWideElement(final double[] values, final double value) {
         values[0] = value;
      }

      public static Object field(final Dereferences receiver) {
         return receiver.value;
      }

      public static void storeField(final Dereferences receiver, final Object value) {
         receiver.value = value;
      }

      @SuppressWarnings("unused") // Wide arguments distinguish descriptor parameter indexes from JVM local slots.
      public static void storeWideField(final long padding, final Dereferences receiver, final long value) {
         receiver.wide = value;
      }

      public static int lock(final Object lock) {
         synchronized (lock) {
            return 1;
         }
      }

      public static int alias(final int[] values) {
         final int[] alias = values;
         return alias.length;
      }

      public static int conditional(final int[] values, final boolean read) {
         return read ? values.length : 0;
      }

      public static int nullable(final int[] values) {
         if (values == null)
            return 0;
         return values.length;
      }

      public static int merged(final int[] first, final int[] second, final boolean useFirst) {
         return (useFirst ? first : second).length;
      }

      public static int caught(final int[] values) {
         try {
            return values.length;
         } catch (final NullPointerException ex) {
            return 0;
         }
      }

      public static int rethrown(final int[] values) {
         try {
            return values.length;
         } catch (final NullPointerException ex) {
            // Keep the established policy for explicit NPE catches even when this handler throws again.
            throw new IllegalArgumentException(ex);
         }
      }

      public static int finallyCompletes(final int[] values) {
         try {
            return values.length;
         } finally {
            System.nanoTime();
         }
      }

      public static int finallyReturns(final int[] values) {
         try {
            return values.length;
         } finally { // CHECKSTYLE:IGNORE ForbidReturnInFinallyBlock
            // This deliberate swallowing path must prevent the normal dereference from becoming a caller requirement.
            if (System.nanoTime() == 0)
               return 0;
         }
      }

      public static int delegated(final int[] values) {
         return length(values);
      }
   }

   @NonNullByDefault({})
   public static final class Functions {
      public static final Supplier<String> STATIC_LAMBDA = () -> null;

      public static Supplier<String> lambda() {
         return () -> null;
      }

      @SuppressWarnings("unchecked") // ECJ warns on this intersection cast, which deliberately exercises altMetafactory.
      public static Supplier<String> serializableLambda() {
         return (Supplier<String> & Serializable) () -> null;
      }

      public static Supplier<String> capturing(final String value) {
         return () -> value;
      }

      public static Supplier<String> methodReference(final String value) {
         return value::trim;
      }

      public static Object invocationResult() {
         return lambda().get();
      }

      public static Supplier<String> conditional(final boolean create) {
         return create ? () -> null : null;
      }

      public static String concat(final Object value) {
         return "value=" + value;
      }

      public static Supplier<String> staticLambda() {
         return STATIC_LAMBDA;
      }
   }

   @NonNullByDefault({})
   public static final class TypeTests {
      public static String fallback(final Object value) {
         return value instanceof String ? (String) value : "";
      }

      public static Object required(final Object value) {
         if (!(value instanceof String))
            throw new IllegalArgumentException();
         return value;
      }

      public static String savedTest(final Object value) {
         final boolean isString = value instanceof String;
         return isString ? (String) value : "";
      }

      public static Object savedRequired(final Object value) {
         final boolean isString = value instanceof String;
         if (!isString)
            throw new IllegalArgumentException();
         return value;
      }

      public static String loop(final Object value, final int repeats) {
         final boolean isString = value instanceof String;
         int remaining = repeats;
         while (remaining-- > 0) {
            if (isString)
               return (String) value;
         }
         return "";
      }

      public static Object falseBranch(final Object value) {
         return value instanceof String ? "" : value;
      }

      public static Object reassigned(Object value, final Object replacement) {
         final boolean isString = value instanceof String;
         value = replacement;
         // The saved boolean concerns the old value; a replacement can still be null on the true branch.
         return isString ? value : "";
      }

      public static Object mergedTest(final Object first, final Object second, final boolean useFirst) {
         final boolean isString = useFirst ? first instanceof String : second instanceof String;
         return isString ? first : "";
      }

      public static Object mergedRequired(final Object first, final Object second, final boolean useFirst) {
         final boolean isString = useFirst ? first instanceof String : second instanceof String;
         if (!isString)
            throw new IllegalArgumentException();
         return first;
      }

      public static Object overwrittenBoolean(boolean accepted, final Object value, final boolean test) {
         if (test) {
            accepted = value instanceof String;
         }
         if (!accepted)
            throw new IllegalArgumentException();
         // A caller-supplied true value can bypass the type test, including when value is null.
         return value;
      }
   }

   /** Exercises null predicates without turning saved booleans into facts about later replacement values. */
   @NonNullByDefault({})
   public static final class NullPredicates {
      public static Object nonNullFallback(final Object value) {
         return Objects.nonNull(value) ? value : "";
      }

      public static Object isNullFallback(final Object value) {
         return Objects.isNull(value) ? "" : value;
      }

      public static Object required(final Object value) {
         if (Objects.isNull(value))
            throw new IllegalArgumentException();
         return value;
      }

      public static Object savedRequired(final Object value) {
         final boolean present = Objects.nonNull(value);
         if (!present)
            throw new IllegalArgumentException();
         return value;
      }

      public static Object savedFallback(final Object value) {
         final Object alias = value;
         final boolean missing = Objects.isNull(alias);
         return missing ? "" : alias;
      }

      public static Object nullReturn(final Object value) {
         return Objects.isNull(value) ? value : "";
      }

      public static Object reassigned(Object value, final Object replacement) {
         final boolean present = Objects.nonNull(value);
         value = replacement;
         // The original argument was tested; the new value may still be null on the true edge.
         return present ? value : "";
      }

      public static Object mixedReferences(final Object first, final Object second, final boolean useFirst) {
         final boolean present = useFirst ? Objects.nonNull(first) : Objects.nonNull(second);
         if (!present)
            throw new IllegalArgumentException();
         return first;
      }

      public static Object mixedPredicates(final Object value, final boolean invert) {
         final boolean accepted = invert ? Objects.isNull(value) : Objects.nonNull(value);
         if (!accepted)
            throw new IllegalArgumentException();
         return value;
      }

      public static Object overwrittenBoolean(boolean accepted, final Object value, final boolean test) {
         if (test) {
            accepted = Objects.nonNull(value);
         }
         if (!accepted)
            throw new IllegalArgumentException();
         return value;
      }

      public static Object caught(final Object value) {
         try {
            if (Objects.isNull(value))
               throw new IllegalArgumentException();
            return value;
         } catch (final IllegalArgumentException ex) {
            // A successful handler keeps null acceptable even though the normal arm rejects it.
            return null;
         }
      }
   }

   /** Distinguishes documented JDK factories from nullable alternatives and unrelated methods named of. */
   @NonNullByDefault({})
   @SuppressWarnings("null") // The caught null argument deliberately exercises a failing JDK factory call.
   public static final class FactoryFields {
      public static final List<?> EMPTY = List.of();
      public static final List<?> SINGLE = List.of("present");
      public static final List<?> VARARGS = List.of(new Object[] {"first", "second"});
      public static final Set<?> SET_EMPTY = Set.of();
      public static final Set<?> SET_SINGLE = Set.of("present");
      public static final Set<?> SET_TEN = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
      public static final Set<?> SET_VARARGS = Set.of(new Object[] {"first", "second"});
      public static final Map<?, ?> MAP_EMPTY = Map.of();
      public static final Map<?, ?> MAP_SINGLE = Map.of("key", "value");
      public static final Map<?, ?> MAP_TEN = Map.of(1, "one", 2, "two", 3, "three", 4, "four", 5, "five", 6, "six", 7, "seven", 8, "eight",
         9, "nine", 10, "ten");
      public static final Map<?, ?> MAP_ENTRIES = Map.ofEntries(Map.entry("key", "value"));
      public static final List<?> LIST_COPY = List.copyOf(Arrays.asList("present"));
      public static final Set<?> SET_COPY = Set.copyOf(Arrays.asList("present"));
      public static final Map<?, ?> MAP_COPY = Map.copyOf(Map.of("key", "value"));
      public static final List<?> CONDITIONAL = System.nanoTime() == 0 ? null : List.of();
      public static final List<?> CUSTOM = of();
      public static final List<?> CAUGHT;
      public static final Set<?> SET_CONDITIONAL = System.nanoTime() == 0 ? null : Set.of();
      public static final Map<?, ?> MAP_CUSTOM = mapOf();
      public static final Map<?, ?> MAP_CAUGHT;

      static {
         List<?> value;
         try {
            value = List.of(missingValue());
         } catch (final NullPointerException ex) {
            value = null;
         }
         CAUGHT = value;
         Map<?, ?> map;
         try {
            map = Map.of(missingValue(), "value");
         } catch (final NullPointerException ex) {
            map = null;
         }
         MAP_CAUGHT = map;
      }

      private static Object missingValue() {
         // Keep deliberate runtime failures compilable against the factories' non-null parameter annotations.
         return null;
      }

      public static List<?> empty() {
         return EMPTY;
      }

      public static List<?> of() {
         return null;
      }

      public static Map<?, ?> mapOf() {
         return null;
      }

      public static Set<?> set() {
         return SET_EMPTY;
      }

      public static Map<?, ?> map() {
         return MAP_EMPTY;
      }
   }

   /** Exercises non-null factory results without requiring non-null inputs or elements. */
   @NonNullByDefault({})
   @SuppressWarnings("null") // Null elements and caught factory failures are intentional regression inputs.
   public static final class AdditionalFactories {
      public static final Object REQUIRED = Objects.requireNonNull(System.getProperty("java.version"));
      public static final Object REQUIRED_MESSAGE = Objects.requireNonNull(System.getProperty("java.version"), "missing");
      public static final Object REQUIRED_SUPPLIER = Objects.requireNonNull(System.getProperty("java.version"), () -> "missing");
      public static final Object DEFAULT = Objects.requireNonNullElse(null, "fallback");
      public static final Object DEFAULT_SUPPLIER = Objects.requireNonNullElseGet(null, Object::new);
      public static final List<Object> EMPTY_LIST = Collections.emptyList();
      public static final Set<Object> EMPTY_SET = Collections.emptySet();
      public static final Map<Object, Object> EMPTY_MAP = Collections.emptyMap();
      public static final List<Object> SINGLETON_LIST = Collections.singletonList(null);
      public static final Set<Object> SINGLETON_SET = Collections.singleton(null);
      public static final Map<Object, Object> SINGLETON_MAP = Collections.singletonMap(null, null);
      public static final List<Object> ARRAY_LIST = Arrays.asList(new Object[] {null});
      public static final Object[] COPY = Arrays.copyOf(new Object[0], 1);
      public static final Object[] RANGE = Arrays.copyOfRange(new Object[0], 0, 1);
      public static final String[] TYPED_COPY = Arrays.copyOf(new Object[0], 1, String[].class);
      public static final String[] TYPED_RANGE = Arrays.copyOfRange(new Object[0], 0, 1, String[].class);
      public static final boolean[] BOOLEAN_COPY = Arrays.copyOf(new boolean[0], 1);
      public static final boolean[] BOOLEAN_RANGE = Arrays.copyOfRange(new boolean[0], 0, 1);
      public static final byte[] BYTE_COPY = Arrays.copyOf(new byte[0], 1);
      public static final byte[] BYTE_RANGE = Arrays.copyOfRange(new byte[0], 0, 1);
      public static final char[] CHAR_COPY = Arrays.copyOf(new char[0], 1);
      public static final char[] CHAR_RANGE = Arrays.copyOfRange(new char[0], 0, 1);
      public static final short[] SHORT_COPY = Arrays.copyOf(new short[0], 1);
      public static final short[] SHORT_RANGE = Arrays.copyOfRange(new short[0], 0, 1);
      public static final int[] INT_COPY = Arrays.copyOf(new int[0], 1);
      public static final int[] INT_RANGE = Arrays.copyOfRange(new int[0], 0, 1);
      public static final long[] LONG_COPY = Arrays.copyOf(new long[0], 1);
      public static final long[] LONG_RANGE = Arrays.copyOfRange(new long[0], 0, 1);
      public static final float[] FLOAT_COPY = Arrays.copyOf(new float[0], 1);
      public static final float[] FLOAT_RANGE = Arrays.copyOfRange(new float[0], 0, 1);
      public static final double[] DOUBLE_COPY = Arrays.copyOf(new double[0], 1);
      public static final double[] DOUBLE_RANGE = Arrays.copyOfRange(new double[0], 0, 1);
      public static final List<?> CONDITIONAL = System.nanoTime() == 0 ? null : Collections.emptyList();
      public static final List<?> CUSTOM = emptyList();
      public static final Object CAUGHT_DEFAULT;
      public static final Object[] CAUGHT_COPY;

      static {
         Object value;
         try {
            value = Objects.requireNonNullElse(null, FactoryFields.missingValue());
         } catch (final NullPointerException ex) {
            value = null;
         }
         CAUGHT_DEFAULT = value;
         Object[] copy;
         try {
            copy = Arrays.copyOf(new Object[0], -1);
         } catch (final NegativeArraySizeException ex) {
            copy = null;
         }
         CAUGHT_COPY = copy;
      }

      public static List<?> emptyList() {
         return null;
      }

      public static Object defaultValue(final Object value, final Object fallback) {
         return Objects.requireNonNullElse(value, fallback);
      }

      public static Object suppliedDefault(final Object value, final Supplier<Object> fallback) {
         return Objects.requireNonNullElseGet(value, fallback);
      }

      public static Object originalAfterDefault(final Object value) {
         Objects.requireNonNullElse(value, "fallback");
         // A non-null replacement does not change the caller's original reference.
         return value;
      }

      public static Object caughtDefault(final Object value, final Object fallback) {
         try {
            return Objects.requireNonNullElse(value, fallback);
         } catch (final NullPointerException ex) {
            return null;
         }
      }

      public static Object copiedElement() {
         // Padding makes this element null even though the copied array is non-null.
         return Arrays.copyOf(new Object[0], 1)[0];
      }
   }

   /** Checks native getClass results without treating exception handlers or similarly named overloads as non-null. */
   @NonNullByDefault({})
   public static final class RuntimeClasses {
      public static Class<?> object(final Object value) {
         return value.getClass();
      }

      public static Class<?> inherited(final ReturnHelpers value) {
         return value.getClass();
      }

      public static Class<?> array(final Object[] value) {
         return value.getClass();
      }

      public static Class<?> caught(final Object value) {
         try {
            return value.getClass();
         } catch (final NullPointerException ex) {
            return null;
         }
      }

      public static Class<?> overloaded(final RuntimeClasses value) {
         return value.getClass(0);
      }

      @SuppressWarnings("unused") // The overload exists only to keep name-only call matching from qualifying its null result.
      public Class<?> getClass(final int ignored) {
         return null;
      }
   }

   @NonNullByDefault({})
   public static class ReturnHelpers {
      public static Object identity(final Object value) {
         return value;
      }

      public static Object alternate(final Object value, final boolean useValue) {
         return useValue ? value : new Object();
      }

      @SuppressWarnings("unused")
      public static Object second(final Object first, final long wide, final Object second) {
         return second;
      }

      public final Object fixed(final Object value) {
         return value;
      }

      public Object virtual(final Object value) {
         return value;
      }

      public static Object nullable(final Object value, final boolean missing) {
         return missing ? null : value;
      }

      public static Object nonNullFallback(final Object value) {
         return value == null ? new Object() : value;
      }

      public static Object conditionalOverwrite(Object value, final Object replacement, final boolean replace) {
         if (replace) {
            value = replacement;
         }
         return value;
      }

      @SuppressWarnings({"null", "unused"}) // The impossible assignment must remain in bytecode to separate flow and structural provenance.
      public static Object unreachableOverwrite(Object value, final Object replacement) {
         final Object marker = new Object();
         if (marker == null) {
            value = replacement;
         }
         return value;
      }

      @SuppressWarnings({"null", "unused"}) // An independent return prevents a whole-method identity shortcut from hiding the same defect.
      public static Object unreachableOverwriteWithAlternative(Object value, final Object replacement, final boolean useValue) {
         final Object marker = new Object();
         if (marker == null) {
            value = replacement;
         }
         return useValue ? value : new Object();
      }

      public static Object overwrite(Object value, final Object replacement) {
         value = replacement;
         return value;
      }

      public static Object recursive(final Object value, final boolean recurse) {
         return recurse ? Returns.recursive(value) : value;
      }
   }

   @NonNullByDefault({})
   public static final class Returns {
      public static Object constant() {
         return ReturnHelpers.identity("present");
      }

      public static Object forwarding(final Object value) {
         return ReturnHelpers.identity(value);
      }

      public static Object nested(final Object value) {
         return ReturnHelpers.identity(ReturnHelpers.identity(value));
      }

      public static Object reordered(final Object first, final Object second) {
         return ReturnHelpers.second(second, 1L, first);
      }

      public static Object alternate(final Object value, final boolean useValue) {
         return ReturnHelpers.alternate(value, useValue);
      }

      public static Object nullArgument() {
         return ReturnHelpers.identity(null);
      }

      public static Object nestedNullArgument() {
         return ReturnHelpers.identity(ReturnHelpers.identity(null));
      }

      public static Object reorderedNullArgument() {
         return ReturnHelpers.second("present", 1L, null);
      }

      public static Object nullArgumentWithFallback() {
         return ReturnHelpers.nonNullFallback(null);
      }

      public static Object nullArgumentWithAlternative() {
         // The helper has a PolyNull dependency, but this call selects its non-null alternative.
         return ReturnHelpers.alternate(null, false);
      }

      public static Object fixedNullArgument(final ReturnHelpers helper) {
         return helper.fixed(null);
      }

      public static Object virtualNullArgument(final ReturnHelpers helper) {
         return helper.virtual(null);
      }

      public static Object nullableHelper(final Object value, final boolean missing) {
         return ReturnHelpers.nullable(value, missing);
      }

      public static Object overwrittenHelper(final Object value, final boolean replace) {
         // Passing a constant replacement cannot qualify the untouched value returned by the helper's other branch.
         return ReturnHelpers.conditionalOverwrite(value, "present", replace);
      }

      public static Object overwrittenArgument(Object value, final Object replacement, final boolean replace) {
         if (replace) {
            value = replacement;
         }
         return ReturnHelpers.identity(value);
      }

      public static Object unreachableOverwrite(final Object value) {
         return ReturnHelpers.unreachableOverwrite(value, "present");
      }

      public static Object unreachableOverwriteWithAlternative(final Object value, final boolean useValue) {
         return ReturnHelpers.unreachableOverwriteWithAlternative(value, "present", useValue);
      }

      public static Object overwrittenConstant(final Object value) {
         return ReturnHelpers.overwrite(value, "present");
      }

      public static Object overwrittenForwarding(final Object value, final Object replacement) {
         return ReturnHelpers.overwrite(value, replacement);
      }

      public static Object fixed(final ReturnHelpers receiver, final Object value) {
         return receiver.fixed(value);
      }

      public static Object exact(final Object value) {
         return new ReturnHelpers().virtual(value);
      }

      public static Object virtual(final ReturnHelpers receiver, final Object value) {
         return receiver.virtual(value);
      }

      public static Object conditionalReceiver(ReturnHelpers receiver, final boolean replace) {
         if (replace) {
            receiver = new ReturnHelpers();
         }
         return receiver.virtual("present");
      }

      public static Object guardedReceiver(ReturnHelpers receiver, final boolean replace) {
         if (receiver == null)
            throw new IllegalArgumentException();
         if (replace) {
            receiver = new ReturnHelpers();
         }
         // A non-null receiver can still be a subclass whose override returns null.
         return receiver.virtual("present");
      }

      public static Object aliasedReceiver(ReturnHelpers receiver, final boolean replace) {
         if (replace) {
            receiver = new ReturnHelpers();
         }
         final ReturnHelpers alias = receiver;
         return alias.virtual("present");
      }

      public static Object reassignedReceiver(ReturnHelpers receiver) {
         // An unconditional overwrite does remove the original receiver, so this exact-allocation proof must survive.
         receiver = new ReturnHelpers();
         return receiver.virtual("present");
      }

      public static Object recursive(final Object value) {
         return ReturnHelpers.recursive(value, true);
      }

      public static Object lambda() {
         return Functions.lambda();
      }

      public static Object typed(final Object value) {
         return TypeTests.fallback(value);
      }
   }

   /** Supplies a valid dynamic call site whose result is null, unlike the standard lambda factories. */
   @NonNullByDefault({})
   public static final class CustomBootstrap {
      @SuppressWarnings("unused")
      public static CallSite bootstrap(final MethodHandles.Lookup lookup, final String name, final MethodType type) {
         return new ConstantCallSite(MethodHandles.constant(Supplier.class, null).asType(type));
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDirectDereferenceRequirements() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Dereferences.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"length", "element", "wideElement", "storeElement", "storeWideElement", "field",
            "storeField", "lock", "alias", "finallyCompletes", "delegated"}) {
            assertRequirements(analyzer, owner, method, 0);
         }
         assertRequirements(analyzer, owner, "storeWideField", 1);
         // Reading a field or array element proves its container non-null, not the value read from it.
         assertReturn(analyzer, owner, "element", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "field", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDereferenceBoundaries() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Dereferences.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"conditional", "nullable", "merged", "caught", "rethrown", "finallyReturns"}) {
            assertRequirements(analyzer, owner, method);
         }
         assertThat(analyzer.determineDefinitelyNullableMethodParameters(owner.getMethodInfo("nullable").get(0))).containsExactly(0);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testLambdaCreationAndInvocation() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Functions.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"lambda", "serializableLambda", "capturing", "methodReference", "concat",
            "staticLambda"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
         assertRequirements(analyzer, owner, "capturing");
         assertRequirements(analyzer, owner, "methodReference", 0);
         assertReturn(analyzer, owner, "invocationResult", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "conditional", Nullability.DEFINITELY_NULL);
         assertThat(Functions.lambda()).isNotNull();
         assertThat(Functions.lambda().get()).isNull();
         final var field = owner.getFieldInfo("STATIC_LAMBDA");
         assert field != null;
         assertThat(analyzer.isDefinitelyNonNullStaticField(field)).isTrue();
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDynamicBootstrapContracts(@TempDir final Path directory) throws Exception {
      final String owner = "test/CustomDynamicFactory";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
      final var factory = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "factory", "()Ljava/util/function/Supplier;", null,
         null);
      factory.visitCode();
      factory.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", new Handle(Opcodes.H_INVOKESTATIC, CustomBootstrap.class
         .getName().replace('.', '/'), "bootstrap",
         "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false));
      factory.visitInsn(Opcodes.ARETURN);
      factory.visitMaxs(0, 0);
      factory.visitEnd();
      /* ECJ uses StringBuilder for source concatenation here. Emit the standard bootstrap explicitly so this test
       * exercises invokedynamic, including a call-site name different from the bootstrap method's name. */
      final var concatBootstrap = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants",
         "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
               + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);
      final var concat = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "concat", "(I)Ljava/lang/String;", null, null);
      concat.visitCode();
      concat.visitVarInsn(Opcodes.ILOAD, 0);
      concat.visitInvokeDynamicInsn("text", "(I)Ljava/lang/String;", concatBootstrap, "value=\u0001");
      concat.visitInsn(Opcodes.ARETURN);
      concat.visitMaxs(0, 0);
      concat.visitEnd();
      writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CONCAT", "Ljava/lang/String;", null, null).visitEnd();
      final var initializer = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
      initializer.visitCode();
      initializer.visitIntInsn(Opcodes.BIPUSH, 7);
      initializer.visitInvokeDynamicInsn("text", "(I)Ljava/lang/String;", concatBootstrap, "value=\u0001");
      initializer.visitFieldInsn(Opcodes.PUTSTATIC, owner, "CONCAT", "Ljava/lang/String;");
      initializer.visitInsn(Opcodes.RETURN);
      initializer.visitMaxs(0, 0);
      initializer.visitEnd();
      writer.visitEnd();
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, writer.toByteArray());
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         assertThat(loader.loadClass(owner.replace('/', '.')).getMethod("factory").invoke(null)).isNull();
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(classInfo);
         assertReturn(analyzer, classInfo, "factory", Nullability.UNKNOWN);
         assertReturn(analyzer, classInfo, "concat", Nullability.NEVER_NULL);
         final var concatField = classInfo.getFieldInfo("CONCAT");
         assert concatField != null;
         assertThat(analyzer.isDefinitelyNonNullStaticField(concatField)).isTrue();
         assertThat(loader.loadClass(owner.replace('/', '.')).getField("CONCAT").get(null)).isEqualTo("value=7");
      }
   }

   @Test
   @SuppressWarnings("null")
   void testInstanceOfRefinesOnlyTheTestedValue() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(TypeTests.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"fallback", "required", "savedTest", "savedRequired", "loop"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
         assertRequirements(analyzer, owner, "required", 0);
         assertRequirements(analyzer, owner, "savedRequired", 0);
         assertRequirements(analyzer, owner, "fallback");
         assertRequirements(analyzer, owner, "mergedTest");
         assertRequirements(analyzer, owner, "mergedRequired");
         assertRequirements(analyzer, owner, "overwrittenBoolean");
         assertReturn(analyzer, owner, "overwrittenBoolean", Nullability.POLY_NULL, 1);
         assertReturn(analyzer, owner, "mergedRequired", Nullability.POLY_NULL, 0);
         assertReturn(analyzer, owner, "falseBranch", Nullability.POLY_NULL, 0);
         assertReturn(analyzer, owner, "reassigned", Nullability.POLY_NULL, 1);
         assertReturn(analyzer, owner, "mergedTest", Nullability.POLY_NULL, 0);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNullPredicateContracts() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(NullPredicates.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"nonNullFallback", "isNullFallback", "required", "savedRequired", "savedFallback"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
         for (final String method : new String[] {"required", "savedRequired"}) {
            assertRequirements(analyzer, owner, method, 0);
         }
         for (final String method : new String[] {"nonNullFallback", "isNullFallback", "savedFallback"}) {
            assertRequirements(analyzer, owner, method);
            assertThat(analyzer.determineDefinitelyNullableMethodParameters(owner.getMethodInfo(method).get(0))).as(method).containsExactly(
               0);
         }
         assertReturn(analyzer, owner, "nullReturn", Nullability.POLY_NULL, 0);
         assertReturn(analyzer, owner, "reassigned", Nullability.POLY_NULL, 1);
         assertReturn(analyzer, owner, "mixedReferences", Nullability.POLY_NULL, 0);
         assertReturn(analyzer, owner, "mixedPredicates", Nullability.POLY_NULL, 0);
         assertReturn(analyzer, owner, "overwrittenBoolean", Nullability.POLY_NULL, 1);
         assertReturn(analyzer, owner, "caught", Nullability.DEFINITELY_NULL);
         for (final String method : new String[] {"reassigned", "mixedReferences", "mixedPredicates", "overwrittenBoolean", "caught"}) {
            assertRequirements(analyzer, owner, method);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testStaticInitializerFactoryContracts() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(FactoryFields.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String field : new String[] {"EMPTY", "SINGLE", "VARARGS", "SET_EMPTY", "SET_SINGLE", "SET_TEN", "SET_VARARGS",
            "MAP_EMPTY", "MAP_SINGLE", "MAP_TEN", "MAP_ENTRIES", "LIST_COPY", "SET_COPY", "MAP_COPY"}) {
            assertThat(analyzer.isDefinitelyNonNullStaticField(Objects.requireNonNull(owner.getFieldInfo(field)))).as(field).isTrue();
         }
         for (final String field : new String[] {"CONDITIONAL", "CUSTOM", "CAUGHT", "SET_CONDITIONAL", "MAP_CUSTOM", "MAP_CAUGHT"}) {
            // One successful factory call cannot qualify other writes, including a handler's null assignment.
            assertThat(analyzer.isDefinitelyNonNullStaticField(Objects.requireNonNull(owner.getFieldInfo(field)))).as(field).isFalse();
         }
         assertReturn(analyzer, owner, "empty", Nullability.NEVER_NULL);
         assertReturn(analyzer, owner, "set", Nullability.NEVER_NULL);
         assertReturn(analyzer, owner, "map", Nullability.NEVER_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testAdditionalStaticFactoryContracts() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(AdditionalFactories.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         final Set<String> nullableFields = Set.of("CONDITIONAL", "CUSTOM", "CAUGHT_DEFAULT", "CAUGHT_COPY");
         for (final var field : owner.getDeclaredFieldInfo()) {
            assertThat(analyzer.isDefinitelyNonNullStaticField(field)).as(field.getName()).isEqualTo(!nullableFields.contains(field
               .getName()));
         }
         for (final String method : new String[] {"defaultValue", "suppliedDefault"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
            // Either argument can be null on a successful call; the other path supplies the non-null result.
            assertRequirements(analyzer, owner, method);
         }
         assertReturn(analyzer, owner, "originalAfterDefault", Nullability.POLY_NULL, 0);
         assertRequirements(analyzer, owner, "originalAfterDefault");
         assertReturn(analyzer, owner, "caughtDefault", Nullability.DEFINITELY_NULL);
         assertReturn(analyzer, owner, "copiedElement", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testFactoryContractsRequireExactSignatures(@TempDir final Path directory) throws IOException {
      final String[][] calls = { //
         {"java/util/Set", "of", "(Ljava/lang/Object;)Ljava/util/List;"}, //
         {"java/util/Set", "of", "(" + "Ljava/lang/Object;".repeat(11) + ")Ljava/util/Set;"}, //
         {"java/util/Set", "copyOf", "(Ljava/util/Set;)Ljava/util/Set;"}, //
         {"java/util/Map", "of", "(Ljava/lang/Object;)Ljava/util/Map;"}, //
         {"java/util/Map", "of", "([Ljava/lang/Object;)Ljava/util/Map;"}, //
         {"java/util/Map", "of", "(" + "Ljava/lang/Object;".repeat(22) + ")Ljava/util/Map;"}, //
         {"java/util/Map", "ofEntries", "([Ljava/lang/Object;)Ljava/util/Map;"}, //
         {"java/util/Map", "copyOf", "(Ljava/util/Collection;)Ljava/util/Map;"}, //
         {"java/util/List", "ofEntries", "([Ljava/util/Map$Entry;)Ljava/util/List;"}, //
         {"java/util/Objects", "requireNonNull", "(Ljava/lang/Object;I)Ljava/lang/Object;"}, //
         {"java/util/Objects", "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"}, //
         {"java/util/Objects", "requireNonNullElseGet", "(Ljava/lang/Object;Ljava/util/concurrent/Callable;)Ljava/lang/Object;"}, //
         {"java/util/Collections", "emptyList", "()Ljava/util/Collection;"}, //
         {"java/util/Collections", "singleton", "(Ljava/lang/Object;)Ljava/util/List;"}, //
         {"java/util/Collections", "singletonList", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"}, //
         {"java/util/Collections", "singletonMap", "(Ljava/lang/Object;)Ljava/util/Map;"}, //
         {"java/util/Arrays", "asList", "(Ljava/lang/Object;)Ljava/util/List;"}, //
         {"java/util/Arrays", "asList", "([Ljava/lang/String;)Ljava/util/List;"}, //
         {"java/util/Arrays", "copyOf", "([Ljava/lang/String;I)[Ljava/lang/String;"}, //
         {"java/util/Arrays", "copyOf", "([II)[J"}, //
         {"java/util/Arrays", "copyOf", "([IILjava/lang/Class;)[I"}, //
         {"java/util/Arrays", "copyOfRange", "([Ljava/lang/Object;I)[Ljava/lang/Object;"}, //
         {"java/util/Arrays", "copyOfRange", "([[III)[[I"}, //
      };
      final String ownerName = "test/InvalidFactories";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, ownerName, null, "java/lang/Object", null);
      for (int index = 0; index < calls.length; index++) {
         final String[] call = calls[index];
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invalid" + index, "()Ljava/lang/Object;", null,
            null);
         method.visitCode();
         for (final Type argument : Type.getArgumentTypes(call[2])) {
            // Keep operand types valid so only the nonexistent overload is under test.
            method.visitInsn(argument.getSort() == Type.INT ? Opcodes.ICONST_0 : Opcodes.ACONST_NULL);
         }
         // These unresolved overloads are never executed; a familiar owner and name must not turn them into known contracts.
         final boolean interfaceOwner = "java/util/List".equals(call[0]) || "java/util/Set".equals(call[0]) || "java/util/Map".equals(
            call[0]);
         method.visitMethodInsn(Opcodes.INVOKESTATIC, call[0], call[1], call[2], interfaceOwner);
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(ownerName + ".class"), writer.toByteArray());
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         final ClassInfo owner = scan.getClassInfo(ownerName.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (int index = 0; index < calls.length; index++) {
            assertReturn(analyzer, owner, "invalid" + index, Nullability.UNKNOWN);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testGetClassCallContracts() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(RuntimeClasses.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"object", "inherited", "array"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
            assertRequirements(analyzer, owner, method, 0);
         }
         assertReturn(analyzer, owner, "caught", Nullability.DEFINITELY_NULL);
         assertRequirements(analyzer, owner, "caught");
         assertReturn(analyzer, owner, "overloaded", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNullForwardingUsesExactReturns() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Returns.class.getName());
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertReturn(analyzer, owner, "constant", Nullability.NEVER_NULL);
            }
            for (final String method : new String[] {"nullArgument", "nestedNullArgument", "reorderedNullArgument", "fixedNullArgument"}) {
               assertReturn(analyzer, owner, method, Nullability.DEFINITELY_NULL);
            }
            // Null input alone does not invert a PolyNull implication or close an overridable call's dispatch.
            assertReturn(analyzer, owner, "nullArgumentWithFallback", Nullability.NEVER_NULL);
            assertReturn(analyzer, owner, "nullArgumentWithAlternative", Nullability.UNKNOWN);
            assertReturn(analyzer, owner, "virtualNullArgument", Nullability.UNKNOWN);
            assertReturn(analyzer, owner, "constant", Nullability.NEVER_NULL);
            assertReturn(analyzer, owner, "forwarding", Nullability.POLY_NULL, 0);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNullForwardingDepthLimitPreservesLocalEvidence(@TempDir final Path directory) throws Exception {
      final String owner = "test/DeepNullForwarding";
      final String descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;";
      final int lastMethod = 140;
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
      for (int i = 0; i <= lastMethod; i++) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "chain" + i, descriptor, null, null);
         method.visitCode();
         if (i < lastMethod) {
            /* The discarded call still enters the value interpreter. Depth exhaustion there must escape ASM's wrapper
             * without caching a partial summary or discarding the independent return of the entry argument. */
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "chain" + (i + 1), descriptor, false);
            method.visitInsn(Opcodes.POP);
         }
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      for (final String name : new String[] {"deep", "localNull", "shallow"}) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
         method.visitCode();
         method.visitInsn(Opcodes.ACONST_NULL);
         method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "chain" + ("shallow".equals(name) ? lastMethod - 1 : 0), descriptor, false);
         if ("localNull".equals(name)) {
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.ACONST_NULL);
         }
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(owner + ".class"), writer.toByteArray());
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         assertThat(loader.loadClass(owner.replace('/', '.')).getMethod("deep").invoke(null)).isNull();
         final ClassInfo info = scan.getClassInfo(owner.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(info);
         assertReturn(analyzer, info, "deep", Nullability.UNKNOWN);
         assertReturn(analyzer, info, "localNull", Nullability.DEFINITELY_NULL);
         assertReturn(analyzer, info, "shallow", Nullability.DEFINITELY_NULL);
         assertReturn(analyzer, info, "chain0", Nullability.POLY_NULL, 0);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testReturnDependenciesAcrossClasses() {
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Returns.class.getName());
         final var resolver = new BytecodeAnalyzer.MethodSummaryResolver(new BytecodeAnalyzer.StaticFieldResolver(scan));
         final var analyzer = new BytecodeAnalyzer(owner, resolver);
         for (final String method : new String[] {"constant", "lambda", "typed"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
         for (final String method : new String[] {"forwarding", "nested", "reordered", "alternate", "exact"}) {
            assertReturn(analyzer, owner, method, Nullability.POLY_NULL, 0);
         }
         assertReturn(analyzer, owner, "fixed", Nullability.POLY_NULL, 1);
         // Exact argument forwarding now preserves a null constant, just as it already preserves a non-null constant.
         assertReturn(analyzer, owner, "nullArgument", Nullability.DEFINITELY_NULL);
         assertReturn(analyzer, owner, "nullableHelper", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "overwrittenHelper", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "overwrittenArgument", Nullability.UNKNOWN);
         // A definite overwrite discards the entry value, so the replacement's dependency must remain usable.
         assertReturn(analyzer, owner, "overwrittenConstant", Nullability.NEVER_NULL);
         assertReturn(analyzer, owner, "overwrittenForwarding", Nullability.POLY_NULL, 1);
         assertReturn(analyzer, owner, "recursive", Nullability.UNKNOWN);
         // Exact allocation warmed virtual()'s body, but an arbitrary receiver can still override that body.
         assertReturn(analyzer, owner, "virtual", Nullability.UNKNOWN);
         // Reusing a helper summary must remap it afresh instead of caching the first caller's constant argument.
         assertReturn(analyzer, owner, "constant", Nullability.NEVER_NULL);
         assertReturn(analyzer, owner, "forwarding", Nullability.POLY_NULL, 0);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testUnreachableOverwriteDoesNotQualifyHelperCallers() {
      assertThat(Returns.unreachableOverwrite(null)).isNull();
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Returns.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         // The unused constant replacement cannot establish a non-null return when the original argument survives.
         assertReturn(analyzer, owner, "unreachableOverwrite", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testUnreachableOverwriteWithAlternativeDoesNotQualifyHelperCallers() {
      assertThat(Returns.unreachableOverwriteWithAlternative(null, true)).isNull();
      assertThat(Returns.unreachableOverwriteWithAlternative(null, false)).isNotNull();
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Returns.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertReturn(analyzer, owner, "unreachableOverwriteWithAlternative", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testConditionalReceiverKeepsRuntimeTypeUncertainty() throws ReflectiveOperationException {
      final var nullableReceiver = new ReturnHelpers() {
         @Override
         @NonNullByDefault({}) // The counterexample must retain the helper's unconstrained argument and nullable runtime result.
         public Object virtual(final Object value) {
            return null;
         }
      };
      final String[] conditionalMethods = {"conditionalReceiver", "guardedReceiver", "aliasedReceiver"};
      for (final String method : conditionalMethods) {
         final var reflected = Returns.class.getMethod(method, ReturnHelpers.class, boolean.class);
         assertThat(reflected.invoke(null, nullableReceiver, false)).as(method).isNull();
         assertThat(reflected.invoke(null, nullableReceiver, true)).as(method).isEqualTo("present");
      }
      try (ScanResult scan = scanFixtures()) {
         final ClassInfo owner = scan.getClassInfo(Returns.class.getName());
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertReturn(analyzer, owner, "reassignedReceiver", Nullability.NEVER_NULL);
            }
            // Each call must establish its receiver type even when the same helper body already has a cached summary.
            for (final String method : conditionalMethods) {
               assertReturn(analyzer, owner, method, Nullability.UNKNOWN);
            }
            assertReturn(analyzer, owner, "reassignedReceiver", Nullability.NEVER_NULL);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testCollapsedNullEdgesDoNotQualifyHelperCallers(@TempDir final Path directory) throws Exception {
      final String helperOwner = "test/NullEdgeHelper";
      final String callerOwner = "test/NullEdgeCaller";
      final String descriptor = "(Ljava/lang/Object;)Ljava/lang/Object;";
      final var helpers = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      final var callers = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      helpers.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, helperOwner, null, "java/lang/Object", null);
      callers.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, callerOwner, null, "java/lang/Object", null);
      for (final boolean collapsed : new boolean[] {true, false}) {
         final String name = collapsed ? "collapsed" : "guarded";
         final var helper = helpers.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
         helper.visitCode();
         final var nullReturn = new Label();
         helper.visitVarInsn(Opcodes.ALOAD, 0);
         helper.visitJumpInsn(Opcodes.IFNULL, nullReturn);
         if (!collapsed) {
            helper.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            helper.visitInsn(Opcodes.DUP);
            helper.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            helper.visitInsn(Opcodes.ARETURN);
         }
         // With no intervening code, both branch outcomes reach this null return regardless of the argument.
         helper.visitLabel(nullReturn);
         helper.visitInsn(Opcodes.ACONST_NULL);
         helper.visitInsn(Opcodes.ARETURN);
         helper.visitMaxs(0, 0);
         helper.visitEnd();
         final var caller = callers.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
         caller.visitCode();
         caller.visitLdcInsn("present");
         caller.visitMethodInsn(Opcodes.INVOKESTATIC, helperOwner, name, descriptor, false);
         caller.visitInsn(Opcodes.ARETURN);
         caller.visitMaxs(0, 0);
         caller.visitEnd();
      }
      helpers.visitEnd();
      callers.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(helperOwner + ".class"), helpers.toByteArray());
      Files.write(directory.resolve(callerOwner + ".class"), callers.toByteArray());
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         final Class<?> callerType = loader.loadClass(callerOwner.replace('/', '.'));
         assertThat(callerType.getMethod("collapsed").invoke(null)).isNull();
         assertThat(callerType.getMethod("guarded").invoke(null)).isNotNull();
         final ClassInfo callerInfo = scan.getClassInfo(callerOwner.replace('/', '.'));
         final var resolver = new BytecodeAnalyzer.StaticFieldResolver(scan);
         final var callerAnalyzer = new BytecodeAnalyzer(callerInfo, resolver);
         assertReturn(callerAnalyzer, callerInfo, "collapsed", Nullability.UNKNOWN);
         assertReturn(callerAnalyzer, callerInfo, "guarded", Nullability.NEVER_NULL);
         final ClassInfo helperInfo = scan.getClassInfo(helperOwner.replace('/', '.'));
         final var helperAnalyzer = new BytecodeAnalyzer(helperInfo, resolver);
         assertReturn(helperAnalyzer, helperInfo, "collapsed", Nullability.DEFINITELY_NULL);
         assertReturn(helperAnalyzer, helperInfo, "guarded", Nullability.POLY_NULL, 0);
      }
   }

   @Test
   void testGeneratedEvidenceAndStoredContracts(@TempDir final Path directory) throws IOException {
      final Path input = directory.resolve("input");
      final Path output = directory.resolve("output");
      final var stored = new EEAFile(Dereferences.class.getName());
      stored.addMember("length", "([I)I").annotatedSignature.value = "([0I)I";
      stored.addMember("element",
         "([Ljava/lang/Object;)Ljava/lang/Object;").annotatedSignature.value = "([Ljava/lang/Object;)L0java/lang/Object;";
      stored.save(input, SaveOption.REPLACE_EXISTING);
      final var config = new EEAGenerator.Config(output, getClass().getPackageName());
      config.inputDirs.add(input);
      config.classFilter = info -> info.getName().equals(Dereferences.class.getName()) || info.getName().equals(Functions.class.getName())
            || info.getName().equals(Returns.class.getName()) || info.getName().equals(TypeTests.class.getName()) || info.getName().equals(
               NullPredicates.class.getName()) || info.getName().equals(FactoryFields.class.getName()) || info.getName().equals(
                  AdditionalFactories.class.getName()) || info.getName().equals(RuntimeClasses.class.getName());
      for (final var mode : EEAGenerator.GenerationMode.values()) {
         config.generationMode = mode;
         EEAGenerator.generateEEAFiles(config);
         final EEAFile dereferences = EEAFile.load(output, Dereferences.class.getName());
         assertSignature(dereferences, "length", mode == EEAGenerator.GenerationMode.FULL ? "([1I)I" : "([0I)I");
         assertSignature(dereferences, "element", "([1Ljava/lang/Object;)L0java/lang/Object;");
         assertSignature(EEAFile.load(output, Functions.class.getName()), "lambda", "()L1java/util/function/Supplier<Ljava/lang/String;>;");
         assertSignature(EEAFile.load(output, TypeTests.class.getName()), "fallback", "(Ljava/lang/Object;)L1java/lang/String;");
         assertSignature(EEAFile.load(output, Returns.class.getName()), "constant", "()L1java/lang/Object;");
         assertSignature(EEAFile.load(output, Returns.class.getName()), "nullArgument", "()L0java/lang/Object;");
         assertSignature(EEAFile.load(output, NullPredicates.class.getName()), "required", "(L1java/lang/Object;)L1java/lang/Object;");
         assertSignature(EEAFile.load(output, NullPredicates.class.getName()), "isNullFallback",
            "(L0java/lang/Object;)L1java/lang/Object;");
         assertSignature(EEAFile.load(output, FactoryFields.class.getName()), "EMPTY", "L1java/util/List<*>;");
         assertSignature(EEAFile.load(output, FactoryFields.class.getName()), "SET_EMPTY", "L1java/util/Set<*>;");
         assertSignature(EEAFile.load(output, FactoryFields.class.getName()), "MAP_COPY", "L1java/util/Map<**>;");
         final EEAFile additionalFactories = EEAFile.load(output, AdditionalFactories.class.getName());
         assertSignature(additionalFactories, "DEFAULT", "L1java/lang/Object;");
         assertSignature(additionalFactories, "SINGLETON_LIST", "L1java/util/List<Ljava/lang/Object;>;");
         assertSignature(additionalFactories, "COPY", "[1Ljava/lang/Object;");
         assertSignature(additionalFactories, "TYPED_RANGE", "[1Ljava/lang/String;");
         assertSignature(additionalFactories, "CAUGHT_COPY", "[Ljava/lang/Object;");
         assertSignature(additionalFactories, "defaultValue", "(Ljava/lang/Object;Ljava/lang/Object;)L1java/lang/Object;");
         assertSignature(EEAFile.load(output, RuntimeClasses.class.getName()), "object", "(L1java/lang/Object;)L1java/lang/Class<*>;");
         final var forwarding = EEAFile.load(output, Returns.class.getName()).getClassMembers().filter(member -> member.name.value.equals(
            "forwarding")).findFirst().orElseThrow();
         assertThat(forwarding.annotatedSignature.value).isEqualTo("(Ljava/lang/Object;)Ljava/lang/Object;");
         assertThat(forwarding.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull)");
      }
   }

   @SuppressWarnings("null") // ClassGraph's scan result is unannotated but present after successful scanning.
   private static ScanResult scanFixtures() {
      return new ClassGraph().enableAllInfo().acceptClasses(Dereferences.class.getName(), Functions.class.getName(), TypeTests.class
         .getName(), ReturnHelpers.class.getName(), Returns.class.getName(), NullPredicates.class.getName(), FactoryFields.class.getName(),
         AdditionalFactories.class.getName(), RuntimeClasses.class.getName()).scan();
   }

   @SuppressWarnings("null")
   private static void assertRequirements(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method,
         final int... expectedIndexes) {
      assertThat(analyzer.determineDefinitelyNonNullMethodParameters(owner.getMethodInfo(method).get(0))).as(method)
         .containsExactlyInAnyOrder(Arrays.stream(expectedIndexes).boxed().toArray(Integer[]::new));
   }

   @SuppressWarnings("null")
   private static void assertReturn(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method, final Nullability expected,
         final int... parameterIndexes) {
      final var result = analyzer.determineMethodReturnAnalysis(owner.getMethodInfo(method).get(0));
      assertThat(result.getNullability()).as(method).isEqualTo(expected);
      assertThat(result.getNullDependentParameterIndexes()).as(method + " dependencies").containsExactlyInAnyOrder(Arrays.stream(
         parameterIndexes).boxed().toArray(Integer[]::new));
   }

   private static void assertSignature(final EEAFile file, final String method, final String signature) {
      assertThat(file.getClassMembers().filter(member -> member.name.value.equals(method)).findFirst()
         .orElseThrow().annotatedSignature.value).as(method).isEqualTo(signature);
   }
}
