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
import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;

/**
 * Checks return inference against JVM superclass selection, including private nest access and clone exception reachability.
 *
 * @author Vegard IT GmbH (https://vegardit.com) and contributors
 */
class BytecodeReturnDispatchTest {

   private static final String OBJECT_RETURN = "()Ljava/lang/Object;";

   @Test
   @SuppressWarnings("null")
   void testNestmateCloneWriteUsesWriterHierarchy(@TempDir final Path directory) throws Exception {
      for (final boolean nullableClone : new boolean[] {true, false}) {
         final String owner = "test/CloneFieldOwner" + nullableClone;
         final String writerName = owner + "$Writer";
         final String base = "test/NullCloneBase";
         // ASM permits a nullable clone override without weakening the compiler's existing Object.clone EEA contract.
         final var baseWriter = newClass(base, "java/lang/Object");
         final var clone = baseWriter.visitMethod(Opcodes.ACC_PROTECTED, "clone", OBJECT_RETURN, null, null);
         clone.visitCode();
         clone.visitInsn(Opcodes.ACONST_NULL);
         clone.visitInsn(Opcodes.ARETURN);
         clone.visitMaxs(0, 0);
         clone.visitEnd();
         writeClass(directory, base, baseWriter);

         final var ownerWriter = newClass(owner, "java/lang/Object", nullableClone ? new String[] {"java/lang/Cloneable"} : new String[0]);
         ownerWriter.visitNestMember(writerName);
         ownerWriter.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "value", "Ljava/lang/Object;", null, null).visitEnd();
         for (final boolean guarded : new boolean[] {true, false}) {
            final var read = ownerWriter.visitMethod(Opcodes.ACC_PUBLIC, guarded ? "read" : "stored", OBJECT_RETURN, null, null);
            read.visitCode();
            if (guarded) {
               final var nonNull = new Label();
               read.visitVarInsn(Opcodes.ALOAD, 0);
               read.visitFieldInsn(Opcodes.GETFIELD, owner, "value", "Ljava/lang/Object;");
               read.visitJumpInsn(Opcodes.IFNONNULL, nonNull);
               read.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
               read.visitInsn(Opcodes.DUP);
               read.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
               read.visitInsn(Opcodes.ARETURN);
               read.visitLabel(nonNull);
            }
            read.visitVarInsn(Opcodes.ALOAD, 0);
            read.visitFieldInsn(Opcodes.GETFIELD, owner, "value", "Ljava/lang/Object;");
            read.visitInsn(Opcodes.ARETURN);
            read.visitMaxs(0, 0);
            read.visitEnd();
         }
         writeClass(directory, owner, ownerWriter);

         final String parent = nullableClone ? base : "java/lang/Object";
         final var writer = newClass(writerName, parent, nullableClone ? new String[0] : new String[] {"java/lang/Cloneable"});
         writer.visitNestHost(owner);
         final var write = writer.visitMethod(Opcodes.ACC_PUBLIC, "write", "(L" + owner + ";)V", null, null);
         write.visitCode();
         write.visitVarInsn(Opcodes.ALOAD, 1);
         write.visitVarInsn(Opcodes.ALOAD, 0);
         write.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "clone", OBJECT_RETURN, false);
         write.visitFieldInsn(Opcodes.PUTFIELD, owner, "value", "Ljava/lang/Object;");
         write.visitInsn(Opcodes.RETURN);
         write.visitMaxs(0, 0);
         write.visitEnd();
         writeClass(directory, writerName, writer);

