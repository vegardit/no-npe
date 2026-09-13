/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import com.vegardit.no_npe.eea_generator.EEAFile;
import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.EEAGenerator;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Verifies checked-exception parameter proofs, their conservative boundaries, and generated return refinement.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeCheckedExceptionTest {

   interface CheckedOperation {
      void run() throws IOException;
   }

   @NonNullByDefault({}) // Preserve runtime null paths in the fixtures regardless of compiler defaults.
   static class Fixtures {
      static native void checkedWork() throws IOException;

      static void checkThenWork(final Object value) throws IOException {
         Objects.requireNonNull(value);
         checkedWork();
      }

      static void workThenCheck(final Object value) throws IOException {
         checkedWork();
         Objects.requireNonNull(value);
      }

      @SuppressWarnings("unused") // A throws declaration alone must not make a checked-exception path reachable.
      static void noCheckedFailure() throws IOException {
         // The body is intentionally empty so its bytecode proves the absence of checked exits.
      }

      static Object impossibleHandler(final Object value) {
         try {
            noCheckedFailure();
         } catch (final IOException ex) {
            // This return without validation must not weaken the reachable path's requirement.
            return value;
         }
         return Objects.requireNonNull(value);
      }

      static Object checkedReturn(final Object value) {
         try {
            checkThenWork(value);
         } catch (final IOException ex) {
            // IOException is possible only after the helper's null check has succeeded.
         }
         return value;
      }

      static Object nestedReturn(final Object value) {
         return checkedReturn(value);
      }

      static Object aliasedReturn(final Object value) {
         final Object alias = value;
         try {
            checkThenWork(alias);
         } catch (final IOException ex) {
            // The proof belongs to the entry argument even though the helper receives an alias.
         }
         return value;
      }

      static Object ambiguousReturn(final Object first, final Object second, final boolean chooseFirst) {
         final Object chosen = chooseFirst ? first : second;
         try {
            checkThenWork(chosen);
         } catch (final IOException ex) {
            // Requiring the chosen reference does not require each possible source argument.
         }
         return first;
      }

      static Object failureBeforeCheck(final Object value) {
         try {
            workThenCheck(value);
         } catch (final IOException ex) {
            // This return can still receive null: a normal-completion summary alone is insufficient.
         }
         return value;
      }

      static CheckedOperation receiverReturn(final CheckedOperation receiver) {
         try {
            receiver.run();
         } catch (final IOException ex) {
            // The JVM rejects a null receiver before entering an unknown implementation.
         }
         return receiver;
      }

      static Object catchesNullFailure(final Object value) {
         try {
            checkThenWork(value);
         } catch (final Exception ex) {
            // The broad catch accepts the null-check failure as well as IOException.
         }
         return value;
      }

      static Object sharedHandler(final Object value) {
         try {
            checkThenWork(value);
         } catch (final IOException | RuntimeException ex) {
            // One handler label receives both the checked exit and the unchecked null-check failure.
         }
         return value;
      }

      @SuppressWarnings("finally") // The fixture must swallow the failure to exercise the exceptional return path.
      static Object finallyReturns(final Object value) {
         try {
            checkThenWork(value);
         } finally { // CHECKSTYLE:IGNORE ForbidReturnInFinallyBlock
            // A returning finally deliberately swallows both checked and unchecked failures.
            return value;
         }
      }

      @SuppressWarnings("unused") // Expose the callback's sneaky checked failure to a Java-source catch fixture.
      static void requireWithSupplier(final Object value) throws IOException {
         Objects.requireNonNull(value, Fixtures::messageFailure);
      }

      static String messageFailure() {
         return Fixtures.<RuntimeException>throwChecked(new IOException());
      }

      @SuppressWarnings("unchecked") // Model bytecode that violates a Java callback's declared throws contract.
      static <T extends Throwable> String throwChecked(final IOException failure) throws T {
         throw (T) failure;
      }

      static Object supplierFailure(final Object value) {
         try {
            requireWithSupplier(value);
         } catch (final IOException ex) {
            // A supplier can throw before requireNonNull constructs the NPE for a null argument.
         }
         return value;
      }
   }

   @Test
   @SuppressWarnings("null")
   void testCheckedExceptionBoundaries() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().enableSystemJarsAndModules().acceptClasses(Fixtures.class.getName(),
         CheckedOperation.class.getName()).scan()) {
         final ClassInfo info = scan.getClassInfo(Fixtures.class.getName());
         final var analyzer = new BytecodeAnalyzer(info, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertRequirements(analyzer, info, "checkedReturn", 0);
         assertRequirements(analyzer, info, "nestedReturn", 0);
         assertRequirements(analyzer, info, "aliasedReturn", 0);
         assertRequirements(analyzer, info, "receiverReturn", 0);
         assertRequirements(analyzer, info, "impossibleHandler", 0);
         assertRequirements(analyzer, info, "ambiguousReturn");
         assertRequirements(analyzer, info, "failureBeforeCheck");
         assertRequirements(analyzer, info, "catchesNullFailure");
         assertRequirements(analyzer, info, "sharedHandler");
         assertRequirements(analyzer, info, "finallyReturns");
         assertRequirements(analyzer, info, "supplierFailure");
      }
      assertThatNullPointerException().isThrownBy(() -> Fixtures.checkedReturn(null));
      assertThat(Fixtures.supplierFailure(null)).isNull();
   }

   @Test
   @SuppressWarnings("null")
   void testSignatureProviderRequirement() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().enableSystemJarsAndModules().acceptClasses("java.security.Signature")
         .scan()) {
         final ClassInfo info = scan.getClassInfo("java.security.Signature");
         final var analyzer = new BytecodeAnalyzer(info, new BytecodeAnalyzer.StaticFieldResolver(scan));
         final var method = info.getMethodInfo("getInstance").stream().filter(
            candidate -> "(Ljava/lang/String;Ljava/security/Provider;)Ljava/security/Signature;".equals(candidate.getTypeDescriptorStr()))
            .findFirst().orElseThrow();
         // RSA checks the provider locally; the other branch delegates. Redundant RSA work must not hide that second proof.
         assertThat(analyzer.determineDefinitelyNonNullMethodParameters(method)).containsExactlyInAnyOrder(0, 1);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testFilesCreateDirectories() {
      try (ScanResult scan = new ClassGraph().enableAllInfo().enableSystemJarsAndModules().acceptClasses(Files.class.getName()).scan()) {
         final ClassInfo info = scan.getClassInfo(Files.class.getName());
         final var analyzer = new BytecodeAnalyzer(info, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertRequirements(analyzer, info, "createAndCheckIsDirectory", 0);
         assertRequirements(analyzer, info, "createDirectories", 0);
      }
   }

   @Test
   void testGeneratedReturnAndStoredPolyNull(@TempDir final Path directory) throws IOException {
      final String signature = "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute<*>;)Ljava/nio/file/Path;";
      final Path input = directory.resolve("input");
      final var stored = new EEAFile(Files.class.getName());
      stored.addMember("createDirectories", signature).annotatedSignature.comment = "# @Generated(PolyNull)";
      stored.save(input, SaveOption.REPLACE_EXISTING);
      for (final var mode : EEAGenerator.GenerationMode.values()) {
         final Path output = directory.resolve(mode.name());
         final var config = new EEAGenerator.Config(output, "java.nio.file");
         config.classFilter = info -> info.getName().equals(Files.class.getName());
         config.inputDirs.add(input);
         config.generationMode = mode;
         EEAGenerator.generateEEAFiles(config);
         final var member = Objects.requireNonNull(EEAFile.load(output, Files.class.getName()).findMatchingClassMember("createDirectories",
            signature));
         if (mode == EEAGenerator.GenerationMode.FULL) {
            assertThat(member.annotatedSignature.value).isEqualTo(
               "(L1java/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute<*>;)L1java/nio/file/Path;");
            assertThat(member.annotatedSignature.comment).isEqualTo("# @Generated(2,66)");
         } else {
            // Additive mode may qualify the parameter while retaining an explicitly stored PolyNull return contract.
            assertThat(member.annotatedSignature.value).isEqualTo(
               "(L1java/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute<*>;)Ljava/nio/file/Path;");
            assertThat(member.annotatedSignature.comment).isEqualTo("# @Generated(2,PolyNull)");
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testExceptionTableOrder(@TempDir final Path directory) throws IOException {
      final String owner = "test/CheckedExceptionHandlers";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
      writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "checkedWork", "()V", null, null).visitEnd();
      for (final String variant : new String[] {"shadowed", "catchAllFirst", "narrowFirst"}) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, variant, "(Ljava/lang/Object;)V", null, null);
         final var start = new Label();
         final var end = new Label();
         final var firstHandler = new Label();
         final var laterHandler = new Label();
         final boolean narrowFirst = "narrowFirst".equals(variant);
         /* Java source rejects shadowed catches, but class files can contain them. Only a covering earlier handler
          * makes the later return unreachable; a narrower earlier catch leaves part of IOException unhandled. */
         method.visitTryCatchBlock(start, end, firstHandler, "catchAllFirst".equals(variant) ? null
               : narrowFirst ? "java/io/FileNotFoundException" : "java/io/IOException");
         method.visitTryCatchBlock(start, end, laterHandler, narrowFirst ? "java/io/IOException" : "java/io/FileNotFoundException");
         method.visitCode();
         method.visitLabel(start);
         method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "checkedWork", "()V", false);
         method.visitLabel(end);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
         method.visitInsn(Opcodes.POP);
         method.visitInsn(Opcodes.RETURN);
         method.visitLabel(firstHandler);
         method.visitInsn(Opcodes.POP);
         method.visitJumpInsn(Opcodes.GOTO, end);
         method.visitLabel(laterHandler);
         method.visitInsn(Opcodes.POP);
         method.visitInsn(Opcodes.RETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitEnd();
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, writer.toByteArray());
      try (ScanResult scan = new ClassGraph().enableAllInfo().enableSystemJarsAndModules().overrideClasspath(directory.toString())
         .acceptClasses(owner.replace('/', '.')).scan()) {
         final ClassInfo info = scan.getClassInfo(owner.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(info, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertRequirements(analyzer, info, "shadowed", 0);
         assertRequirements(analyzer, info, "catchAllFirst", 0);
         assertRequirements(analyzer, info, "narrowFirst");
      }
   }

   @Test
   @SuppressWarnings("null")
   void testDepthLimitAndCacheWarmth(@TempDir final Path directory) throws IOException {
      final String owner = "test/CheckedExceptionDepth";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
      for (int step = 0; step < 160; step++) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "step" + step, "(Ljava/lang/Object;)V", null, null);
         method.visitCode();
         method.visitVarInsn(Opcodes.ALOAD, 0);
         if (step == 159) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;",
               false);
            method.visitInsn(Opcodes.POP);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "checkedWork", "()V", false);
         } else {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step" + (step + 1), "(Ljava/lang/Object;)V", false);
         }
         method.visitInsn(Opcodes.RETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE, "checkedWork", "()V", null, null).visitEnd();
      for (final int firstStep : new int[] {0, 80}) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "catch" + firstStep, "(Ljava/lang/Object;)V", null,
            null);
         final var start = new Label();
         final var end = new Label();
         final var handler = new Label();
         final var done = new Label();
         method.visitTryCatchBlock(start, end, handler, "java/io/IOException");
         method.visitCode();
         method.visitLabel(start);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "step" + firstStep, "(Ljava/lang/Object;)V", false);
         method.visitLabel(end);
         method.visitJumpInsn(Opcodes.GOTO, done);
         method.visitLabel(handler);
         method.visitInsn(Opcodes.POP);
         method.visitLabel(done);
         method.visitInsn(Opcodes.RETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitEnd();
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, writer.toByteArray());
      try (ScanResult scan = new ClassGraph().enableAllInfo().enableSystemJarsAndModules().overrideClasspath(directory.toString())
         .acceptClasses(owner.replace('/', '.')).scan()) {
         final ClassInfo info = scan.getClassInfo(owner.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var analyzer = new BytecodeAnalyzer(info, new BytecodeAnalyzer.StaticFieldResolver(scan));
            if (warmFirst) {
               assertRequirements(analyzer, info, "catch80", 0);
            }
            // Warming the shorter checked-exception proof must not let the longer caller cross the depth limit.
            assertRequirements(analyzer, info, "catch0");
            assertRequirements(analyzer, info, "catch80", 0);
            assertRequirements(analyzer, info, "catch0");
         }
      }
   }

   @SuppressWarnings("null") // ClassGraph's loaded method declarations have no nullness annotations.
   private static void assertRequirements(final BytecodeAnalyzer analyzer, final ClassInfo info, final String method,
         final Integer... indexes) {
      assertThat(analyzer.determineDefinitelyNonNullMethodParameters(info.getMethodInfo(method).get(0))).as(method)
         .containsExactlyInAnyOrder(indexes);
   }
}
