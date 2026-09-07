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
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

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
         assertReturn(analyzer, owner, "nullArgument", Nullability.UNKNOWN);
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
            || info.getName().equals(Returns.class.getName()) || info.getName().equals(TypeTests.class.getName());
      for (final var mode : EEAGenerator.GenerationMode.values()) {
         config.generationMode = mode;
         EEAGenerator.generateEEAFiles(config);
         final EEAFile dereferences = EEAFile.load(output, Dereferences.class.getName());
         assertSignature(dereferences, "length", mode == EEAGenerator.GenerationMode.FULL ? "([1I)I" : "([0I)I");
         assertSignature(dereferences, "element", "([1Ljava/lang/Object;)L0java/lang/Object;");
         assertSignature(EEAFile.load(output, Functions.class.getName()), "lambda", "()L1java/util/function/Supplier<Ljava/lang/String;>;");
         assertSignature(EEAFile.load(output, TypeTests.class.getName()), "fallback", "(Ljava/lang/Object;)L1java/lang/String;");
         assertSignature(EEAFile.load(output, Returns.class.getName()), "constant", "()L1java/lang/Object;");
         final var forwarding = EEAFile.load(output, Returns.class.getName()).getClassMembers().filter(member -> member.name.value.equals(
            "forwarding")).findFirst().orElseThrow();
         assertThat(forwarding.annotatedSignature.value).isEqualTo("(Ljava/lang/Object;)Ljava/lang/Object;");
         assertThat(forwarding.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull)");
      }
   }

   @SuppressWarnings("null") // ClassGraph's scan result is unannotated but present after successful scanning.
   private static ScanResult scanFixtures() {
      return new ClassGraph().enableAllInfo().acceptClasses(Dereferences.class.getName(), Functions.class.getName(), TypeTests.class
         .getName(), ReturnHelpers.class.getName(), Returns.class.getName()).scan();
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