         try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
              ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
            final Class<?> ownerType = loader.loadClass(owner.replace('/', '.'));
            final Class<?> writerType = loader.loadClass(writerName.replace('/', '.'));
            final Object instance = ownerType.getConstructor().newInstance();
            writerType.getMethod("write", ownerType).invoke(writerType.getConstructor().newInstance(), instance);
            assertThat(ownerType.getMethod("stored").invoke(instance) == null).isEqualTo(nullableClone);
            final ClassInfo ownerInfo = scan.getClassInfo(owner.replace('/', '.'));
            // A concurrent null write can invalidate read()'s guard; a valid writer clone must retain the positive proof.
            assertReturn(new BytecodeAnalyzer(ownerInfo, new BytecodeAnalyzer.StaticFieldResolver(scan)), ownerInfo, "read", nullableClone
                  ? Nullability.UNKNOWN
                  : Nullability.NEVER_NULL);
         }
      }
   }

   @Test
   void testSuperclassReturnUsesExactTarget(@TempDir final Path directory) throws Exception {
      assertSuperclassReturn(directory, false);
   }

   @Test
   void testPrivateSuperclassReturnUsesExactTarget(@TempDir final Path directory) throws Exception {
      assertSuperclassReturn(directory, true);
   }

   @Test
   void testCloneReturnUsesSelectedSuperclass(@TempDir final Path directory) throws Exception {
      assertCloneOverride(directory, false);
   }

   @Test
   void testCloneHandlerRemainsReachable(@TempDir final Path directory) throws Exception {
      assertCloneOverride(directory, true);
   }

   @Test
   @SuppressWarnings("null")
   void testInterfaceCloneDoesNotUseSuperclassLookup(@TempDir final Path directory) throws Exception {
      final String contract = "test/CloneContract";
      final String child = "test/InterfaceCloneChild";
      final var contractWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      contractWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, contract, null,
         "java/lang/Object", null);
      final var clone = contractWriter.visitMethod(Opcodes.ACC_PUBLIC, "clone", OBJECT_RETURN, null, null);
      clone.visitCode();
      clone.visitInsn(Opcodes.ACONST_NULL);
      clone.visitInsn(Opcodes.ARETURN);
      clone.visitMaxs(0, 0);
      clone.visitEnd();
      writeClass(directory, contract, contractWriter);
      final var childWriter = newClass(child, "java/lang/Object", "java/lang/Cloneable", contract);
      final var forward = childWriter.visitMethod(Opcodes.ACC_PUBLIC, "forward", OBJECT_RETURN, null, null);
      forward.visitCode();
      forward.visitVarInsn(Opcodes.ALOAD, 0);
      // An interface-super call selects its default method; Object is not the starting point for this lookup.
      forward.visitMethodInsn(Opcodes.INVOKESPECIAL, contract, "clone", OBJECT_RETURN, true);
      forward.visitInsn(Opcodes.ARETURN);
      forward.visitMaxs(0, 0);
      forward.visitEnd();
      writeClass(directory, child, childWriter);
      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         assertThat(invoke(loader, child, "forward")).isNull();
         final ClassInfo childClass = scan.getClassInfo(child.replace('/', '.'));
         assertReturn(new BytecodeAnalyzer(childClass, new BytecodeAnalyzer.StaticFieldResolver(scan)), childClass, "forward",
            Nullability.UNKNOWN);
      }
   }

   @SuppressWarnings("null")
   private void assertSuperclassReturn(final Path directory, final boolean privateMethod) throws Exception {
      final String grandparent = "test/ReturnGrandparent";
      final String parent = "test/ReturnParent";
      final String direct = "test/DirectReturn";
      final String indirect = "test/IndirectReturn";
      for (final boolean isGrandparent : new boolean[] {true, false}) {
         final String owner = isGrandparent ? grandparent : parent;
         final var writer = newClass(owner, isGrandparent ? "java/lang/Object" : grandparent);
         if (privateMethod) {
            /* A valid nest makes the private ancestor callable. Its privacy does not bypass the JVM's
             * superclass selection, so the intermediate declaration must still invalidate its return proof. */
            if (isGrandparent) {
               writer.visitNestMember(parent);
               writer.visitNestMember(direct);
               writer.visitNestMember(indirect);
            } else {
               writer.visitNestHost(grandparent);
            }
         }
         final var make = writer.visitMethod(isGrandparent && privateMethod ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PUBLIC, "make",
            OBJECT_RETURN, null, null);
         make.visitCode();
         if (isGrandparent) {
            make.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            make.visitInsn(Opcodes.DUP);
            make.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
         } else {
            make.visitInsn(Opcodes.ACONST_NULL);
         }
         make.visitInsn(Opcodes.ARETURN);
         make.visitMaxs(0, 0);
         make.visitEnd();
         if (isGrandparent) {
            writeForwarder(writer, "sameClass", grandparent, "make", false);
         }
         writeClass(directory, owner, writer);
      }
      for (final boolean isDirect : new boolean[] {true, false}) {
         final String owner = isDirect ? direct : indirect;
         final var writer = newClass(owner, isDirect ? grandparent : parent);
         if (privateMethod) {
            writer.visitNestHost(grandparent);
         }
         writeForwarder(writer, "forward", grandparent, "make", false);
         writeClass(directory, owner, writer);
      }

      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         // Execute first: invalid bytecode or inaccessible methods must not masquerade as a nullness counterexample.
         assertThat(invoke(loader, indirect, "forward")).isNull();
         assertThat(invoke(loader, direct, "forward")).isNotNull();
         assertThat(invoke(loader, grandparent, "sameClass")).isNotNull();
         final ClassInfo directClass = scan.getClassInfo(direct.replace('/', '.'));
         final ClassInfo indirectClass = scan.getClassInfo(indirect.replace('/', '.'));
         final ClassInfo grandparentClass = scan.getClassInfo(grandparent.replace('/', '.'));
         for (final boolean warmFirst : new boolean[] {false, true}) {
            final var resolver = new BytecodeAnalyzer.MethodSummaryResolver(new BytecodeAnalyzer.StaticFieldResolver(scan));
            final var directAnalyzer = new BytecodeAnalyzer(directClass, resolver);
            final var indirectAnalyzer = new BytecodeAnalyzer(indirectClass, resolver);
            if (warmFirst) {
               assertReturn(directAnalyzer, directClass, "forward", Nullability.NEVER_NULL);
            }
            /* The shared cache describes the ancestor's body, not its eligibility at a particular call site.
             * Neither a warm non-null summary nor a previously rejected call may change the other caller's result. */
            assertReturn(indirectAnalyzer, indirectClass, "forward", Nullability.UNKNOWN);
            assertReturn(directAnalyzer, directClass, "forward", Nullability.NEVER_NULL);
            assertReturn(indirectAnalyzer, indirectClass, "forward", Nullability.UNKNOWN);
            assertReturn(new BytecodeAnalyzer(grandparentClass, resolver), grandparentClass, "sameClass", Nullability.NEVER_NULL);
         }
      }
   }

   @SuppressWarnings("null")
   private void assertCloneOverride(final Path directory, final boolean throwsException) throws Exception {
      final String parent = "test/CloneParent";
      final String child = "test/CloneChild";
      final var parentWriter = newClass(parent, "java/lang/Object");
      final var clone = parentWriter.visitMethod(Opcodes.ACC_PROTECTED, "clone", OBJECT_RETURN, null, null);
      clone.visitCode();
      if (throwsException) {
         clone.visitTypeInsn(Opcodes.NEW, "java/lang/CloneNotSupportedException");
         clone.visitInsn(Opcodes.DUP);
         clone.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/CloneNotSupportedException", "<init>", "()V", false);
         clone.visitInsn(Opcodes.ATHROW);
      } else {
         clone.visitInsn(Opcodes.ACONST_NULL);
         clone.visitInsn(Opcodes.ARETURN);
      }
      clone.visitMaxs(0, 0);
      clone.visitEnd();
      writeClass(directory, parent, parentWriter);
      final var childWriter = newClass(child, parent, "java/lang/Cloneable");
      // Naming Object.clone directly must not skip the intermediate override selected for this Cloneable receiver.
      writeForwarder(childWriter, "forward", "java/lang/Object", "clone", throwsException);
      writeClass(directory, child, childWriter);

      try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
           ScanResult scan = new ClassGraph().enableAllInfo().overrideClasspath(directory.toString()).acceptPackages("test").scan()) {
         assertThat(invoke(loader, child, "forward")).isNull();
         final ClassInfo childClass = scan.getClassInfo(child.replace('/', '.'));
         final var analyzer = new BytecodeAnalyzer(childClass, new BytecodeAnalyzer.StaticFieldResolver(scan));
         /* A live catch returns an explicit null; a call whose body is not imported remains unknown.
          * Both outcomes must withdraw the incorrect Object.clone non-null proof. */
         assertReturn(analyzer, childClass, "forward", throwsException ? Nullability.DEFINITELY_NULL : Nullability.UNKNOWN);
      }
   }

   private static ClassWriter newClass(final String owner, final String parent, final String... interfaces) {
      final var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
      writer.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, owner, null, parent, interfaces);
      final var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();
      return writer;
   }

   private static void writeForwarder(final ClassWriter writer, final String name, final String target, final String method,
         final boolean catchCloneException) {
      final var forward = writer.visitMethod(Opcodes.ACC_PUBLIC, name, OBJECT_RETURN, null, null);
      forward.visitCode();
      final var start = new Label();
      final var end = new Label();
      final var handler = new Label();
      if (catchCloneException) {
         forward.visitTryCatchBlock(start, end, handler, "java/lang/CloneNotSupportedException");
      }
      forward.visitLabel(start);
      forward.visitVarInsn(Opcodes.ALOAD, 0);
      forward.visitMethodInsn(Opcodes.INVOKESPECIAL, target, method, OBJECT_RETURN, false);
      forward.visitLabel(end);
      forward.visitInsn(Opcodes.ARETURN);
      if (catchCloneException) {
         forward.visitLabel(handler);
         forward.visitInsn(Opcodes.POP);
         forward.visitInsn(Opcodes.ACONST_NULL);
         forward.visitInsn(Opcodes.ARETURN);
      }
      forward.visitMaxs(0, 0);
      forward.visitEnd();
   }

   private static void writeClass(final Path directory, final String owner, final ClassWriter writer) throws IOException {
      writer.visitEnd();
      final Path classFile = directory.resolve(owner + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, Objects.requireNonNull(writer.toByteArray()));
   }

   private static @Nullable Object invoke(final ClassLoader loader, final String owner, final String method) throws Exception {
      final Class<?> type = loader.loadClass(owner.replace('/', '.'));
      return type.getMethod(method).invoke(type.getConstructor().newInstance());
   }

   @SuppressWarnings("null")
   private static void assertReturn(final BytecodeAnalyzer analyzer, final ClassInfo owner, final String method,
         final Nullability expected) {
      assertThat(analyzer.determineMethodReturnTypeNullability(owner.getMethodInfo(method).get(0))).as(owner.getName() + "." + method)
         .isEqualTo(expected);
   }
}
