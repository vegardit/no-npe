/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Verifies parameter-proof work accounting, shared local provenance, and the actual superclass dispatch boundary.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeParameterSafetyTest {

   public static class ThrowingGrandparent {
      public void stop() {
         throw new IllegalStateException();
      }
   }

   public static class ReturningParent extends ThrowingGrandparent {
      @Override
      public void stop() {
         // This override makes the symbolic grandparent's throwing body an invalid normal-completion proof.
      }
   }

   @Test
   @SuppressWarnings("null")
   void testWorkBudgetBoundsBranchingCycles(@TempDir final Path directory) throws IOException {
      final String owner = "test/ParameterSummaryCycleWork";
      writeClass(directory, owner, createBranchingHelpers(owner, true));
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/', '.'))
         .scan()) {
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
         /* The small cycle expands into many repeated analyses. The final delegated check must be skipped after the
          * shared allowance is exhausted, while the wrapper's independent receiver check must survive. This checks
          * the fallback contract without depending on machine speed or leaving a timed-out analysis running. */
         assertRequirements(analyzer, classInfo, "withLocalFact", 1);
         assertRequirements(analyzer, classInfo, "warmLeaf", 0);
         // A fresh root gets a new allowance, but a warmed leaf cannot bypass an exhausted caller's allowance.
         assertRequirements(analyzer, classInfo, "withLocalFact", 1);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testWorkBudgetDoesNotDependOnCacheWarmth(@TempDir final Path directory) throws IOException {
      final String owner = "test/ParameterSummaryBranchWork";
      writeClass(directory, owner, createBranchingHelpers(owner, false));
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/', '.'))
         .scan()) {
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertRequirements(analyzer, classInfo, "warm", 0);
               assertRequirements(analyzer, classInfo, "warmLocal", 0, 1);
            }
            // A cached suffix that no longer fits still has a local check that cold, limited analysis would retain.
            assertRequirements(analyzer, classInfo, "partial", 0, 1);
            /* A single suffix fits, but its two uses exceed the root allowance. Cached proofs must carry their work
             * cost or this result would change with analysis order even though the bytecode is identical. */
            assertRequirements(analyzer, classInfo, "step0");
            assertRequirements(analyzer, classInfo, "warm", 0);
            assertRequirements(analyzer, classInfo, "warmLocal", 0, 1);
            assertRequirements(analyzer, classInfo, "partial", 0, 1);
            assertRequirements(analyzer, classInfo, "step0");
            assertRequirements(analyzer, classInfo, "withLocalFact", 1);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testSuperclassCompletionUsesExactTarget(@TempDir final Path directory) throws Exception {
      final String directOwner = "test/DirectSpecialParameter";
      final String indirectOwner = "test/IndirectSpecialParameter";
      final String grandparent = ThrowingGrandparent.class.getName().replace('.', '/');
      writeClass(directory, directOwner, createSpecialCaller(directOwner, grandparent, grandparent, null));
      writeClass(directory, indirectOwner, createSpecialCaller(indirectOwner, ReturningParent.class.getName().replace('.', '/'),
         grandparent, null));
      assertSpecialCallerRequirements(directory, directOwner, indirectOwner);
   }

   @Test
   void testPrivateSuperclassCompletionUsesExactTarget(@TempDir final Path directory) throws Exception {
      final String grandparent = "test/PrivateSpecialGrandparent";
      final String parent = "test/PrivateSpecialParent";
      final String directOwner = "test/DirectPrivateSpecialParameter";
      final String indirectOwner = "test/IndirectPrivateSpecialParameter";
      for (final boolean privateMethod : new boolean[] {true, false}) {
         final String owner = privateMethod ? grandparent : parent;
         final String superclass = privateMethod ? "java/lang/Object" : grandparent;
         final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
         writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, superclass, null);
         /* Private access needs a valid shared nest. Superclass selection still starts at the immediate parent,
          * even though its same-signature method cannot override the private grandparent declaration. */
         if (privateMethod) {
            writer.visitNestMember(parent);
            writer.visitNestMember(directOwner);
            writer.visitNestMember(indirectOwner);
         } else {
            writer.visitNestHost(grandparent);
         }
         writeDefaultConstructor(writer, superclass);
         final var stop = writer.visitMethod(privateMethod ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, "stop", "()V", null, null);
         stop.visitCode();
         if (privateMethod) {
            stop.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
            stop.visitInsn(Opcodes.DUP);
            stop.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false);
            stop.visitInsn(Opcodes.ATHROW);
         } else {
            stop.visitInsn(Opcodes.RETURN);
         }
         stop.visitMaxs(0, 0);
         stop.visitEnd();
         writer.visitEnd();
         writeClass(directory, owner, Objects.requireNonNull(writer.toByteArray()));
      }
      writeClass(directory, directOwner, createSpecialCaller(directOwner, grandparent, grandparent, grandparent));
      writeClass(directory, indirectOwner, createSpecialCaller(indirectOwner, parent, grandparent, grandparent));
      assertSpecialCallerRequirements(directory, directOwner, indirectOwner);
   }

   @Test
   @SuppressWarnings("null")
   void testWorkBudgetBoundsArgumentProvenance(@TempDir final Path directory) throws IOException {
      for (final boolean parameterSource : new boolean[] {true, false}) {
         final String owner = "test/ArgumentProvenance" + parameterSource;
         writeClass(directory, owner, createRepeatedArgumentCalls(owner, parameterSource));
         try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/',
            '.')).scan()) {
            final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
            for (final boolean warmFirst : new boolean[] {false, true}) {
               final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
               if (warmFirst) {
                  assertRequirements(analyzer, classInfo, "warm", 0);
               }
               /* Repeated walks exceed the allowance even when their final producer is not a parameter. The late
                * helper must lose its proof, while earlier delegated evidence and a later local check survive. */
               assertRequirements(analyzer, classInfo, "expensive", parameterSource ? new int[] {0, 2} : new int[] {2});
               assertRequirements(analyzer, classInfo, "warm", 0);
               assertRequirements(analyzer, classInfo, "expensive", parameterSource ? new int[] {0, 2} : new int[] {2});
            }
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testWorkBudgetBoundsSharedProvenanceEdges(@TempDir final Path directory) throws IOException {
      final String owner = "test/SharedArgumentProvenance";
      writeClass(directory, owner, createSharedArgumentCalls(owner));
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/', '.'))
         .scan()) {
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertRequirements(analyzer, classInfo, "warm", 0);
            }
            /* Each walk expands few producers but visits many shared edges. Exhaustion must suppress the late helper
             * while retaining the earlier argument proof and the independent receiver check, regardless of cache warmth. */
            assertRequirements(analyzer, classInfo, "expensive", 0, 2);
            assertRequirements(analyzer, classInfo, "warm", 0);
            assertRequirements(analyzer, classInfo, "expensive", 0, 2);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testMergedTypeTestsShareReferenceProvenance(@TempDir final Path directory) throws Exception {
      final String owner = "test/SharedTypeTestProvenance";
      writeClass(directory, owner, createSharedTypeTests(owner));
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/', '.'))
              .scan()) {
         // Verify the generated bytecode and its normal path with a null tested value before asserting analysis facts.
         loader.loadClass(owner.replace('/', '.')).getMethod("expensive", Object.class, Object.class, Object.class, int.class).invoke(null,
            null, new Object(), new Object(), 0);
         final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertRequirements(analyzer, classInfo, "warm", 0);
            }
            /* Sharing one reference walk per Boolean proof leaves room for the late helper. This deterministic budget
             * assertion detects redundant walks without a timing threshold or a large, slow regression fixture. */
            assertRequirements(analyzer, classInfo, "expensive", 1, 2);
            assertRequirements(analyzer, classInfo, "warm", 0);
            assertRequirements(analyzer, classInfo, "expensive", 1, 2);
         }
      }
   }

   private static byte[] createSharedTypeTests(final String owner) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
      writeParameterLeafAndWarmWrapper(writer, owner);
      final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "expensive",
         "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V", null, null);
      method.visitCode();
      method.visitVarInsn(Opcodes.ALOAD, 0);
      method.visitVarInsn(Opcodes.ASTORE, 4);
      // Separate traversals multiply 512 alias producers by 64 type tests and 40 checks, exceeding the work allowance.
      for (int alias = 0; alias < 256; alias++) {
         method.visitVarInsn(Opcodes.ALOAD, 4);
         method.visitVarInsn(Opcodes.ASTORE, 4);
      }
      final Label[] branches = Stream.generate(Label::new).limit(64).toArray(Label[]::new);
      final var merged = new Label();
      method.visitVarInsn(Opcodes.ILOAD, 3);
      method.visitTableSwitchInsn(0, branches.length - 1, branches[0], branches);
      for (final Label branch : branches) {
         method.visitLabel(branch);
         method.visitVarInsn(Opcodes.ALOAD, 4);
         method.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Object");
         method.visitVarInsn(Opcodes.ISTORE, 5);
         method.visitJumpInsn(Opcodes.GOTO, merged);
      }
      method.visitLabel(merged);
      for (int check = 0; check < 40; check++) {
         final var next = new Label();
         method.visitVarInsn(Opcodes.ILOAD, 5);
         method.visitJumpInsn(Opcodes.IFEQ, next);
         // Distinct successors exercise the type-test proof, while their later join still permits a null parameter.
         method.visitInsn(Opcodes.NOP);
         method.visitLabel(next);
      }
      method.visitVarInsn(Opcodes.ALOAD, 1);
      method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      method.visitVarInsn(Opcodes.ALOAD, 2);
      method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      method.visitInsn(Opcodes.POP);
      method.visitInsn(Opcodes.RETURN);
      method.visitMaxs(0, 0);
      method.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   @SuppressWarnings("null")
   @Test
   void testWorkBudgetIncludesHelperLocalProvenance(@TempDir final Path directory) throws IOException {
      for (final boolean receiverCheck : new boolean[] {true, false}) {
         final String owner = "test/HelperLocalProvenance" + receiverCheck;
         writeClass(directory, owner, createRepeatedLocalChecks(owner, receiverCheck));
         try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptClasses(owner.replace('/',
            '.')).scan()) {
            final ClassInfo classInfo = scan.getClassInfo(owner.replace('/', '.'));
            final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scan));
            /* Each helper fits separately, but repeated local walks must consume the shared allowance even when a
             * cycle prevents summary caching. Both the helper's late local check and the root's local check survive;
             * only the late delegated proof is optional. */
            assertRequirements(analyzer, classInfo, "expensive", 0, 2, 3);
            assertRequirements(analyzer, classInfo, "warm", 0);
            assertRequirements(analyzer, classInfo, "expensive", 0, 2, 3);
         }
      }
   }

   private static byte[] createRepeatedLocalChecks(final String owner, final boolean receiverCheck) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
      writeParameterLeafAndWarmWrapper(writer, owner);
      final String descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Z)V";
      final var helper = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "helper", descriptor, null, null);
      helper.visitCode();
      final var localChecks = new Label();
      helper.visitVarInsn(Opcodes.ILOAD, 2);
      helper.visitJumpInsn(Opcodes.IFEQ, localChecks);
      helper.visitVarInsn(Opcodes.ALOAD, 0);
      helper.visitVarInsn(Opcodes.ALOAD, 1);
      helper.visitInsn(Opcodes.ICONST_0);
      helper.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "helper", descriptor, false);
      helper.visitLabel(localChecks);
      helper.visitVarInsn(Opcodes.ALOAD, 0);
      helper.visitVarInsn(Opcodes.ASTORE, 3);
      // Keep the failing case modest: three calls retrace just over two million producer-and-edge work units.
      for (int alias = 0; alias < 300; alias++) {
         helper.visitVarInsn(Opcodes.ALOAD, 3);
         helper.visitVarInsn(Opcodes.ASTORE, 3);
      }
      for (int check = 0; check < 600; check++) {
         helper.visitVarInsn(Opcodes.ALOAD, 3);
         if (receiverCheck) {
            helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            helper.visitInsn(Opcodes.POP);
         } else {
            final var nonNull = new Label();
            helper.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
            helper.visitInsn(Opcodes.ACONST_NULL);
            helper.visitInsn(Opcodes.ATHROW);
            helper.visitLabel(nonNull);
         }
      }
      // The final invocation reaches this independent check only after its expensive local walks exhaust the allowance.
      helper.visitVarInsn(Opcodes.ALOAD, 1);
      helper.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      helper.visitInsn(Opcodes.POP);
      helper.visitInsn(Opcodes.RETURN);
      helper.visitMaxs(0, 0);
      helper.visitEnd();

      final var root = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "expensive",
         "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
      root.visitCode();
      for (int call = 0; call < 3; call++) {
         root.visitVarInsn(Opcodes.ALOAD, 0);
         if (call == 2) {
            // Earlier calls must not supply this fact, or the third helper's local fallback would go untested.
            root.visitVarInsn(Opcodes.ALOAD, 2);
         } else {
            root.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            root.visitInsn(Opcodes.DUP);
            root.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
         }
         root.visitInsn(Opcodes.ICONST_0);
         root.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "helper", descriptor, false);
      }
      root.visitVarInsn(Opcodes.ALOAD, 1);
      root.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      root.visitVarInsn(Opcodes.ALOAD, 3);
      root.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      root.visitInsn(Opcodes.POP);
      root.visitInsn(Opcodes.RETURN);
      root.visitMaxs(0, 0);
      root.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   @SuppressWarnings("null")
   private void assertSpecialCallerRequirements(final Path directory, final String directOwner, final String indirectOwner)
         throws Exception {
      // Execute the fixtures so invalid access or nest metadata cannot masquerade as a valid inference counterexample.
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString(), getClass().getProtectionDomain()
              .getCodeSource().getLocation()).acceptClasses(directOwner.replace('/', '.'), indirectOwner.replace('/', '.')).scan()) {
         final Class<?> directClass = loader.loadClass(directOwner.replace('/', '.'));
         final Object directInstance = directClass.getConstructor().newInstance();
         assertThatThrownBy(() -> directClass.getMethod("forward", Object.class).invoke(directInstance, new @Nullable Object[] {null}))
            .hasCauseInstanceOf(IllegalStateException.class);
         final Class<?> indirectClass = loader.loadClass(indirectOwner.replace('/', '.'));
         final Object indirectInstance = indirectClass.getConstructor().newInstance();
         // The JVM selects the returning parent, even though the instruction names the throwing grandparent.
         assertThat(indirectClass.getMethod("forward", Object.class).invoke(indirectInstance, new @Nullable Object[] {null})).isNull();

         final ClassInfo direct = scan.getClassInfo(directOwner.replace('/', '.'));
         final ClassInfo indirect = scan.getClassInfo(indirectOwner.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var resolver = new BytecodeAnalyzer.MethodSummaryResolver(new BytecodeAnalyzer.StaticFieldResolver(scan));
            final var directAnalyzer = new BytecodeAnalyzer(direct, resolver);
            final var indirectAnalyzer = new BytecodeAnalyzer(indirect, resolver);
            if (warmFirst) {
               assertRequirements(directAnalyzer, direct, "forward", 0);
            }
            assertRequirements(indirectAnalyzer, indirect, "forward");
            assertRequirements(directAnalyzer, direct, "forward", 0);
            // Dispatch eligibility must also reject a cached proof of the grandparent's non-returning body.
            assertRequirements(indirectAnalyzer, indirect, "forward");
         }
      }
   }

   private static byte[] createBranchingHelpers(final String owner, final boolean cyclic) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
      final String descriptor = "(Ljava/lang/Object;I)V";
      /* Keep the unfixed cyclic case finite enough for a regression run. Including argument-provenance work, one
       * acyclic suffix fits the allowance while two exceed it, exercising cached costs without a wall-clock assertion. */
      final int helperCount = 16;
      for (int step = 0; step < helperCount; step++) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "step" + step, descriptor, null, null);
         method.visitCode();
         final var branches = new Label();
         if (cyclic) {
            method.visitVarInsn(Opcodes.ILOAD, 1);
            method.visitJumpInsn(Opcodes.IFGT, branches);
         }
         if (cyclic || step + 1 == helperCount) {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            method.visitInsn(Opcodes.POP);
            method.visitInsn(Opcodes.RETURN);
         }
         if (cyclic || step + 1 < helperCount) {
            method.visitLabel(branches);
            final var alternative = new Label();
            final var done = new Label();
            method.visitVarInsn(Opcodes.ILOAD, 1);
            if (cyclic) {
               method.visitInsn(Opcodes.ICONST_1);
               method.visitInsn(Opcodes.IAND);
            }
            method.visitJumpInsn(Opcodes.IFEQ, alternative);
            for (int branch = 0; branch < 2; branch++) {
               if (branch == 1) {
                  method.visitLabel(alternative);
               }
               method.visitVarInsn(Opcodes.ALOAD, 0);
               method.visitVarInsn(Opcodes.ILOAD, 1);
               if (cyclic) {
                  // Both runtime branches decrease the counter; recursive analysis still has to inspect both call sites.
                  method.visitInsn(branch == 0 ? Opcodes.ICONST_1 : Opcodes.ICONST_2);
                  method.visitInsn(Opcodes.ISUB);
               }
               method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step" + (step + 1 == helperCount ? 0 : step + 1), descriptor, false);
               if (branch == 0) {
                  method.visitJumpInsn(Opcodes.GOTO, done);
               }
            }
            method.visitLabel(done);
            method.visitInsn(Opcodes.RETURN);
         }
         method.visitMaxs(0, 0);
         method.visitEnd();
      }

      final var leaf = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "leaf", "(Ljava/lang/Object;)V", null, null);
      leaf.visitCode();
      leaf.visitVarInsn(Opcodes.ALOAD, 0);
      leaf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      leaf.visitInsn(Opcodes.POP);
      leaf.visitInsn(Opcodes.RETURN);
      leaf.visitMaxs(0, 0);
      leaf.visitEnd();

      for (final boolean leafOnly : new boolean[] {false, true}) {
         final var warm = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, leafOnly ? "warmLeaf" : "warm",
            "(Ljava/lang/Object;)V", null, null);
         warm.visitCode();
         warm.visitVarInsn(Opcodes.ALOAD, 0);
         if (!leafOnly) {
            warm.visitInsn(Opcodes.ICONST_1);
         }
         warm.visitMethodInsn(Opcodes.INVOKESTATIC, owner, leafOnly ? "leaf" : "step1", leafOnly ? "(Ljava/lang/Object;)V" : descriptor,
            false);
         warm.visitInsn(Opcodes.RETURN);
         warm.visitMaxs(0, 0);
         warm.visitEnd();
      }

      final String localDescriptor = "(Ljava/lang/Object;Ljava/lang/Object;)V";
      final var localSuffix = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "localSuffix", localDescriptor, null, null);
      localSuffix.visitCode();
      localSuffix.visitVarInsn(Opcodes.ALOAD, 1);
      localSuffix.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      localSuffix.visitInsn(Opcodes.POP);
      localSuffix.visitVarInsn(Opcodes.ALOAD, 0);
      localSuffix.visitInsn(Opcodes.ICONST_1);
      localSuffix.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step1", descriptor, false);
      localSuffix.visitInsn(Opcodes.RETURN);
      localSuffix.visitMaxs(0, 0);
      localSuffix.visitEnd();
      for (final boolean partial : new boolean[] {false, true}) {
         final var caller = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, partial ? "partial" : "warmLocal", localDescriptor,
            null, null);
         caller.visitCode();
         if (partial) {
            // Spend most of the allowance before the same localSuffix call site, keeping its remaining depth identical.
            caller.visitVarInsn(Opcodes.ALOAD, 0);
            caller.visitInsn(Opcodes.ICONST_1);
            caller.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step1", descriptor, false);
         }
         caller.visitVarInsn(Opcodes.ALOAD, 0);
         caller.visitVarInsn(Opcodes.ALOAD, 1);
         caller.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "localSuffix", localDescriptor, false);
         caller.visitInsn(Opcodes.RETURN);
         caller.visitMaxs(0, 0);
         caller.visitEnd();
      }

      final var local = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "withLocalFact",
         "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
      local.visitCode();
      local.visitVarInsn(Opcodes.ALOAD, 1);
      local.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      local.visitInsn(Opcodes.POP);
      local.visitVarInsn(Opcodes.ALOAD, 0);
      local.visitInsn(Opcodes.ICONST_1);
      local.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step0", descriptor, false);
      local.visitVarInsn(Opcodes.ALOAD, 0);
      local.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      local.visitInsn(Opcodes.RETURN);
      local.visitMaxs(0, 0);
      local.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   private static byte[] createRepeatedArgumentCalls(final String owner, final boolean parameterSource) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
      writeParameterLeafAndWarmWrapper(writer, owner);
      final var expensive = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "expensive",
         "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
      expensive.visitCode();
      if (parameterSource) {
         expensive.visitVarInsn(Opcodes.ALOAD, 0);
      } else {
         expensive.visitInsn(Opcodes.ACONST_NULL);
      }
      expensive.visitVarInsn(Opcodes.ASTORE, 3);
      /* This small method stays below the frame and instruction limits, but 1,100 calls each retrace 2,003
       * producers. Keep it only just above the work allowance so the unfixed regression is cheap to run. */
      for (int alias = 0; alias < 1_000; alias++) {
         expensive.visitVarInsn(Opcodes.ALOAD, 3);
         expensive.visitVarInsn(Opcodes.ASTORE, 3);
      }
      for (int call = 0; call < 1_100; call++) {
         expensive.visitVarInsn(Opcodes.ALOAD, 3);
         expensive.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      }
      expensive.visitVarInsn(Opcodes.ALOAD, 1);
      expensive.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      expensive.visitVarInsn(Opcodes.ALOAD, 2);
      expensive.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      expensive.visitInsn(Opcodes.POP);
      expensive.visitInsn(Opcodes.RETURN);
      expensive.visitMaxs(0, 0);
      expensive.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   private static void writeParameterLeafAndWarmWrapper(final ClassWriter writer, final String owner) {
      // Warming through a wrapper gives the leaf the same remaining depth as a call from expensive().
      for (final boolean wrapper : new boolean[] {false, true}) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, wrapper ? "warm" : "leaf", "(Ljava/lang/Object;)V",
            null, null);
         method.visitCode();
         method.visitVarInsn(Opcodes.ALOAD, 0);
         if (wrapper) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
         } else {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
            method.visitInsn(Opcodes.POP);
         }
         method.visitInsn(Opcodes.RETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
   }

   private static byte[] createSharedArgumentCalls(final String owner) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null);
      writeParameterLeafAndWarmWrapper(writer, owner);
      final var expensive = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "expensive",
         "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I)V", null, null);
      expensive.visitCode();
      /* Two 128-way joins create 128 squared dependency edges with only about 512 producers. A small number
       * of repeated calls exceeds the shared allowance without requiring a slow or timing-sensitive regression. */
      for (int stage = 0; stage < 2; stage++) {
         final Label[] branches = Stream.generate(Label::new).limit(128).toArray(Label[]::new);
         final var done = new Label();
         expensive.visitVarInsn(Opcodes.ILOAD, 3);
         expensive.visitTableSwitchInsn(0, branches.length - 1, branches[0], branches);
         for (final Label branch : branches) {
            expensive.visitLabel(branch);
            expensive.visitVarInsn(Opcodes.ALOAD, stage == 0 ? 0 : 4);
            expensive.visitVarInsn(Opcodes.ASTORE, stage == 0 ? 4 : 5);
            expensive.visitJumpInsn(Opcodes.GOTO, done);
         }
         expensive.visitLabel(done);
      }
      for (int call = 0; call < 132; call++) {
         expensive.visitVarInsn(Opcodes.ALOAD, 5);
         expensive.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      }
      expensive.visitVarInsn(Opcodes.ALOAD, 1);
      expensive.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "leaf", "(Ljava/lang/Object;)V", false);
      expensive.visitVarInsn(Opcodes.ALOAD, 2);
      expensive.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "hashCode", "()I", false);
      expensive.visitInsn(Opcodes.POP);
      expensive.visitInsn(Opcodes.RETURN);
      expensive.visitMaxs(0, 0);
      expensive.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   private static byte[] createSpecialCaller(final String owner, final String parent, final String symbolicOwner,
         final @Nullable String nestHost) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, parent, null);
      if (nestHost != null) {
         writer.visitNestHost(nestHost);
      }
      writeDefaultConstructor(writer, parent);

      final var helper = writer.visitMethod(Opcodes.ACC_PRIVATE, "helper", "(Ljava/lang/Object;)V", null, null);
      helper.visitCode();
      final var done = new Label();
      helper.visitVarInsn(Opcodes.ALOAD, 1);
      helper.visitJumpInsn(Opcodes.IFNONNULL, done);
      helper.visitVarInsn(Opcodes.ALOAD, 0);
      helper.visitMethodInsn(Opcodes.INVOKESPECIAL, symbolicOwner, "stop", "()V", false);
      helper.visitLabel(done);
      helper.visitInsn(Opcodes.RETURN);
      helper.visitMaxs(0, 0);
      helper.visitEnd();

      final var forward = writer.visitMethod(Opcodes.ACC_PUBLIC, "forward", "(Ljava/lang/Object;)V", null, null);
      forward.visitCode();
      forward.visitVarInsn(Opcodes.ALOAD, 0);
      forward.visitVarInsn(Opcodes.ALOAD, 1);
      forward.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "helper", "(Ljava/lang/Object;)V", false);
      forward.visitInsn(Opcodes.RETURN);
      forward.visitMaxs(0, 0);
      forward.visitEnd();
      writer.visitEnd();
      return Objects.requireNonNull(writer.toByteArray());
   }

   private static void writeDefaultConstructor(final ClassWriter writer, final String parent) {
      final var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();
   }

   private static void writeClass(final Path directory, final String owner, final byte[] bytecode) throws IOException {
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, bytecode);
   }

   @SuppressWarnings("null")
   private static void assertRequirements(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method,
         final int... expectedIndexes) {
      assertThat(analyzer.determineDefinitelyNonNullMethodParameters(owner.getMethodInfo(method).get(0))).as(method)
         .containsExactlyInAnyOrder(Arrays.stream(expectedIndexes).boxed().toArray(Integer[]::new));
   }
}
