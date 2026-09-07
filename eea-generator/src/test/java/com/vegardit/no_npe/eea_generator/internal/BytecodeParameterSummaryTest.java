/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import com.vegardit.no_npe.eea_generator.EEAFile;
import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.EEAGenerator;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Verifies delegated parameter requirements, conservative call boundaries, and cache-independent bounded analysis.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeParameterSummaryTest {

   @NonNullByDefault({}) // These fixtures describe runtime checks, without compiler nullness assumptions removing their branches.
   static class Helpers {
      Helpers(final Object value) {
         requireValue(value);
      }

      static void requireValue(final Object value) {
         if (value == null)
            throw new IllegalArgumentException();
      }

      static int requirePair(final Object first, final Object second) {
         requireValue(first);
         requireValue(second);
         return 1;
      }

      static void acceptsNull(final Object value) {
         if (value == null)
            return;
         value.toString();
      }

      static void catchesOwnFailure(final Object value) {
         try {
            requireValue(value);
         } catch (final IllegalArgumentException ex) {
            // Normal completion with null must prevent this helper from imposing a requirement on its callers.
         }
      }

      static void requireNpe(final Object value) {
         Objects.requireNonNull(value);
      }

      @SuppressWarnings("unused") // The parameter is present to verify that a non-returning body supplies no requirement.
      static void alwaysThrows(final Object value) {
         throw new IllegalArgumentException();
      }

      static native void nativeCall(Object value);

      final void fixedCall(final Object value) {
         requireValue(value);
      }

      void virtualCall(final Object value) {
         requireValue(value);
      }

      private void privateCall(final Object value) {
         requireValue(value);
      }
   }

   static final class Child extends Helpers {
      Child() {
         super(new Object());
      }

      void superVirtual(final Object value) {
         super.virtualCall(value);
      }
   }

   public interface PublishedParent {
      void inherited(Object value);
   }

   @NonNullByDefault({})
   public static final class PublishedContracts implements PublishedParent {
      @Override
      public void inherited(final Object value) {
         Helpers.requireValue(value);
      }

      public void annotated(final @Nullable Object value) {
         Helpers.requireValue(value);
      }

      public void conflicting(final Object value) {
         Helpers.requireValue(value);
      }

      public void kept(final Object value) {
         Helpers.requireValue(value);
      }
   }

   @NonNullByDefault({})
   static final class Forwarders {
      static void privateChain(final Object value) {
         privateHelper(value);
      }

      private static void privateHelper(final Object value) {
         Helpers.requireValue(value);
      }

      @SuppressWarnings("unused") // Wide primitives shift local slots while leaving descriptor argument indexes stable.
      static int reordered(final Object first, final long wide, final Object second, final double otherWide) {
         return Helpers.requirePair(second, first);
      }

      @SuppressWarnings("cast") // Keep the CHECKCAST producer in the bytecode provenance chain under test.
      static void alias(final Object value) {
         final Object alias = (String) value;
         Helpers.requireValue(alias);
      }

      static int repeatedArgument(final Object value) {
         return Helpers.requirePair(value, value);
      }

      static void ambiguous(final Object first, final Object second, final boolean useFirst) {
         Helpers.requireValue(useFirst ? first : second);
      }

      static void overwritten(Object value, final Object replacement) {
         value = replacement;
         Helpers.requireValue(value);
      }

      static void fixedReceiver(final Helpers receiver, final Object value) {
         receiver.fixedCall(value);
      }

      static void virtualReceiver(final Helpers receiver, final Object value) {
         receiver.virtualCall(value);
      }

      static void privateReceiver(final Helpers receiver, final Object value) {
         receiver.privateCall(value);
      }

      @SuppressWarnings("unused") // Only the constructor's normal completion matters for this void wrapper.
      static void constructor(final Object value) {
         new Helpers(value);
      }

      static void conditional(final Object value, final boolean check) {
         if (check) {
            Helpers.requireValue(value);
         }
      }

      static void everyBranch(final Object value, final boolean usePrivate) {
         if (usePrivate) {
            privateHelper(value);
         } else {
            Helpers.requireValue(value);
         }
      }

      static void acceptsNull(final Object value) {
         Helpers.acceptsNull(value);
      }

      static Object returnsArgument(final Object value) {
         Helpers.requireValue(value);
         return value;
      }

      static void catchesHelperFailure(final Object value) {
         Helpers.catchesOwnFailure(value);
      }

      static void catchesInCaller(final Object value) {
         try {
            Helpers.requireValue(value);
         } catch (final IllegalArgumentException ex) {
            // The exceptional path reaches a normal return without the helper's normal-completion fact.
         }
      }

      static void rethrowsOtherException(final Object value) {
         try {
            Helpers.requireValue(value);
         } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException(ex);
         }
      }

      static void rethrowsNpe(final Object value) {
         try {
            Helpers.requireNpe(value);
         } catch (final NullPointerException ex) {
            // Preserve the existing policy: an explicit NPE-capable catch suppresses call evidence even when it rethrows.
            throw new IllegalStateException(ex);
         }
      }

      static void finallyCompletes(final Object value) {
         try {
            Helpers.requireValue(value);
         } finally {
            System.nanoTime();
         }
      }

      // A normal return from finally must deliberately swallow the helper failure to exercise this contract boundary.
      static void finallyCanReturn(final Object value) {
         try {
            Helpers.requireValue(value);
         } finally { // CHECKSTYLE:IGNORE ForbidReturnInFinallyBlock
            if (System.nanoTime() == 0)
               return;
         }
      }

      static void alwaysThrows(final Object value) {
         Helpers.alwaysThrows(value);
      }

      static void nativeCall(final Object value) {
         Helpers.nativeCall(value);
      }

      static void cycle(final Object first, final Object second, final boolean recurse) {
         if (recurse) {
            CycleHelper.call(first, second);
         }
         Helpers.requireValue(first);
      }
   }

   @NonNullByDefault({})
   static final class CycleHelper {
      static void call(final Object first, final Object second) {
         Forwarders.cycle(first, second, false);
         Helpers.requireValue(second);
      }
   }

   @NonNullByDefault({})
   static final class BudgetCycle {
      static void first(final Object first, final Object second) {
         second(first, second);
         first.hashCode();
      }

      static void second(final Object first, final Object second) {
         if (System.nanoTime() == 0) {
            first(second, first);
         } else {
            second.hashCode();
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testArgumentMappingAndDispatch() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().acceptClasses(Forwarders.class.getName(), Helpers.class.getName(), Child.class
         .getName()).scan()) {
         final ClassInfo forwarders = scan.getClassInfo(Forwarders.class.getName());
         final var resolver = new BytecodeAnalyzer.MethodSummaryResolver(new BytecodeAnalyzer.StaticFieldResolver(scan));
         final var analyzer = new BytecodeAnalyzer(forwarders, resolver);
         assertRequirements(analyzer, forwarders, "privateChain", 0);
         assertRequirements(analyzer, forwarders, "reordered", 0, 2);
         assertRequirements(analyzer, forwarders, "alias", 0);
         assertRequirements(analyzer, forwarders, "repeatedArgument", 0);
         assertRequirements(analyzer, forwarders, "ambiguous");
         assertRequirements(analyzer, forwarders, "overwritten");
         assertRequirements(analyzer, forwarders, "fixedReceiver", 0, 1);
         assertRequirements(analyzer, forwarders, "privateReceiver", 0, 1);
         assertRequirements(analyzer, forwarders, "constructor", 0);
         assertRequirements(analyzer, forwarders, "conditional");
         assertRequirements(analyzer, forwarders, "everyBranch", 0);
         assertRequirements(analyzer, forwarders, "nativeCall");

         // Warm the exact body at the same depth used by a caller; cached evidence cannot close virtual dispatch.
         final ClassInfo child = scan.getClassInfo(Child.class.getName());
         assertRequirements(new BytecodeAnalyzer(child, resolver), child, "superVirtual", 0);
         assertRequirements(analyzer, forwarders, "virtualReceiver", 0);
         final var nullable = analyzer.determineMethodParameterAnalysis(forwarders.getMethodInfo("acceptsNull").get(0));
         assertThat(nullable.getDefinitelyNonNullParameterIndexes()).isEmpty();
         assertThat(nullable.getDefinitelyNullableParameterIndexes()).as("nullable acceptance is not a delegated requirement").isEmpty();
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNormalCompletionAndExceptionBoundaries() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().acceptClasses(Forwarders.class.getName()).scan()) {
         final ClassInfo forwarders = scan.getClassInfo(Forwarders.class.getName());
         final var analyzer = new BytecodeAnalyzer(forwarders, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertRequirements(analyzer, forwarders, "catchesHelperFailure");
         assertRequirements(analyzer, forwarders, "catchesInCaller");
         assertRequirements(analyzer, forwarders, "rethrowsOtherException", 0);
         assertRequirements(analyzer, forwarders, "rethrowsNpe");
         assertRequirements(analyzer, forwarders, "finallyCompletes", 0);
         assertRequirements(analyzer, forwarders, "finallyCanReturn");
         assertRequirements(analyzer, forwarders, "alwaysThrows");

         /* Exact local forwarding now proves PolyNull independently. Delegated parameter evidence must not upgrade it
          * to non-null or change its dependencies when that analysis warms the resolver. */
         final var method = forwarders.getMethodInfo("returnsArgument").get(0);
         assertThat(analyzer.determineMethodReturnAnalysis(method).getNullability()).isEqualTo(Nullability.POLY_NULL);
         assertThat(analyzer.determineMethodReturnAnalysis(method).getNullDependentParameterIndexes()).containsExactly(0);
         assertRequirements(analyzer, forwarders, "returnsArgument", 0);
         assertThat(analyzer.determineMethodReturnAnalysis(method).getNullability()).isEqualTo(Nullability.POLY_NULL);
         assertThat(analyzer.determineMethodReturnAnalysis(method).getNullDependentParameterIndexes()).containsExactly(0);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testCycleDependentSummariesAreNotReused() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().acceptClasses(Forwarders.class.getName(), CycleHelper.class.getName())
         .scan()) {
         final ClassInfo forwarders = scan.getClassInfo(Forwarders.class.getName());
         final ClassInfo helper = scan.getClassInfo(CycleHelper.class.getName());
         for (final boolean helperFirst : new boolean[] {false, true}) {
            final var resolver = new BytecodeAnalyzer.MethodSummaryResolver(new BytecodeAnalyzer.StaticFieldResolver(scan));
            final var firstAnalyzer = new BytecodeAnalyzer(forwarders, resolver);
            final var helperAnalyzer = new BytecodeAnalyzer(helper, resolver);
            if (helperFirst) {
               assertRequirements(helperAnalyzer, helper, "call", 0, 1);
            }
            assertRequirements(firstAnalyzer, forwarders, "cycle", 0);
            /* When reached inside cycle(), call() temporarily sees only its own second-parameter requirement. That
             * incomplete result must not hide the first-parameter requirement when call() is later analyzed as a root. */
            assertRequirements(helperAnalyzer, helper, "call", 0, 1);
            assertRequirements(firstAnalyzer, forwarders, "cycle", 0);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDepthBudgetDoesNotDependOnCacheWarmth(@TempDir final Path directory) throws IOException {
      final String owner = "test/ParameterSummaryDepth";
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, createDeepHelperChain(owner));
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString(), BudgetCycle.class
         .getProtectionDomain().getCodeSource().getLocation()).acceptClasses(owner.replace('/', '.'), BudgetCycle.class.getName()).scan()) {
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         for (final boolean suffixFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (suffixFirst) {
               assertRequirements(analyzer, classInfo, "step80", 0);
            }
            assertRequirements(analyzer, classInfo, "step0");
            assertRequirements(analyzer, classInfo, "withLocalFact", 1);
            assertRequirements(analyzer, classInfo, "step80", 0);
            assertRequirements(analyzer, classInfo, "step0");
         }
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertRequirements(analyzer, classInfo, "warm0", 1);
            }
            assertRequirements(analyzer, classInfo, "cold0", 0);
            // A depth cutoff can hide a later cycle; its cached partial proof must not bypass an active ancestor.
            assertRequirements(analyzer, classInfo, "warm0", 1);
            assertRequirements(analyzer, classInfo, "cold0", 0);
         }
      }
   }

   @Test
   void testGenerationPreservesEvidencePrecedence(@TempDir final Path directory) throws IOException {
      final Path input = directory.resolve("input");
      final Path output = directory.resolve("output");
      final String signature = "(Ljava/lang/Object;)V";
      final var parent = new EEAFile(PublishedParent.class.getName());
      parent.addMember("inherited", signature).annotatedSignature.value = "(L0java/lang/Object;)V";
      parent.save(input, SaveOption.REPLACE_EXISTING);
      final var stored = new EEAFile(PublishedContracts.class.getName());
      stored.addMember("conflicting", signature).annotatedSignature.value = "(L0java/lang/Object;)V";
      final var kept = stored.addMember("kept", signature);
      kept.annotatedSignature.value = "(L0java/lang/Object;)V";
      kept.annotatedSignature.comment = "# @Keep reviewed contract";
      stored.save(input, SaveOption.REPLACE_EXISTING);
      final var config = new EEAGenerator.Config(output, BytecodeParameterSummaryTest.class.getPackageName());
      config.inputDirs.add(input);
      config.classFilter = info -> info.getName().equals(PublishedContracts.class.getName());
      for (final var mode : EEAGenerator.GenerationMode.values()) {
         config.generationMode = mode;
         EEAGenerator.generateEEAFiles(config);
         final var generated = EEAFile.load(output, PublishedContracts.class.getName());
         // Delegated proof follows the existing precedence: local proof beats inheritance; annotations and @Keep win.
         // A bare relationship marker already owns the complete contract; an additional @Generated would be redundant.
         assertGeneratedParameter(generated, "inherited", "(L1java/lang/Object;)V", "# @Overrides(" + PublishedParent.class.getName()
               + ")");
         assertGeneratedParameter(generated, "annotated", "(L0java/lang/Object;)V", "# @Generated");
         assertGeneratedParameter(generated, "kept", "(L0java/lang/Object;)V", kept.annotatedSignature.comment);
         if (mode == EEAGenerator.GenerationMode.FULL) {
            assertGeneratedParameter(generated, "conflicting", "(L1java/lang/Object;)V", "# @Generated");
         } else {
            // Additive generation retains a stored disagreement without claiming ownership of its manual value.
            assertGeneratedParameter(generated, "conflicting", "(L0java/lang/Object;)V", "");
         }
      }
   }

   private static void assertGeneratedParameter(final EEAFile file, final String name, final String signature, final String comment) {
      final var member = file.getClassMembers().filter(candidate -> candidate.name.value.equals(name)).findFirst().orElseThrow();
      assertThat(member.annotatedSignature.value).as(name).isEqualTo(signature);
      assertThat(member.annotatedSignature.comment).as(name + " ownership").isEqualTo(comment);
   }

   private static byte[] createDeepHelperChain(final String owner) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
      // The complete chain exceeds the analysis depth; its shorter suffix fits and can independently warm the cache.
      for (int step = 0; step < 160; step++) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "step" + step, "(Ljava/lang/Object;)V", null, null);
         method.visitCode();
         method.visitVarInsn(Opcodes.ALOAD, 0);
         if (step == 159) {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            method.visitInsn(Opcodes.POP);
         } else {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step" + (step + 1), "(Ljava/lang/Object;)V", false);
         }
         method.visitInsn(Opcodes.RETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      final var local = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withLocalFact",
         "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
      local.visitCode();
      local.visitVarInsn(Opcodes.ALOAD, 1);
      local.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      local.visitInsn(Opcodes.POP);
      local.visitVarInsn(Opcodes.ALOAD, 0);
      local.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step0", "(Ljava/lang/Object;)V", false);
      local.visitInsn(Opcodes.RETURN);
      local.visitMaxs(0, 0);
      local.visitEnd();
      // Reach second() with one remaining hop and first() with two, so both traversals meet the same summary cache key.
      for (final boolean warm : new boolean[] {false, true}) {
         final String prefix = warm ? "warm" : "cold";
         final int length = warm ? 127 : 126;
         final String descriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V";
         for (int step = 0; step < length; step++) {
            final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, prefix + step, descriptor, null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitVarInsn(Opcodes.ALOAD, 1);
            if (step + 1 == length) {
               method.visitMethodInsn(Opcodes.INVOKESTATIC, BudgetCycle.class.getName().replace('.', '/'), warm ? "second" : "first",
                  descriptor, false);
            } else {
               method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, prefix + (step + 1), descriptor, false);
            }
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
         }
      }
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   @SuppressWarnings("null")
   private static void assertRequirements(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method,
         final int... expectedIndexes) {
      assertThat(analyzer.determineDefinitelyNonNullMethodParameters(owner.getMethodInfo(method).get(0))).as(method)
         .containsExactlyInAnyOrder(Arrays.stream(expectedIndexes).boxed().toArray(Integer[]::new));
   }
}
