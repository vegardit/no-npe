/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import com.vegardit.no_npe.eea_generator.EEAFile;
import com.vegardit.no_npe.eea_generator.EEAGenerator;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Checks return-value refinement without confusing checked references with mutable fields, replacements, or exception paths.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeValueRefinementTest {

   /** Mutable fields provide unknown values independently of entry-parameter contracts and final-field proofs. */
   @NonNullByDefault({})
   public static final class Checks {
      public Object value;
      public Object[] array;
      public double[] wideArray;
      public Checks other;
      public Runnable task;
      public long wide;

      public Object requireLocal() {
         final Object local = value;
         Objects.requireNonNull(local);
         return local;
      }

      public Object requireAlias() {
         final Object local = value;
         final Object alias = local;
         Objects.requireNonNull(alias);
         return local;
      }

      public Object requireMessage(final String message) {
         final Object local = value;
         Objects.requireNonNull(local, message);
         return local;
      }

      @SuppressWarnings("null") // A null message supplier must not qualify as a requirement on a successful checked value.
      public Object requireSupplier(final Supplier<String> message) {
         final Object local = value;
         Objects.requireNonNull(local, message);
         return local;
      }

      public Object callReceiver() {
         final Object local = value;
         local.toString();
         return local;
      }

      public Runnable interfaceReceiver() {
         final Runnable local = task;
         local.run();
         return local;
      }

      public Object[] arrayLength() {
         final Object[] local = array;
         if (local.length == 0)
            return new Object[0];
         return local;
      }

      public Object[] arrayRead() {
         final Object[] local = array;
         value = local[0];
         return local;
      }

      public Object[] arrayWrite(final Object element) {
         final Object[] local = array;
         local[0] = element;
         return local;
      }

      public double[] wideArrayWrite(final double element) {
         final double[] local = wideArray;
         local[0] = element;
         return local;
      }

      public Checks fieldRead() {
         final Checks local = other;
         value = local.value;
         return local;
      }

      public Checks fieldWrite(final Object replacement) {
         final Checks local = other;
         local.value = replacement;
         return local;
      }

      public Checks wideFieldWrite(final long replacement) {
         final Checks local = other;
         local.wide = replacement;
         return local;
      }

      public Object monitor() {
         final Object local = value;
         synchronized (local) {
            value = null;
         }
         return local;
      }

      public Object checkedMerge(final boolean first) {
         final Object selected = first ? value : other;
         Objects.requireNonNull(selected);
         return selected;
      }

      public Object preservedAlias() {
         final Object original = value;
         Object checked = original;
         Objects.requireNonNull(checked);
         checked = null;
         // Replacing one alias cannot invalidate the non-null fact about the original reference.
         value = checked;
         return original;
      }

      public Object caughtCheck() {
         final Object local = value;
         try {
            Objects.requireNonNull(local);
         } catch (final NullPointerException ex) { /* The null value can reach the return through this handler. */}
         return local;
      }

      public Object caughtDereference() {
         final Object local = value;
         try {
            local.toString();
         } catch (final NullPointerException ex) { /* The normal-edge fact must not leak into the handler. */}
         return local;
      }

      public Object caughtFallback() {
         final Object local = value;
         try {
            Objects.requireNonNull(local);
            return local;
         } catch (final NullPointerException ex) {
            return new Object();
         }
      }

      public Object conditional(final boolean check) {
         final Object local = value;
         if (check) {
            Objects.requireNonNull(local);
         }
         return local;
      }

      public Object reassigned() {
         Object local = value;
         Objects.requireNonNull(local);
         local = other;
         return local;
      }

      public Object laterFieldRead() {
         final Object local = value;
         Objects.requireNonNull(local);
         // Another invocation can change this public field even though the saved local stays non-null.
         return value;
      }

      public Object separateFieldRead() {
         final Object first = value;
         final Object second = value;
         Objects.requireNonNull(first);
         return second;
      }

      public Object mergedCheck(final boolean useFirst) {
         final Object first = value;
         final Object selected = useFirst ? first : other;
         Objects.requireNonNull(selected);
         return first;
      }

      public Object arrayElement() {
         return array[0];
      }

      public Object fieldValue() {
         return other.value;
      }

      public Object sameAsThis(final Object candidate) {
         if (candidate == this)
            return candidate;
         return new Object();
      }

      public Object sameAsLiteral(final Object candidate) {
         final Object known = "sentinel";
         if (known == candidate)
            return candidate;
         return new Object();
      }

      public Object sameAsCheckedLocal() {
         final Object checked = value;
         Objects.requireNonNull(checked);
         final Object candidate = other;
         if (candidate == checked)
            return candidate;
         return new Object();
      }

      public Object nullComparisonFallback() {
         final Object local = value;
         final Object absent = null;
         return local != absent ? local : new Object();
      }

      public Object nullComparisonReturn() {
         final Object local = value;
         final Object absent = null;
         return local == absent ? local : new Object();
      }

      public Object differentFromThis(final Object candidate) {
         if (candidate != this)
            return candidate;
         return new Object();
      }

      public Object unknownComparison(final Object known) {
         final Object local = value;
         if (local == known)
            return local;
         return new Object();
      }

      public Object reassignedComparison(final Object candidate) {
         Object local = candidate;
         if (local != this)
            return new Object();
         local = value;
         return local;
      }

      public Object loopedCheckedAlias(final int count) {
         Object selected = value;
         for (int index = 0; index < count; index++) {
            selected = other;
         }
         final Object alias = selected;
         Objects.requireNonNull(alias);
         return selected;
      }

      public Object loopedSeparateValues(final int count) {
         Object first = value;
         Object second = other;
         for (int index = 0; index < count; index++) {
            first = second;
            second = value;
         }
         Objects.requireNonNull(first);
         return second;
      }

      public Object savedPredicateAfterMerge(final boolean chooseFirst, final boolean chooseSecond) {
         Object first = value;
         Object second = other;
         if (chooseFirst) {
            first = second;
         }
         final boolean present = Objects.nonNull(first);
         if (chooseSecond) {
            second = first;
         }
         // The saved test checks first, not every reference sharing some of its possible producers.
         return present ? second : new Object();
      }
   }

   @Test
   @SuppressWarnings("null")
   void testSuccessfulChecksRefineReturnedValues() {
      try (ScanResult scan = scanChecks()) {
         final ClassInfo owner = scan.getClassInfo(Checks.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"requireLocal", "requireAlias", "requireMessage", "requireSupplier", "callReceiver",
            "interfaceReceiver", "arrayLength", "arrayRead", "arrayWrite", "wideArrayWrite", "fieldRead", "fieldWrite", "wideFieldWrite",
            "monitor", "checkedMerge", "preservedAlias", "caughtFallback", "loopedCheckedAlias"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testChecksPreserveUnknownAlternatives() {
      try (ScanResult scan = scanChecks()) {
         final ClassInfo owner = scan.getClassInfo(Checks.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"caughtCheck", "caughtDereference", "conditional", "reassigned", "laterFieldRead",
            "separateFieldRead", "mergedCheck", "arrayElement", "fieldValue", "loopedSeparateValues", "savedPredicateAfterMerge"}) {
            assertReturn(analyzer, owner, method, Nullability.UNKNOWN);
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testNullChecksKeepOnlyReachableReturns(@TempDir final Path directory) throws IOException {
      // Construct these instructions directly: ECJ rejects a known-null dereference and can remove its dead continuation.
      final String ownerName = "test/NullContinuations";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, ownerName, null, "java/lang/Object", null);
      for (final String name : new String[] {"checkedNull", "dereferencedNull", "caughtNull"}) {
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
         final var start = new Label();
         final var end = new Label();
         final var handler = new Label();
         method.visitCode();
         if ("caughtNull".equals(name)) {
            method.visitTryCatchBlock(start, end, handler, "java/lang/NullPointerException");
         }
         method.visitInsn(Opcodes.ACONST_NULL);
         method.visitVarInsn(Opcodes.ASTORE, 0);
         method.visitLabel(start);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         if ("dereferencedNull".equals(name)) {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
         } else {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;",
               false);
         }
         method.visitInsn(Opcodes.POP);
         method.visitLabel(end);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitInsn(Opcodes.ARETURN);
         if ("caughtNull".equals(name)) {
            method.visitLabel(handler);
            method.visitInsn(Opcodes.POP);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitInsn(Opcodes.ARETURN);
         }
         method.visitMaxs(0, 0);
         method.visitEnd();
      }
      writer.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(ownerName + ".class"), writer.toByteArray());
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         final ClassInfo owner = scan.getClassInfo(ownerName.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         assertReturn(analyzer, owner, "checkedNull", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "dereferencedNull", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "caughtNull", Nullability.DEFINITELY_NULL);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testMergedValuesDoNotBecomeAliases(@TempDir final Path directory) throws Exception {
      final String ownerName = "test/MergedAliases";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, ownerName, null, "java/lang/Object", null);
      for (final String field : new String[] {"first", "second"}) {
         writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "Ljava/lang/Object;", null, null).visitEnd();
      }
      final var expected = new LinkedHashMap<String, Nullability>();
      for (int variant = 0; variant < 32; variant++) {
         final boolean copyCheckedValue = (variant & 4) != 0;
         final int checkKind = variant >>> 3;
         final String name = "merged" + variant;
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(ZZ)Ljava/lang/Object;", null, null);
         method.visitCode();
         method.visitFieldInsn(Opcodes.GETSTATIC, ownerName, "first", "Ljava/lang/Object;");
         method.visitVarInsn(Opcodes.ASTORE, 2);
         method.visitFieldInsn(Opcodes.GETSTATIC, ownerName, "second", "Ljava/lang/Object;");
         method.visitVarInsn(Opcodes.ASTORE, 3);
         for (int condition = 0; condition < 2; condition++) {
            final var join = new Label();
            method.visitVarInsn(Opcodes.ILOAD, condition);
            if ((variant & 1 << condition) == 0) {
               final var assign = new Label();
               /* ASM visits the jump target first. An initially aliasing path must not hide a later path where the
                * producer sets widen but the two runtime references differ. Cover both layouts for each condition. */
               method.visitJumpInsn(Opcodes.IFNE, assign);
               method.visitJumpInsn(Opcodes.GOTO, join);
               method.visitLabel(assign);
            } else {
               method.visitJumpInsn(Opcodes.IFEQ, join);
            }
            method.visitVarInsn(Opcodes.ALOAD, condition == 0 ? 3 : 2);
            method.visitVarInsn(Opcodes.ASTORE, condition == 0 ? 2 : 3);
            method.visitLabel(join);
         }
         if (copyCheckedValue) {
            // A copy after the joins is a real alias even though its value has several possible producers.
            method.visitVarInsn(Opcodes.ALOAD, 2);
            method.visitVarInsn(Opcodes.ASTORE, 3);
         }
         method.visitVarInsn(Opcodes.ALOAD, 2);
         if (checkKind == 1) {
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
         } else if (checkKind == 0) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;",
               false);
            method.visitInsn(Opcodes.POP);
         } else {
            final var checked = new Label();
            if (checkKind == 3) {
               method.visitLdcInsn("present");
            }
            method.visitJumpInsn(checkKind == 2 ? Opcodes.IFNONNULL : Opcodes.IF_ACMPEQ, checked);
            method.visitLdcInsn("fallback");
            method.visitInsn(Opcodes.ARETURN);
            method.visitLabel(checked);
         }
         method.visitVarInsn(Opcodes.ALOAD, 3);
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
         expected.put(name, copyCheckedValue ? Nullability.NEVER_NULL : Nullability.UNKNOWN);
      }
      writer.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(ownerName + ".class"), writer.toByteArray());
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         final Class<?> runtimeType = loader.loadClass(ownerName.replace('/', '.'));
         runtimeType.getField("first").set(null, "present");
         final ClassInfo owner = scan.getClassInfo(ownerName.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final var expectation : expected.entrySet()) {
            // Verify the fixture's actual null-returning path as well as the abstract contract.
            assertThat(runtimeType.getMethod(expectation.getKey(), boolean.class, boolean.class).invoke(null, false, false)).as(expectation
               .getKey()).isEqualTo(expectation.getValue() == Nullability.NEVER_NULL ? "present" : null);
            assertReturn(analyzer, owner, expectation.getKey(), expectation.getValue());
         }
      }
   }

   @Test
   @SuppressWarnings("null")
   void testReferenceComparisonsRefineReturnedValues() {
      try (ScanResult scan = scanChecks()) {
         final ClassInfo owner = scan.getClassInfo(Checks.class.getName());
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final String method : new String[] {"sameAsThis", "sameAsLiteral", "sameAsCheckedLocal", "nullComparisonFallback"}) {
            assertReturn(analyzer, owner, method, Nullability.NEVER_NULL);
         }
         assertReturn(analyzer, owner, "nullComparisonReturn", Nullability.DEFINITELY_NULL);
         assertReturn(analyzer, owner, "differentFromThis", Nullability.POLY_NULL);
         assertReturn(analyzer, owner, "unknownComparison", Nullability.UNKNOWN);
         assertReturn(analyzer, owner, "reassignedComparison", Nullability.UNKNOWN);
      }
   }

   @Test
   @SuppressWarnings("null")
   void testReferenceComparisonEdges(@TempDir final Path directory) throws IOException {
      // ASM keeps null comparisons as IF_ACMP instructions even if a Java compiler would simplify them to IFNULL.
      final String ownerName = "test/ComparisonValues";
      final var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, ownerName, null, "java/lang/Object", null);
      writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "Ljava/lang/Object;", null, null).visitEnd();
      final var expected = new LinkedHashMap<String, Nullability>();
      for (int variant = 0; variant < 16; variant++) {
         // Four bits cover both operand orders, opcodes, known nullnesses, and selected return edges.
         final boolean knownNull = (variant & 1) == 0;
         final boolean knownOnLeft = (variant & 2) == 0;
         final int opcode = (variant & 4) == 0 ? Opcodes.IF_ACMPEQ : Opcodes.IF_ACMPNE;
         final boolean returnOnEquality = (variant & 8) == 0;
         final String name = "comparison" + variant;
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
         final var target = new Label();
         method.visitCode();
         method.visitFieldInsn(Opcodes.GETSTATIC, ownerName, "value", "Ljava/lang/Object;");
         method.visitVarInsn(Opcodes.ASTORE, 0);
         if (knownNull) {
            method.visitInsn(Opcodes.ACONST_NULL);
         } else {
            method.visitLdcInsn("sentinel");
         }
         method.visitVarInsn(Opcodes.ASTORE, 1);
         method.visitVarInsn(Opcodes.ALOAD, knownOnLeft ? 1 : 0);
         method.visitVarInsn(Opcodes.ALOAD, knownOnLeft ? 0 : 1);
         method.visitJumpInsn(opcode, target);
         for (final boolean jumpEdge : new boolean[] {false, true}) {
            if (jumpEdge) {
               method.visitLabel(target);
            }
            final boolean equalityEdge = jumpEdge == (opcode == Opcodes.IF_ACMPEQ);
            if (equalityEdge == returnOnEquality) {
               method.visitVarInsn(Opcodes.ALOAD, 0);
            } else {
               method.visitLdcInsn("fallback");
            }
            method.visitInsn(Opcodes.ARETURN);
         }
         method.visitMaxs(0, 0);
         method.visitEnd();
         expected.put(name, knownNull ? returnOnEquality ? Nullability.DEFINITELY_NULL : Nullability.NEVER_NULL
               : returnOnEquality ? Nullability.NEVER_NULL : Nullability.UNKNOWN);
      }
      for (final int opcode : new int[] {Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE}) {
         final String name = "collapsed" + opcode;
         final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()Ljava/lang/Object;", null, null);
         final var target = new Label();
         method.visitCode();
         method.visitFieldInsn(Opcodes.GETSTATIC, ownerName, "value", "Ljava/lang/Object;");
         method.visitVarInsn(Opcodes.ASTORE, 0);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitLdcInsn("sentinel");
         method.visitJumpInsn(opcode, target);
         // Both outcomes reach this same return, so the equality fact cannot qualify it on its own.
         method.visitLabel(target);
         method.visitVarInsn(Opcodes.ALOAD, 0);
         method.visitInsn(Opcodes.ARETURN);
         method.visitMaxs(0, 0);
         method.visitEnd();
         expected.put(name, Nullability.UNKNOWN);
      }
      writer.visitEnd();
      Files.createDirectories(directory.resolve("test"));
      Files.write(directory.resolve(ownerName + ".class"), writer.toByteArray());
      try (ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         final ClassInfo owner = scan.getClassInfo(ownerName.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(owner, new BytecodeAnalyzer.StaticFieldResolver(scan));
         for (final var expectation : expected.entrySet()) {
            assertReturn(analyzer, owner, expectation.getKey(), expectation.getValue());
         }
      }
   }

   @Test
   void testGeneratedReturnContracts(@TempDir final Path directory) throws IOException {
      final var config = new EEAGenerator.Config(directory, getClass().getPackageName());
      config.classFilter = info -> info.getName().equals(Checks.class.getName());
      for (final var mode : EEAGenerator.GenerationMode.values()) {
         config.generationMode = mode;
         EEAGenerator.generateEEAFiles(config);
         final EEAFile file = EEAFile.load(directory, Checks.class.getName());
         assertSignature(file, "requireLocal", "()L1java/lang/Object;");
         assertSignature(file, "arrayRead", "()[1Ljava/lang/Object;");
         assertSignature(file, "caughtCheck", "()Ljava/lang/Object;");
         assertSignature(file, "laterFieldRead", "()Ljava/lang/Object;");
         assertSignature(file, "sameAsThis", "(Ljava/lang/Object;)L1java/lang/Object;");
         assertSignature(file, "nullComparisonFallback", "()L1java/lang/Object;");
         assertSignature(file, "nullComparisonReturn", "()L0java/lang/Object;");
      }
   }

   @SuppressWarnings("null") // ClassGraph's result is present after successful scanning.
   private static ScanResult scanChecks() {
      return new ClassGraph().enableAllInfo().acceptClasses(Checks.class.getName()).scan();
   }

   @SuppressWarnings("null")
   private static void assertReturn(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method,
         final Nullability expected) {
      assertThat(analyzer.determineMethodReturnAnalysis(owner.getMethodInfo(method).get(0)).getNullability()).as(method).isEqualTo(
         expected);
   }

   private static void assertSignature(final EEAFile file, final String method, final String signature) {
      assertThat(file.getClassMembers().filter(member -> member.name.value.equals(method)).findFirst()
         .orElseThrow().annotatedSignature.value).as(method).isEqualTo(signature);
   }
}
