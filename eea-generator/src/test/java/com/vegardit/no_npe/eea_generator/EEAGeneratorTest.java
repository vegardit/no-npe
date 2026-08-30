/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.meta.TypeQualifierNickname;
import javax.annotation.meta.When;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer;

import io.github.classgraph.ClassGraph;

/**
 * Verifies generator inference, stored-contract updates, inheritance, ownership metadata, and artifact minimization.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
@SuppressWarnings("null")
class EEAGeneratorTest {

   public static class HiddenFieldParent {
      public String value = "";
   }

   @SuppressWarnings("hiding") // Intentional fixture for verifying that hidden fields do not inherit annotations.
   public static final class HiddenFieldChild extends HiddenFieldParent {
      public String value = "";
   }

   public static final class ComparableType implements Comparable<String> {
      @Override
      public int compareTo(final String other) {
         return other == null ? -1 : 0;
      }

      public int unrelated(final String value) {
         return value.length();
      }
   }

   public static final class ExplicitlyNullableComparable implements Comparable<String> {
      @Override
      public int compareTo(final @Nullable String other) {
         return other == null ? -1 : other.length();
      }
   }

   public abstract static class ExplicitParameterParent {
      public abstract void clearInherited(String first, Object second);

      public abstract void cleared(String first, Object second);

      public abstract void extend(String first, Object second);

      public abstract void inherit(String first, Object second);

      public abstract void match(String first, Object second);

      public abstract List<String> merge(List<String> values);

      public abstract void shrinkInherited(String first, Object second);

      public abstract void update(String first, Object second);
   }

   public static final class ExplicitParameterChild extends ExplicitParameterParent {
      @Override
      public void clearInherited(final String first, final Object second) {
      }

      @Override
      public void cleared(final String first, final Object second) {
      }

      @Override
      public void extend(final String first, final Object second) {
      }

      @Override
      public void inherit(final String first, final Object second) {
      }

      @Override
      public void match(final String first, final Object second) {
      }

      @Override
      public List<String> merge(final List<String> values) {
         return values;
      }

      @Override
      public void shrinkInherited(final String first, final Object second) {
      }

      @Override
      public void update(final @Nullable String first, final Object second) {
      }
   }

   public interface GenericListenerRegistration {
      <T extends java.util.EventListener> void addGenericListener(T listener);
   }

   public interface MultiTypeVariableGenericListenerRegistration {
      // X intentionally precedes the listener type variable; inserting after '<' would annotate X instead of T.
      <X, T extends java.util.EventListener> void addGenericListener(T listener);
   }

   public interface UnavailableRelationshipParent {
      String relationshipValue(String value);
   }

   public interface AvailableRelationshipParent {
      String relationshipValue(String value);
   }

   public static final class MultipleRelationshipParents implements UnavailableRelationshipParent, AvailableRelationshipParent {
      @Override
      public String relationshipValue(final String value) {
         return value;
      }
   }

   public interface TransitiveInterfaceBase {
      void accept(String value);
   }

   public interface TransitiveInterfaceDerived extends TransitiveInterfaceBase {
      @Override
      void accept(String value);
   }

   // Keep the redundant class-file interface entry: it reproduces the flattened ancestor set that must not look conflicting.
   public static final class TransitiveInterfaceImplementation implements TransitiveInterfaceDerived {
      @Override
      public void accept(final String value) {
      }
   }

   public static final class ExplicitParameterAnnotations {
      public String combine(final @Nullable String nullable, final String nonNull, final List<String> values, final int count) {
         return nullable == null ? nonNull : nullable + values.size() + count;
      }

      public boolean consumeArray(final @Nullable String[] values) {
         return values == null;
      }

      public <T> boolean consumeTypeVariable(final @Nullable T value) {
         return value == null;
      }
   }

   public static final class ReceiverParameterContracts {
      public void caughtAndRethrown(final Object value) {
         try {
            value.toString();
         } catch (final RuntimeException ex) {
            throw new IllegalStateException(ex);
         }
      }

      public void explicitNullable(final @Nullable Object value) {
         value.toString();
      }

      public <T> void generic(final T value) {
         value.toString();
      }

      public void keptInferred(final Object value) {
         value.toString();
      }

      public void onlyFirst(final Object first, @SuppressWarnings("unused") final Object second) {
         first.toString();
      }

      public int primitiveReturn(final Object value) {
         return value.hashCode();
      }

      public Object referenceReturn(final Object value) {
         value.toString();
         return new Object();
      }

      public void unknownGenerated(final Object value, final boolean invoke) {
         if (invoke) {
            value.toString();
         }
      }

      public void unknownManual(final Object value, final boolean invoke) {
         if (invoke) {
            value.toString();
         }
      }

      public void voidReturn(final Object value) {
         value.toString();
      }
   }

   @NonNullByDefault({}) // Keep ECJ neutral so only the field evidence under test influences the generated signatures.
   public static final class StaticFinalFields {
      public static final Object DEFINITELY_NON_NULL = new Object();

      @NonNull
      public static final String EXPLICITLY_NON_NULL = System.getProperty("eea.generator.explicitly-non-null");

      @Nullable
      public static final Object EXPLICITLY_NULLABLE = new Object();

      public static final Object INITIALIZED_AFTER_REENTRANT_CALL;

      public static final String UNKNOWN = System.getProperty("eea.generator.unknown");

      static {
         StaticFinalFieldObserver.observe();
         INITIALIZED_AFTER_REENTRANT_CALL = new Object();
      }
   }

   public static final class StaticFinalFieldObserver {
      private static void observe() {
         // Recursive class initialization can expose the JVM default before the declaring class reaches normal
         // completion.
         @SuppressWarnings("unused")
         final Object observedDuringInitialization = StaticFinalFields.INITIALIZED_AFTER_REENTRANT_CALL;
      }
   }

   public static final class ReturnAnnotationOnly {
      @NonNull
      public Object create(final Class<?> type) {
         return type;
      }

      @NonNull
      public Object createExtended(final Class<?> type) {
         return type;
      }
   }

   @NonNullByDefault({})
   public static class CompactOwnershipParent {
      @NonNull
      public String inheritedValue() {
         return "";
      }

      @Nullable
      public String overriddenValue() {
         return null;
      }
   }

   @NonNullByDefault({})
   public static final class CompactOwnershipContracts extends CompactOwnershipParent {
      @NonNull
      public String field = "";

      public void accept(@NonNull final Object value) {
         value.hashCode();
      }

      @NonNull
      public Object fromPrimitiveArray(final int[] values) {
         return values;
      }

      @NonNull
      public List<String> genericReturn() {
         return List.of();
      }

      @Override
      @NonNull
      public String inheritedValue() {
         return "";
      }

      @Override
      @NonNull
      public String overriddenValue() {
         return "";
      }

      @NonNull
      public String[] objectArrayReturn() {
         return new String[0];
      }

      @NonNull
      public byte[] primitiveArrayReturn() {
         return new byte[0];
      }
   }

   @NonNullByDefault({}) // Keep ECJ neutral so the null-dependent branch remains a valid fixture.
   public static final class PolyNullReturn {
      public static Object[] copy(final Object[] value) {
         if (value == null)
            return null;
         return new Object[0];
      }

      public static Object identity(final Object value) {
         if (value == null)
            return null;
         return new Object();
      }

      public static Object kept(final Object value) {
         if (value == null)
            return null;
         return new Object();
      }
   }

   @NonNullByDefault({})
   public abstract static class PolyNullUnknownReturn {
      public abstract Object generatedUnknown(Object value);

      public abstract Object kept(Object value);

      @NonNull
      public abstract Object nonNullReturn(Object value);

      public abstract Object parameterOnly(@NonNull Object value);

      public abstract Object unknown(Object value);
   }

   @NonNullByDefault({})
   public abstract static class PolyNullParent {
      public abstract Object identity(Object value);
   }

   @NonNullByDefault({})
   public static final class PolyNullChild extends PolyNullParent {
      @Override
      public Object identity(final Object value) {
         if (value == null)
            return null;
         return new Object();
      }
   }

   @NonNullByDefault({})
   public abstract static class ParentOnlyPolyNull {
      public abstract Object identity(Object value);
   }

   @NonNullByDefault({})
   public static final class ParentOnlyPolyNullChild extends ParentOnlyPolyNull {
      @Override
      public Object identity(final Object value) {
         return new Object();
      }
   }

   @NonNullByDefault({})
   public abstract static class PolyNullBoundaryGrandparent {
      public abstract Object identity(Object value);
   }

   @NonNullByDefault({})
   public static class PolyNullBoundaryParent extends PolyNullBoundaryGrandparent {
      @Override
      public Object identity(final Object value) {
         if (value == null)
            return null;
         return new Object();
      }
   }

   @NonNullByDefault({})
   public abstract static class PolyNullBoundaryChild extends PolyNullBoundaryParent {
      @Override
      public abstract Object identity(Object value);
   }

   @NonNullByDefault({})
   public abstract static class PolyNullBoundaryDescendant extends PolyNullBoundaryChild {
      @Override
      public abstract Object identity(Object value);
   }

   @NonNullByDefault({}) // Keep ECJ neutral so this fixture contains only the JSpecify contract under test.
   public static final class JSpecifyTypeUseAnnotations {
      public static <T> @org.jspecify.annotations.Nullable Optional<T> nullableOptional(
            final @org.jspecify.annotations.Nullable Optional<T> optional) {
         return optional == null ? null : optional;
      }
   }

   @NonNullByDefault({})
   public abstract static class TypeUseQualifierNicknames {
      public @test.nullness.Jsr305TypeUseNicknames.Nullable Object nullableField;

      public abstract @test.nullness.Jsr305TypeUseNicknames.Nullable Optional<String> nullableOptional();

      public abstract void consume(@test.nullness.Jsr305TypeUseNicknames.NonNull Object value);
   }

   @NonNullByDefault({})
   public interface NestedTypeUseAnnotations {
      @org.jspecify.annotations.NonNull
      NestedTypeOuter<String>.@org.jspecify.annotations.Nullable Inner leafNullable();

      @org.jspecify.annotations.Nullable
      NestedTypeOuter<String>.@org.jspecify.annotations.NonNull Inner leafNonNull();

      @org.jspecify.annotations.Nullable
      NestedTypeOuter<String>.Inner outerNullableOnly();

      NestedTypeOuter<String>.Inner unannotatedInner();

      Object unannotatedObject();

      void consume(@org.jspecify.annotations.Nullable NestedTypeOuter<String>.@org.jspecify.annotations.NonNull Inner value);

      void consumeOuterNullableOnly(@org.jspecify.annotations.Nullable NestedTypeOuter<String>.Inner value);
   }

   public interface OptionalFallback {
      Optional<String> findOptional();
   }

   @NonNullByDefault({})
   public interface CreateNameWithoutNullnessContract {
      String createMapping(Object value);
   }

   // Being non-static is essential: javac adds the enclosing instance to the descriptor but omits it from the
   // generic signature.
   public final class InnerWithAnnotatedGenericConstructor {
      @SuppressWarnings("unused")
      public InnerWithAnnotatedGenericConstructor( // CHECKSTYLE:IGNORE RedundantModifier
            final @Nullable List<String> values) {
      }
   }

   @Nonnull(when = When.UNKNOWN)
   @TypeQualifierNickname
   @Retention(RUNTIME)
   @Target(PARAMETER)
   public @interface UnknownNullness {
   }

   @Nonnull(when = When.NEVER)
   @TypeQualifierNickname
   @Retention(RUNTIME)
   @Target(METHOD)
   public @interface NeverNullness {
   }

   @Nonnull
   @TypeQualifierNickname
   @Retention(RUNTIME)
   @Target(PARAMETER)
   public @interface UnconditionalNonNull {
   }

   public static final class Jsr305ParameterAnnotations {

      public boolean consumeDirectUnknown(@Nonnull(when = When.UNKNOWN) final Object value) {
         return value == null;
      }

      public boolean consumeIndirectUnknown(@UnknownNullness final Object value) {
         return value == null;
      }

      public boolean consumeIndirectNonNull(@UnconditionalNonNull final Object value) {
         return value != null;
      }
   }

   @NonNullByDefault({})
   public interface Jsr305ReturnAnnotations {

      @Nonnull(when = When.UNKNOWN)
      Optional<String> findDirectUnknown();

      @Nonnull(when = When.MAYBE)
      Optional<String> findDirectMaybe();

      @NeverNullness
      Optional<String> findIndirectNever();
   }

   private static void assertAnnotatedSignature(final EEAFile eeaFile, final String memberName, final String originalSignature,
         final String expectedAnnotatedSignature) {
      final var member = eeaFile.findMatchingClassMember(memberName, originalSignature);
      assertThat(member).isNotNull();
      assert member != null;
      assertThat(member.annotatedSignature.value).isEqualTo(expectedAnnotatedSignature);
   }

   private static void assertAnnotatedSignatureComment(final EEAFile eeaFile, final String memberName, final String originalSignature,
         final String expectedComment) {
      final var member = eeaFile.findMatchingClassMember(memberName, originalSignature);
      assertThat(member).isNotNull();
      assert member != null;
      assertThat(member.annotatedSignature.comment).isEqualTo(expectedComment);
   }

   private static byte[] createClassWithNullableToString(final String className) {
      // ECJ rejects the source override against its external Object contract, although this bytecode is legal on the
      // JVM.
      final var classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
      classWriter.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

      final var constructor = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
      constructor.visitCode();
      constructor.visitVarInsn(Opcodes.ALOAD, 0);
      constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      constructor.visitInsn(Opcodes.RETURN);
      constructor.visitMaxs(0, 0);
      constructor.visitEnd();

      final var toString = classWriter.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
      toString.visitCode();
      toString.visitInsn(Opcodes.ACONST_NULL);
      toString.visitInsn(Opcodes.ARETURN);
      toString.visitMaxs(0, 0);
      toString.visitEnd();

      classWriter.visitEnd();
      return classWriter.toByteArray();
   }

   private static void writeClass(final Path root, final String className, final byte[] bytecode) throws IOException {
      final Path classFile = root.resolve(className + ".class");
      Files.createDirectories(classFile.getParent());
      Files.write(classFile, bytecode);
   }

   @Test
   void testValidateValidEEAFiles() throws IOException {
      final var rootPath = Path.of("src/test/resources/valid");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      assertThat(EEAGenerator.validateEEAFiles(config)).isEqualTo(2);
   }

   @Test
   void testAnnotationMerge() throws IOException {
      final var eeaFiles = EEAGenerator.computeEEAFiles("com.vegardit.no_npe.eea_generator", classInfo -> classInfo.getName().equals(
         "com.vegardit.no_npe.eea_generator.EEAFileTest$TestEntity"));
      assertThat(eeaFiles).hasSize(1);
      final var computedEeaFile = eeaFiles.values().iterator().next();
      final var existingEeaFile = EEAFile.load(Path.of(
         "src/test/resources/valid/com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity.eea"));

      assertThat(computedEeaFile.renderFileContent(Set.of(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE))).isEqualTo(List.of(
         "class com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity", //
         "", //
         "STATIC_STRING", //
         " Ljava/lang/String;", //
         " L1java/lang/String;", //
         "", //
         "keepTest1", //
         " ()Ljava/lang/String;", //
         " ()L0java/lang/String;" //
      ));

      final var keepTestMethodComputed = computedEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst().get();
      final var keepTestMethodExisting = existingEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst().get();
      assertThat(keepTestMethodComputed.annotatedSignature.value).contains("L0java/lang/String");
      assertThat(keepTestMethodExisting.annotatedSignature.value).contains("Ljava/lang/String");
      assertThat(keepTestMethodExisting.annotatedSignature.comment).contains("@Keep");
      assertThat(keepTestMethodComputed.annotatedSignature.value).isNotEqualTo(keepTestMethodExisting.annotatedSignature.value);
      assertThat(keepTestMethodComputed.annotatedSignature.comment).isNotEqualTo(keepTestMethodExisting.annotatedSignature.comment);

      computedEeaFile.applyAnnotationsAndCommentsFrom(existingEeaFile, true, false);
      final var keepTestMethodComputedUpdated = computedEeaFile.getClassMembers().filter(m -> m.name.value.equals("keepTest1")).findFirst()
         .get();
      assertThat(keepTestMethodComputedUpdated.annotatedSignature.value).contains("Ljava/lang/String");
      assertThat(keepTestMethodComputedUpdated.annotatedSignature.comment).contains("@Keep");

      assertThat(computedEeaFile.renderFileContent(Set.of(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE))).isEqualTo(List.of(
         "class com/vegardit/no_npe/eea_generator/EEAFileTest$TestEntity # a class comment", //
         "", //
         "STATIC_STRING", //
         " Ljava/lang/String;", //
         " L1java/lang/String; # an annotated signature comment", //
         "", //
         "name", //
         " Ljava/lang/String;", //
         " L1java/lang/String;", //
         "", //
         "keepTest1", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing generator from changing it to L0", //
         "keepTest2", //
         " ()Ljava/lang/String;", //
         " ()Ljava/lang/String; # @Keep to test preventing removal on minimization" //
      ));
   }

   @Test
   void testComparableAndReceiverCallEvidenceRemainIndependent() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ComparableType.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      final var compareTo = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("compareTo")).findFirst()
         .orElseThrow();
      final var unrelated = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("unrelated")).findFirst()
         .orElseThrow();

      assertThat(compareTo.annotatedSignature.value).contains("L1java/lang/String;");
      // The Comparable convention still handles compareTo without receiver-call proof; the unrelated method now has
      // its own proof because normal completion requires value.length() to succeed.
      assertThat(unrelated.annotatedSignature.value).isEqualTo("(L1java/lang/String;)I");
   }

   @Test
   void testExplicitParameterAnnotationOverridesHeuristic() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ExplicitlyNullableComparable.class.getName()));
      final var compareTo = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "compareTo")).findFirst().orElseThrow();

      assertThat(compareTo.annotatedSignature.value).isEqualTo("(L0java/lang/String;)I");
   }

   @Test
   void testStaticFinalFieldInferenceRequiresDefiniteInitializerEvidence() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(StaticFinalFields.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "DEFINITELY_NON_NULL", "Ljava/lang/Object;", "L1java/lang/Object;");
      assertAnnotatedSignature(computedEEAFile, "UNKNOWN", "Ljava/lang/String;", "Ljava/lang/String;");
      assertAnnotatedSignature(computedEEAFile, "EXPLICITLY_NON_NULL", "Ljava/lang/String;", "L1java/lang/String;");
      assertAnnotatedSignature(computedEEAFile, "EXPLICITLY_NULLABLE", "Ljava/lang/Object;", "L0java/lang/Object;");
      // Source-level nullness contracts describe the value after successful class initialization, not a recursive early read.
      assertAnnotatedSignature(computedEEAFile, "INITIALIZED_AFTER_REENTRANT_CALL", "Ljava/lang/Object;", "L1java/lang/Object;");
   }

   @Test
   void testJSpecifyTypeUseAnnotationsOverrideOptionalFallback() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(JSpecifyTypeUseAnnotations.class.getName()));
      final var nullableOptional = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "nullableOptional")).findFirst().orElseThrow();

      assertThat(nullableOptional.annotatedSignature.value).isEqualTo(
         "<T:Ljava/lang/Object;>(L0java/util/Optional<TT;>;)L0java/util/Optional<TT;>;");
   }

   @Test
   void testTypeUseQualifierNicknamesAreExpanded() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(TypeUseQualifierNicknames.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "nullableField", "Ljava/lang/Object;", "L0java/lang/Object;");
      assertAnnotatedSignature(computedEEAFile, "nullableOptional", "()Ljava/util/Optional<Ljava/lang/String;>;",
         "()L0java/util/Optional<Ljava/lang/String;>;");
      assertAnnotatedSignature(computedEEAFile, "consume", "(Ljava/lang/Object;)V", "(L1java/lang/Object;)V");
   }

   @Test
   void testNestedTypeUseAnnotationsUseTheLeafSegment() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(NestedTypeUseAnnotations.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();
      final var leafNullable = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("leafNullable")).findFirst()
         .orElseThrow();
      final var leafNonNull = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("leafNonNull")).findFirst()
         .orElseThrow();
      final var outerNullableOnly = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("outerNullableOnly"))
         .findFirst().orElseThrow();
      final var consume = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consume")).findFirst().orElseThrow();
      final var consumeOuterNullableOnly = computedEEAFile.getClassMembers().filter(member -> member.name.value.equals(
         "consumeOuterNullableOnly")).findFirst().orElseThrow();

      assertThat(leafNullable.originalSignature.value).contains(".Inner;");
      assertThat(leafNullable.annotatedSignature.value).isEqualTo(leafNullable.originalSignature.value.replace(")L", ")L0"));
      assertThat(leafNonNull.annotatedSignature.value).isEqualTo(leafNonNull.originalSignature.value.replace(")L", ")L1"));
      assertThat(outerNullableOnly.annotatedSignature.value).isEqualTo(outerNullableOnly.originalSignature.value);
      assertThat(consume.annotatedSignature.value).isEqualTo(consume.originalSignature.value.replace("(L", "(L1"));
      assertThat(consumeOuterNullableOnly.annotatedSignature.value).isEqualTo(consumeOuterNullableOnly.originalSignature.value);
   }

   @Test
   void testEcjAppliesTheRootEeaMarkerToANestedReturnValue(@TempDir final Path tempDir) throws IOException {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(NestedTypeUseAnnotations.class.getName()));
      final var unannotatedInner = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "unannotatedInner")).findFirst().orElseThrow();
      final var unannotatedObject = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "unannotatedObject")).findFirst().orElseThrow();
      assertThat(unannotatedInner.originalSignature.value).contains(".Inner;");

      final Path annotationDir = tempDir.resolve("annotations");
      final var eeaFile = new EEAFile(NestedTypeUseAnnotations.class.getName());
      final var eeaMember = eeaFile.addMember(unannotatedInner.name.value, unannotatedInner.originalSignature.value);
      // ECJ treats the marker after the initial L as the nullness of the complete returned nested-class value. Keep this
      // experiment because reading the raw signature as separate Outer and Inner values suggests the opposite mapping.
      eeaMember.annotatedSignature.value = eeaMember.originalSignature.value.replace(")L", ")L0");
      final var controlMember = eeaFile.addMember(unannotatedObject.name.value, unannotatedObject.originalSignature.value);
      controlMember.annotatedSignature.value = controlMember.originalSignature.value.replace(")L", ")L0");
      eeaFile.save(annotationDir, SaveOption.REPLACE_EXISTING);

      final Path sourceFile = tempDir.resolve("src/com/vegardit/no_npe/eea_generator/NestedTypeConsumer.java");
      Files.createDirectories(sourceFile.getParent());
      Files.writeString(sourceFile, String.join(System.lineSeparator(), //
         "package com.vegardit.no_npe.eea_generator;", //
         "final class NestedTypeConsumer {", //
         "  void consume(EEAGeneratorTest.NestedTypeUseAnnotations source) {", //
         "    source.unannotatedObject().toString();", //
         "  }", //
         "}"));
      final Path compilerOutputDir = Files.createDirectories(tempDir.resolve("classes"));
      final var compilerOutput = new StringWriter();
      final String testClasspath = Objects.requireNonNullElseGet(System.getProperty("surefire.test.class.path"), () -> Objects
         .requireNonNull(System.getProperty("java.class.path")));
      // This matches the production compiler setup: CLASSPATH makes ECJ scan each classpath entry for a matching EEA file.
      final String compilerClasspath = testClasspath + File.pathSeparator + annotationDir;
      Path compilerProperties = Path.of(System.getProperty("basedir", "."), ".settings", "org.eclipse.jdt.core.prefs");
      if (!Files.isRegularFile(compilerProperties)) {
         compilerProperties = Path.of("eea-generator", ".settings", "org.eclipse.jdt.core.prefs");
      }
      final String[] compilerArguments = { //
         "-properties", compilerProperties.toAbsolutePath().toString(), "-source", "11", "-target", "11", "-proc:none", //
         "-classpath", compilerClasspath, "-annotationpath", "CLASSPATH", //
         "-d", compilerOutputDir.toString(), sourceFile.toString() //
      };

      // Prove that the annotation path is active before relying on the nested-type diagnostic.
      boolean compiled = BatchCompiler.compile(compilerArguments, new PrintWriter(compilerOutput), new PrintWriter(compilerOutput), null);
      assertThat(compiled).isFalse();
      assertThat(compilerOutput.toString()).contains("Potential null pointer access");

      Files.writeString(sourceFile, String.join(System.lineSeparator(), //
         "package com.vegardit.no_npe.eea_generator;", //
         "final class NestedTypeConsumer {", //
         "  void consume(EEAGeneratorTest.NestedTypeUseAnnotations source) {", //
         "    source.unannotatedInner().toString();", //
         "  }", //
         "}"));
      compilerOutput.getBuffer().setLength(0);
      compiled = BatchCompiler.compile(compilerArguments, new PrintWriter(compilerOutput), new PrintWriter(compilerOutput), null);

      assertThat(compiled).isFalse();
      assertThat(compilerOutput.toString()).contains("Potential null pointer access");
   }

   @Test
   void testOptionalFallbackAppliesWithoutStrongerEvidence() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(OptionalFallback.class.getName()));
      final var findOptional = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "findOptional")).findFirst().orElseThrow();

      assertThat(findOptional.annotatedSignature.value).isEqualTo("()L1java/util/Optional<Ljava/lang/String;>;");
   }

   @Test
   void testListenerHeuristicAnnotatesSoleMethodTypeParameterUse() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(GenericListenerRegistration.class.getName()));
      final var addListener = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "addGenericListener")).findFirst().orElseThrow();

      // The heuristic constrains this parameter value without imposing a stronger constraint on substitutions for T.
      assertThat(addListener.annotatedSignature.value).isEqualTo("<T::Ljava/util/EventListener;>(T1T;)V");
   }

   @Test
   void testListenerHeuristicDoesNotAnnotateUnrelatedMethodTypeParameter() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(MultiTypeVariableGenericListenerRegistration.class.getName()));
      final var addListener = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "addGenericListener")).findFirst().orElseThrow();

      assertThat(addListener.annotatedSignature.value).isEqualTo("<X:Ljava/lang/Object;T::Ljava/util/EventListener;>(T1T;)V");
   }

   @Test
   void testExplicitParameterAnnotationsAreCombinedWithOtherAnnotations() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ExplicitParameterAnnotations.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("combine")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo(
            "(L0java/lang/String;L1java/lang/String;L1java/util/List<Ljava/lang/String;>;I)L1java/lang/String;");
      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeArray")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("([0Ljava/lang/String;)Z");
      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeTypeVariable")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("<T:Ljava/lang/Object;>(T0T;)Z");
   }

   @Test
   void testReceiverCallsInferOnlyNonNullParameterContracts() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ReceiverParameterContracts.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "voidReturn", "(Ljava/lang/Object;)V", "(L1java/lang/Object;)V");
      assertAnnotatedSignature(computedEEAFile, "primitiveReturn", "(Ljava/lang/Object;)I", "(L1java/lang/Object;)I");
      assertAnnotatedSignature(computedEEAFile, "referenceReturn", "(Ljava/lang/Object;)Ljava/lang/Object;",
         "(L1java/lang/Object;)L1java/lang/Object;");
      assertAnnotatedSignature(computedEEAFile, "generic", "<T:Ljava/lang/Object;>(TT;)V", "<T:Ljava/lang/Object;>(T1T;)V");
      assertAnnotatedSignature(computedEEAFile, "onlyFirst", "(Ljava/lang/Object;Ljava/lang/Object;)V",
         "(L1java/lang/Object;Ljava/lang/Object;)V");
      assertAnnotatedSignature(computedEEAFile, "caughtAndRethrown", "(Ljava/lang/Object;)V", "(Ljava/lang/Object;)V");
      assertAnnotatedSignature(computedEEAFile, "unknownGenerated", "(Ljava/lang/Object;Z)V", "(Ljava/lang/Object;Z)V");
      // Explicit declaration evidence stays authoritative even when the method body dereferences the parameter.
      assertAnnotatedSignature(computedEEAFile, "explicitNullable", "(Ljava/lang/Object;)V", "(L0java/lang/Object;)V");
   }

   @Test
   void testParameterAnnotationIsAlignedWithGenericInnerConstructorSignature() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(InnerWithAnnotatedGenericConstructor.class.getName()));
      final var constructor = computedEEAFiles.values().iterator().next().getClassMembers().filter(member -> member.name.value.equals(
         "<init>")).findFirst().orElseThrow();

      assertThat(constructor.annotatedSignature.value).isEqualTo("(L0java/util/List<Ljava/lang/String;>;)V");
   }

   @Test
   void testNonnullWhenIsRespectedForParameterAnnotations() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(Jsr305ParameterAnnotations.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeDirectUnknown")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("(Ljava/lang/Object;)Z");
      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeIndirectUnknown")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("(Ljava/lang/Object;)Z");
      assertThat(computedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeIndirectNonNull")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("(L1java/lang/Object;)Z");
   }

   @Test
   void testNonnullMaybeAndNeverAreNullableReturnAnnotations() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(Jsr305ReturnAnnotations.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "findDirectMaybe", "()Ljava/util/Optional<Ljava/lang/String;>;",
         "()L0java/util/Optional<Ljava/lang/String;>;");
      assertAnnotatedSignature(computedEEAFile, "findIndirectNever", "()Ljava/util/Optional<Ljava/lang/String;>;",
         "()L0java/util/Optional<Ljava/lang/String;>;");
   }

   @Test
   void testNonnullUnknownDoesNotDisableOptionalFallback() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(Jsr305ReturnAnnotations.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "findDirectUnknown", "()Ljava/util/Optional<Ljava/lang/String;>;",
         "()L1java/util/Optional<Ljava/lang/String;>;");
   }

   @Test
   void testCreateMethodNameDoesNotImplyNonNullReturn() {
      final var computedEEAFiles = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(CreateNameWithoutNullnessContract.class.getName()));
      final var computedEEAFile = computedEEAFiles.values().iterator().next();

      assertAnnotatedSignature(computedEEAFile, "createMapping", "(Ljava/lang/Object;)Ljava/lang/String;",
         "(Ljava/lang/Object;)Ljava/lang/String;");
   }

   @Test
   void testLocalReturnEvidenceOverridesObjectTemplate(@TempDir final Path tempDir) throws IOException {
      final String className = "test/NullableToString";
      final String methodSignature = "()Ljava/lang/String;";
      writeClass(tempDir, className, createClassWithNullableToString(className));

      try (var scanResult = new ClassGraph() //
         .enableAllInfo() //
         .overrideClasspath(tempDir.toString()) //
         .acceptClasses(className.replace('/', '.')) //
         .scan()) {
         final var classInfo = scanResult.getClassInfo(className.replace('/', '.'));
         assert classInfo != null;
         final var methodInfo = classInfo.getMethodInfo("toString").get(0);
         final var member = new EEAFile.ClassMember("toString", methodSignature);
         final var analyzer = new BytecodeAnalyzer(classInfo, new BytecodeAnalyzer.StaticFieldResolver(scanResult));

         // Object's template is only a fallback; bytecode from this concrete override proves the actual return
         // nullable.
         assertThat(EEAGenerator.computeAnnotatedSignature(member, classInfo, methodInfo, analyzer).value).isEqualTo(
            "()L0java/lang/String;");
      }
   }

   @Test
   void testGenerateReplacesUnmarkedSignaturesAndPreservesKeep(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");

      final var inputEEAFile = new EEAFile(ExplicitParameterAnnotations.class.getName());
      final var combine = inputEEAFile.addMember("combine",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List<Ljava/lang/String;>;I)Ljava/lang/String;");
      // Full generation applies current evidence at every known position, including positions that were previously
      // added by hand without @Keep.
      combine.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/String;Ljava/util/List<L1java/lang/String;>;I)L1java/lang/String;";

      final var kept = inputEEAFile.addMember("consumeArray", "([Ljava/lang/String;)Z");
      kept.annotatedSignature.comment = "# @Keep an intentional external contract";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterAnnotations.class.getName());

      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, ExplicitParameterAnnotations.class.getName());
      final var generatedCombine = generatedEEAFile.getClassMembers().filter(member -> member.name.value.equals("combine")).findFirst()
         .orElseThrow();
      assertThat(generatedCombine.annotatedSignature.value).isEqualTo(
         "(L0java/lang/String;L1java/lang/String;L1java/util/List<L1java/lang/String;>;I)L1java/lang/String;");
      // The nested generic marker has no current evidence and therefore remains manual; the marker lists only the
      // four positions generated in this run.
      assertThat(generatedCombine.annotatedSignature.comment).isEqualTo("# @Generated(2,20,38,76)");
      assertThat(generatedEEAFile.getClassMembers().filter(member -> member.name.value.equals("consumeArray")).findFirst()
         .orElseThrow().annotatedSignature.value).isEqualTo("([Ljava/lang/String;)Z");
   }

   @Test
   void testGenerateReconcilesReceiverParameterEvidenceByPosition(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String objectParameterSignature = "(Ljava/lang/Object;)V";
      final String conditionalSignature = "(Ljava/lang/Object;Z)V";

      final var inputEEAFile = new EEAFile(ReceiverParameterContracts.class.getName());
      final var overriddenManual = inputEEAFile.addMember("voidReturn", objectParameterSignature);
      overriddenManual.annotatedSignature.value = "(L0java/lang/Object;)V";
      final var keptConflict = inputEEAFile.addMember("keptInferred", objectParameterSignature);
      keptConflict.annotatedSignature.value = "(L0java/lang/Object;)V";
      keptConflict.annotatedSignature.comment = "# @Keep deliberate nullable contract";
      final var withdrawnGenerated = inputEEAFile.addMember("unknownGenerated", conditionalSignature);
      withdrawnGenerated.annotatedSignature.value = "(L1java/lang/Object;Z)V";
      withdrawnGenerated.annotatedSignature.comment = "# @Generated(2) stale receiver proof";
      final var preservedManual = inputEEAFile.addMember("unknownManual", conditionalSignature);
      preservedManual.annotatedSignature.value = "(L1java/lang/Object;Z)V";
      preservedManual.annotatedSignature.comment = "# manual contract";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ReceiverParameterContracts.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, ReceiverParameterContracts.class.getName());
      final var generatedOverride = generatedEEAFile.findMatchingClassMember(overriddenManual);
      final var generatedKeptConflict = generatedEEAFile.findMatchingClassMember(keptConflict);
      final var generatedWithdrawal = generatedEEAFile.findMatchingClassMember(withdrawnGenerated);
      final var generatedManual = generatedEEAFile.findMatchingClassMember(preservedManual);
      assertThat(generatedOverride).isNotNull();
      assertThat(generatedKeptConflict).isNotNull();
      assertThat(generatedWithdrawal).isNotNull();
      assertThat(generatedManual).isNotNull();
      assert generatedOverride != null;
      assert generatedKeptConflict != null;
      assert generatedWithdrawal != null;
      assert generatedManual != null;

      // Current proof replaces an unprotected disagreement and owns the signature's sole nullness position.
      assertThat(generatedOverride.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)V");
      assertThat(generatedOverride.annotatedSignature.comment).isEqualTo("# @Generated");
      assertThat(generatedKeptConflict.annotatedSignature.value).isEqualTo("(L0java/lang/Object;)V");
      assertThat(generatedKeptConflict.annotatedSignature.comment).isEqualTo(keptConflict.annotatedSignature.comment);
      // Silence withdraws only prior generated evidence. Explanatory text and an unowned manual marker survive.
      assertThat(generatedWithdrawal.annotatedSignature.value).isEqualTo(conditionalSignature);
      assertThat(generatedWithdrawal.annotatedSignature.comment).isEqualTo("# stale receiver proof");
      assertThat(generatedManual.annotatedSignature.value).isEqualTo(preservedManual.annotatedSignature.value);
      assertThat(generatedManual.annotatedSignature.comment).isEqualTo(preservedManual.annotatedSignature.comment);
   }

   @Test
   void testGenerateAdditiveClaimsMatchesAndPreservesConflictingEvidence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final var freshlyComputedEEAFile = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo
         .getName().equals(ExplicitParameterAnnotations.class.getName())).values().iterator().next();

      final var inputEEAFile = new EEAFile(ExplicitParameterAnnotations.class.getName());
      final var freshlyComputedCombine = freshlyComputedEEAFile.getClassMembers().filter(member -> member.name.value.equals("combine"))
         .findFirst().orElseThrow();
      final var exactLegacyMember = inputEEAFile.addMember(freshlyComputedCombine.name.value,
         freshlyComputedCombine.originalSignature.value);
      exactLegacyMember.annotatedSignature = freshlyComputedCombine.annotatedSignature.clone();

      final var mismatchingLegacyMember = inputEEAFile.addMember("consumeArray", "([Ljava/lang/String;)Z");
      mismatchingLegacyMember.annotatedSignature.value = "([1Ljava/lang/String;)Z";
      // Explanatory text is independent from ownership and must survive both marker removal and later replacement.
      mismatchingLegacyMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED + " preserved conflict";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterAnnotations.class.getName());
      config.generationMode = EEAGenerator.GenerationMode.ADDITIVE;

      EEAGenerator.generateEEAFiles(config);

      var generatedEEAFile = EEAFile.load(outputDir, ExplicitParameterAnnotations.class.getName());
      var generatedExactMember = generatedEEAFile.findMatchingClassMember(exactLegacyMember);
      var preservedMismatch = generatedEEAFile.findMatchingClassMember(mismatchingLegacyMember);
      assertThat(generatedExactMember).isNotNull();
      assertThat(preservedMismatch).isNotNull();
      assert generatedExactMember != null;
      assert preservedMismatch != null;
      assertThat(generatedExactMember.annotatedSignature.comment).isEqualTo("# @Generated(2,20,38,76)");
      // Additive keeps both the conflicting value and its existing generated provenance. A later full run can then
      // replace that still-generator-owned position.
      assertThat(preservedMismatch.annotatedSignature.value).isEqualTo(mismatchingLegacyMember.annotatedSignature.value);
      assertThat(preservedMismatch.annotatedSignature.comment).isEqualTo("# @Generated(2) preserved conflict");

      config.inputDirs.clear();
      config.inputDirs.add(outputDir);
      config.generationMode = EEAGenerator.GenerationMode.FULL;
      EEAGenerator.generateEEAFiles(config);

      generatedEEAFile = EEAFile.load(outputDir, ExplicitParameterAnnotations.class.getName());
      generatedExactMember = generatedEEAFile.findMatchingClassMember(exactLegacyMember);
      preservedMismatch = generatedEEAFile.findMatchingClassMember(mismatchingLegacyMember);
      assertThat(generatedExactMember).isNotNull();
      assertThat(preservedMismatch).isNotNull();
      assert generatedExactMember != null;
      assert preservedMismatch != null;
      assertThat(generatedExactMember.annotatedSignature.comment).isEqualTo("# @Generated(2,20,38,76)");
      // Full generation exposes and applies the replacement that additive generation skipped.
      assertThat(preservedMismatch.annotatedSignature.value).isEqualTo("([0Ljava/lang/String;)Z");
      assertThat(preservedMismatch.annotatedSignature.comment).isEqualTo("# @Generated(2) preserved conflict");

      // Repeating full generation after the replacement must converge.
      assertThat(EEAGenerator.generateEEAFiles(config)).isZero();
   }

   @Test
   void testGeneratedSignatureIsReplacedByCurrentInference(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final var inputEEAFile = new EEAFile(ExplicitParameterAnnotations.class.getName());
      final var generatedMember = inputEEAFile.addMember("consumeArray", "([Ljava/lang/String;)Z");
      generatedMember.annotatedSignature.value = "([1Ljava/lang/String;)Z";
      generatedMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED;
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterAnnotations.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, ExplicitParameterAnnotations.class.getName());
      final var refreshedMember = generatedEEAFile.findMatchingClassMember(generatedMember);
      assertThat(refreshedMember).isNotNull();
      assert refreshedMember != null;
      assertThat(refreshedMember.annotatedSignature.value).isEqualTo("([0Ljava/lang/String;)Z");
      assertThat(refreshedMember.annotatedSignature.comment).isEqualTo("# @Generated(2)");
   }

   @Test
   void testGenerateRejectsEmptyGeneratedOwnershipMarker(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final var inputEEAFile = new EEAFile(ExplicitParameterAnnotations.class.getName());
      final var generatedMember = inputEEAFile.addMember("consumeArray", "([Ljava/lang/String;)Z");
      generatedMember.annotatedSignature.value = "([1Ljava/lang/String;)Z";
      generatedMember.annotatedSignature.comment = "# @Generated()";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterAnnotations.class.getName());

      assertThatThrownBy(() -> EEAGenerator.generateEEAFiles(config)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("unsupported ownership token []");
   }

   @Test
   void testValidateRejectsEmptyGeneratedOwnershipMarkerEvenWhenKept(@TempDir final Path outputDir) throws IOException {
      final var invalidEEAFile = new EEAFile(ExplicitParameterAnnotations.class.getName());
      final var invalidMember = invalidEEAFile.addMember("consumeArray", "([Ljava/lang/String;)Z");
      invalidMember.annotatedSignature.value = "([1Ljava/lang/String;)Z";
      invalidMember.annotatedSignature.comment = "# @Generated() @Keep";
      invalidEEAFile.save(outputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterAnnotations.class.getName());

      assertThatThrownBy(() -> EEAGenerator.validateEEAFiles(config)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessageContaining("unsupported ownership token []");
   }

   @Test
   void testGeneratedOwnershipUsesSingletonShorthand(@TempDir final Path outputDir) throws IOException {
      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.classFilter = classInfo -> classInfo.getName().equals(CompactOwnershipParent.class.getName()) //
            || classInfo.getName().equals(CompactOwnershipContracts.class.getName());

      EEAGenerator.generateEEAFiles(config);

      final var parentEEAFile = EEAFile.load(outputDir, CompactOwnershipParent.class.getName());
      final var childEEAFile = EEAFile.load(outputDir, CompactOwnershipContracts.class.getName());
      assertAnnotatedSignatureComment(parentEEAFile, "inheritedValue", "()Ljava/lang/String;", "# @Generated");
      assertAnnotatedSignatureComment(childEEAFile, "field", "Ljava/lang/String;", "# @Generated");
      assertAnnotatedSignatureComment(childEEAFile, "accept", "(Ljava/lang/Object;)V", "# @Generated");
      assertAnnotatedSignatureComment(childEEAFile, "primitiveArrayReturn", "()[B", "# @Generated");
      assertAnnotatedSignatureComment(childEEAFile, "inheritedValue", "()Ljava/lang/String;", "# @Inherited(" + CompactOwnershipParent.class
         .getName() + ")");
      assertAnnotatedSignatureComment(childEEAFile, "overriddenValue", "()Ljava/lang/String;", "# @Overrides("
            + CompactOwnershipParent.class.getName() + ")");

      // These members each contain another legal nullness site, so a future manual marker still needs distinct
      // ownership even though current analysis generated only one position.
      assertAnnotatedSignatureComment(childEEAFile, "fromPrimitiveArray", "([I)Ljava/lang/Object;", "# @Generated(5)");
      assertAnnotatedSignatureComment(childEEAFile, "objectArrayReturn", "()[Ljava/lang/String;", "# @Generated(3)");
      assertAnnotatedSignatureComment(childEEAFile, "genericReturn", "()Ljava/util/List<Ljava/lang/String;>;", "# @Generated(3)");

      // Re-reading compact ownership must not expand it or otherwise cause output churn.
      config.inputDirs.add(outputDir);
      assertThat(EEAGenerator.generateEEAFiles(config)).isZero();
   }

   @Test
   void testGeneratePreservesManualEvidenceAndWithdrawsSilentGeneratedEvidence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";
      final var inputEEAFile = new EEAFile(ExplicitParameterParent.class.getName());

      final var unmarkedMember = inputEEAFile.addMember("cleared", methodSignature);
      unmarkedMember.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/Object;)V";
      unmarkedMember.annotatedSignature.comment = "# existing unmarked contract";
      final var generatedMember = inputEEAFile.addMember("inherit", methodSignature);
      generatedMember.annotatedSignature.value = "(Ljava/lang/String;L0java/lang/Object;)V";
      generatedMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED;
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, ExplicitParameterParent.class.getName());
      final var preservedUnmarkedMember = generatedEEAFile.findMatchingClassMember(unmarkedMember);
      final var preservedGeneratedMember = generatedEEAFile.findMatchingClassMember(generatedMember);
      assertThat(preservedUnmarkedMember).isNotNull();
      assertThat(preservedGeneratedMember).isNotNull();
      assert preservedUnmarkedMember != null;
      assert preservedGeneratedMember != null;
      assertThat(preservedUnmarkedMember.annotatedSignature.value).isEqualTo(unmarkedMember.annotatedSignature.value);
      assertThat(preservedUnmarkedMember.annotatedSignature.comment).isEqualTo(unmarkedMember.annotatedSignature.comment);
      // A bare legacy @Generated marker owns every stored position, so full generation withdraws its second
      // parameter when the current analysis becomes silent there.
      assertThat(preservedGeneratedMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(preservedGeneratedMember.annotatedSignature.comment).isEmpty();
   }

   @Test
   void testFullGenerationWithdrawsOnlySilentGeneratedPositions(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path additiveOutputDir = tempDir.resolve("additive-output");
      final Path fullOutputDir = tempDir.resolve("full-output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";
      final var inputEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var mixedMember = inputEEAFile.addMember("cleared", methodSignature);
      mixedMember.annotatedSignature.value = "(L1java/lang/String;L0java/lang/Object;)V";
      // Gap 20 belongs to the second parameter. The first parameter is deliberately manual and must survive silence.
      mixedMember.annotatedSignature.comment = "# @Generated(20) mixed contract";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var additiveConfig = new EEAGenerator.Config(additiveOutputDir, EEAGeneratorTest.class.getPackageName());
      additiveConfig.inputDirs.add(inputDir);
      additiveConfig.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName());
      additiveConfig.generationMode = EEAGenerator.GenerationMode.ADDITIVE;
      EEAGenerator.generateEEAFiles(additiveConfig);

      final var additiveMember = EEAFile.load(additiveOutputDir, ExplicitParameterParent.class.getName()).findMatchingClassMember(
         mixedMember);
      assertThat(additiveMember).isNotNull();
      assert additiveMember != null;
      assertThat(additiveMember.annotatedSignature.value).isEqualTo(mixedMember.annotatedSignature.value);
      assertThat(additiveMember.annotatedSignature.comment).isEqualTo(mixedMember.annotatedSignature.comment);

      final var fullConfig = new EEAGenerator.Config(fullOutputDir, EEAGeneratorTest.class.getPackageName());
      fullConfig.inputDirs.add(additiveOutputDir);
      fullConfig.classFilter = additiveConfig.classFilter;
      EEAGenerator.generateEEAFiles(fullConfig);

      final var fullMember = EEAFile.load(fullOutputDir, ExplicitParameterParent.class.getName()).findMatchingClassMember(mixedMember);
      assertThat(fullMember).isNotNull();
      assert fullMember != null;
      assertThat(fullMember.annotatedSignature.value).isEqualTo("(L1java/lang/String;Ljava/lang/Object;)V");
      assertThat(fullMember.annotatedSignature.comment).isEqualTo("# mixed contract");
   }

   @Test
   void testGenerateAdditiveMergesCompatibleAnnotationsWithoutClaimingStoredEvidence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Class<*>;)Ljava/lang/Object;";

      final var computedEEAFile = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(ReturnAnnotationOnly.class.getName())).values().iterator().next();
      final var computedMember = computedEEAFile.findMatchingClassMember("create", methodSignature);
      assertThat(computedMember).isNotNull();
      assert computedMember != null;
      assertThat(computedMember.annotatedSignature.value).isEqualTo("(Ljava/lang/Class<*>;)L1java/lang/Object;");

      final var inputEEAFile = new EEAFile(ReturnAnnotationOnly.class.getName());
      final var extendedMember = inputEEAFile.addMember("create", methodSignature);
      // The parameter marker is independent evidence; the inferred return marker already agrees with the file.
      extendedMember.annotatedSignature.value = "(L1java/lang/Class<*>;)L1java/lang/Object;";
      // Only the return at raw-signature gap 23 is generated. The parameter remains manual even though both values
      // currently agree with generator output.
      extendedMember.annotatedSignature.comment = "# @Generated(23) existing extended contract";
      final var mergedMember = inputEEAFile.addMember("createExtended", methodSignature);
      // Current inference adds the return marker while the stored contract contributes only the parameter marker.
      mergedMember.annotatedSignature.value = "(L1java/lang/Class<*>;)Ljava/lang/Object;";
      mergedMember.annotatedSignature.comment = "# existing partial contract";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ReturnAnnotationOnly.class.getName());
      config.generationMode = EEAGenerator.GenerationMode.ADDITIVE;
      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, ReturnAnnotationOnly.class.getName());
      final var preservedMember = generatedEEAFile.findMatchingClassMember(extendedMember);
      final var positionallyMergedMember = generatedEEAFile.findMatchingClassMember(mergedMember);
      assertThat(preservedMember).isNotNull();
      assertThat(positionallyMergedMember).isNotNull();
      assert preservedMember != null;
      assert positionallyMergedMember != null;
      assertThat(preservedMember.annotatedSignature.value).isEqualTo(extendedMember.annotatedSignature.value);
      assertThat(preservedMember.annotatedSignature.comment).isEqualTo("# @Generated(23) existing extended contract");
      assertThat(positionallyMergedMember.annotatedSignature.value).isEqualTo("(L1java/lang/Class<*>;)L1java/lang/Object;");
      assertThat(positionallyMergedMember.annotatedSignature.comment).isEqualTo("# @Generated(23) existing partial contract");

      config.inputDirs.clear();
      config.inputDirs.add(outputDir);
      assertThat(EEAGenerator.generateEEAFiles(config)).isZero();
   }

   @Test
   void testGenerateClearsPolyNullReturnAndPreservesIndependentAnnotations(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";
      final String arrayMethodSignature = "([Ljava/lang/Object;)[Ljava/lang/Object;";

      final var computedEEAFile = EEAGenerator.computeEEAFiles(EEAGeneratorTest.class.getPackageName(), classInfo -> classInfo.getName()
         .equals(PolyNullReturn.class.getName())).values().iterator().next();
      final var computedMember = computedEEAFile.findMatchingClassMember("identity", methodSignature);
      assertThat(computedMember).isNotNull();
      assert computedMember != null;
      assertThat(computedMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(computedMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull)");

      final var inputEEAFile = new EEAFile(PolyNullReturn.class.getName());
      final var extendedMember = inputEEAFile.addMember("identity", methodSignature);
      extendedMember.annotatedSignature.value = "(L1java/lang/Object;)L0java/lang/Object;";
      extendedMember.annotatedSignature.comment = "# existing extended contract";
      final var generatedMember = inputEEAFile.addMember("copy", arrayMethodSignature);
      generatedMember.annotatedSignature.value = "([Ljava/lang/Object;)[0Ljava/lang/Object;";
      generatedMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED;
      final var keptMember = inputEEAFile.addMember("kept", methodSignature);
      keptMember.annotatedSignature.value = "(Ljava/lang/Object;)L0java/lang/Object;";
      keptMember.annotatedSignature.comment = "# " + EEAFile.MARKER_KEEP;
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullReturn.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedEEAFile = EEAFile.load(outputDir, PolyNullReturn.class.getName());
      final var preservedMember = generatedEEAFile.findMatchingClassMember(extendedMember);
      final var refreshedGeneratedMember = generatedEEAFile.findMatchingClassMember(generatedMember);
      final var preservedKeptMember = generatedEEAFile.findMatchingClassMember(keptMember);
      assertThat(preservedMember).isNotNull();
      assertThat(refreshedGeneratedMember).isNotNull();
      assertThat(preservedKeptMember).isNotNull();
      assert preservedMember != null;
      assert refreshedGeneratedMember != null;
      assert preservedKeptMember != null;
      // The dependent return must stay unqualified. Its ownership is recorded separately from the surviving manual
      // parameter.
      assertThat(preservedMember.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)Ljava/lang/Object;");
      assertThat(preservedMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull) existing extended contract");
      assertThat(refreshedGeneratedMember.annotatedSignature.value).isEqualTo(arrayMethodSignature);
      assertThat(refreshedGeneratedMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull)");
      assertThat(preservedKeptMember.annotatedSignature.value).isEqualTo(keptMember.annotatedSignature.value);
      assertThat(preservedKeptMember.annotatedSignature.comment).isEqualTo(keptMember.annotatedSignature.comment);
   }

   @Test
   void testGenerateAdditivePreservesPolyNullConflictsAndFullGenerationReplacesThem(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path additiveOutputDir = tempDir.resolve("additive-output");
      final Path generationOutputDir = tempDir.resolve("generation-output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var polyNullEEAFile = new EEAFile(PolyNullReturn.class.getName());
      final var exactPolyNullMember = polyNullEEAFile.addMember("identity", methodSignature);
      exactPolyNullMember.annotatedSignature.comment = "# manually confirmed " + EEAFile.MARKER_POLY_NULL;
      polyNullEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var unknownReturnEEAFile = new EEAFile(PolyNullUnknownReturn.class.getName());
      final var unknownMember = unknownReturnEEAFile.addMember("unknown", methodSignature);
      unknownMember.annotatedSignature.comment = "# manual " + EEAFile.MARKER_POLY_NULL;
      final var parameterOnlyMember = unknownReturnEEAFile.addMember("parameterOnly", methodSignature);
      parameterOnlyMember.annotatedSignature.comment = "# manual " + EEAFile.MARKER_POLY_NULL;
      final var generatedUnknownMember = unknownReturnEEAFile.addMember("generatedUnknown", methodSignature);
      // Keep the formerly emitted paired form as migration coverage; generation must render it canonically.
      generatedUnknownMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED + "(PolyNull) " + EEAFile.MARKER_POLY_NULL;
      final var keptMember = unknownReturnEEAFile.addMember("kept", methodSignature);
      keptMember.annotatedSignature.comment = "# " + EEAFile.MARKER_KEEP + " " + EEAFile.MARKER_POLY_NULL;
      final var conflictingReturnMember = unknownReturnEEAFile.addMember("nonNullReturn", methodSignature);
      conflictingReturnMember.annotatedSignature.comment = "# manual " + EEAFile.MARKER_POLY_NULL;
      unknownReturnEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var additiveConfig = new EEAGenerator.Config(additiveOutputDir, EEAGeneratorTest.class.getPackageName());
      additiveConfig.inputDirs.add(inputDir);
      additiveConfig.classFilter = classInfo -> classInfo.getName().equals(PolyNullReturn.class.getName()) //
            || classInfo.getName().equals(PolyNullUnknownReturn.class.getName());
      additiveConfig.generationMode = EEAGenerator.GenerationMode.ADDITIVE;
      EEAGenerator.generateEEAFiles(additiveConfig);

      final var additiveExactMember = EEAFile.load(additiveOutputDir, PolyNullReturn.class.getName()).findMatchingClassMember(
         exactPolyNullMember);
      final var additiveUnknownEEAFile = EEAFile.load(additiveOutputDir, PolyNullUnknownReturn.class.getName());
      final var additiveUnknownMember = additiveUnknownEEAFile.findMatchingClassMember(unknownMember);
      final var additiveParameterOnlyMember = additiveUnknownEEAFile.findMatchingClassMember(parameterOnlyMember);
      final var additiveGeneratedUnknownMember = additiveUnknownEEAFile.findMatchingClassMember(generatedUnknownMember);
      final var additiveKeptMember = additiveUnknownEEAFile.findMatchingClassMember(keptMember);
      final var additiveConflictingReturnMember = additiveUnknownEEAFile.findMatchingClassMember(conflictingReturnMember);
      assertThat(additiveExactMember).isNotNull();
      assertThat(additiveUnknownMember).isNotNull();
      assertThat(additiveParameterOnlyMember).isNotNull();
      assertThat(additiveGeneratedUnknownMember).isNotNull();
      assertThat(additiveKeptMember).isNotNull();
      assertThat(additiveConflictingReturnMember).isNotNull();
      assert additiveExactMember != null;
      assert additiveUnknownMember != null;
      assert additiveParameterOnlyMember != null;
      assert additiveGeneratedUnknownMember != null;
      assert additiveKeptMember != null;
      assert additiveConflictingReturnMember != null;
      assertThat(additiveExactMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull) manually confirmed");
      // Generator silence preserves an unowned dependency, while independent new parameter evidence is additive.
      assertThat(additiveUnknownMember.annotatedSignature.comment).isEqualTo(unknownMember.annotatedSignature.comment);
      assertThat(additiveParameterOnlyMember.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)Ljava/lang/Object;");
      assertThat(additiveParameterOnlyMember.annotatedSignature.comment).isEqualTo("# @Generated(2) manual " + EEAFile.MARKER_POLY_NULL);
      // Additive silence retains prior generated evidence and migrates its legacy complete marker to exact
      // provenance so a later full run can withdraw it.
      assertThat(additiveGeneratedUnknownMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(additiveGeneratedUnknownMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull)");
      assertThat(additiveKeptMember.annotatedSignature.comment).isEqualTo(keptMember.annotatedSignature.comment);
      // Replacing @PolyNull with an unconditional return marker is destructive and therefore deferred to full
      // generation.
      assertThat(additiveConflictingReturnMember.annotatedSignature.comment).isEqualTo(conflictingReturnMember.annotatedSignature.comment);

      final var generationConfig = new EEAGenerator.Config(generationOutputDir, EEAGeneratorTest.class.getPackageName());
      generationConfig.inputDirs.add(additiveOutputDir);
      generationConfig.classFilter = additiveConfig.classFilter;
      EEAGenerator.generateEEAFiles(generationConfig);

      final var generatedUnknownEEAFile = EEAFile.load(generationOutputDir, PolyNullUnknownReturn.class.getName());
      final var preservedUnknownMember = generatedUnknownEEAFile.findMatchingClassMember(unknownMember);
      final var replacedParameterOnlyMember = generatedUnknownEEAFile.findMatchingClassMember(parameterOnlyMember);
      final var withdrawnGeneratedUnknownMember = generatedUnknownEEAFile.findMatchingClassMember(generatedUnknownMember);
      final var preservedKeptMember = generatedUnknownEEAFile.findMatchingClassMember(keptMember);
      final var replacedConflictingReturnMember = generatedUnknownEEAFile.findMatchingClassMember(conflictingReturnMember);
      assertThat(preservedUnknownMember).isNotNull();
      assertThat(replacedParameterOnlyMember).isNotNull();
      assertThat(withdrawnGeneratedUnknownMember).isNotNull();
      assertThat(preservedKeptMember).isNotNull();
      assertThat(replacedConflictingReturnMember).isNotNull();
      assert preservedUnknownMember != null;
      assert replacedParameterOnlyMember != null;
      assert withdrawnGeneratedUnknownMember != null;
      assert preservedKeptMember != null;
      assert replacedConflictingReturnMember != null;
      assertThat(preservedUnknownMember.annotatedSignature.comment).isEqualTo(unknownMember.annotatedSignature.comment);
      // The stored manual @PolyNull and generated parameter keep separate provenance.
      assertThat(replacedParameterOnlyMember.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)Ljava/lang/Object;");
      assertThat(replacedParameterOnlyMember.annotatedSignature.comment).isEqualTo("# @Generated(2) manual " + EEAFile.MARKER_POLY_NULL);
      assertThat(withdrawnGeneratedUnknownMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(withdrawnGeneratedUnknownMember.annotatedSignature.comment).isEmpty();
      assertThat(preservedKeptMember.annotatedSignature.comment).isEqualTo(keptMember.annotatedSignature.comment);
      assertThat(replacedConflictingReturnMember.annotatedSignature.value).isEqualTo("(Ljava/lang/Object;)L1java/lang/Object;");
      assertThat(replacedConflictingReturnMember.annotatedSignature.comment).contains(EEAFile.MARKER_GENERATED).doesNotContain(
         EEAFile.MARKER_POLY_NULL);
      // Manual PolyNull evidence and the withdrawn generated dependency must both remain stable on a repeated run.
      generationConfig.inputDirs.clear();
      generationConfig.inputDirs.add(generationOutputDir);
      assertThat(EEAGenerator.generateEEAFiles(generationConfig)).isZero();
   }

   @Test
   void testPolyNullReturnDoesNotInheritParentReturnAnnotation(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var parentEEAFile = new EEAFile(PolyNullParent.class.getName());
      final var parentMember = parentEEAFile.addMember("identity", methodSignature);
      parentMember.annotatedSignature.value = "(Ljava/lang/Object;)L1java/lang/Object;";
      parentMember.annotatedSignature.comment = "# manual parent return";
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullParent.class.getName()) //
            || classInfo.getName().equals(PolyNullChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedChildMember = EEAFile.load(outputDir, PolyNullChild.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedChildMember).isNotNull();
      assert generatedChildMember != null;
      // The child's dependency is local evidence that rejects the parent's unconditional return annotation.
      assertThat(generatedChildMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedChildMember.annotatedSignature.comment).isEqualTo("# @Generated(PolyNull) @Overrides(" + PolyNullParent.class
         .getName() + ")");
   }

   @Test
   void testParentPolyNullMetadataIsNotCopiedToChild(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var parentEEAFile = new EEAFile(ParentOnlyPolyNull.class.getName());
      final var parentMember = parentEEAFile.addMember("identity", methodSignature);
      parentMember.annotatedSignature.value = "(L1java/lang/Object;)Ljava/lang/Object;";
      parentMember.annotatedSignature.comment = "# manual " + EEAFile.MARKER_POLY_NULL;
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ParentOnlyPolyNull.class.getName()) //
            || classInfo.getName().equals(ParentOnlyPolyNullChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedChildMember = EEAFile.load(outputDir, ParentOnlyPolyNullChild.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedChildMember).isNotNull();
      assert generatedChildMember != null;
      // The copied parameter and the child's unconditional return are both generator-managed in the child.
      assertThat(generatedChildMember.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)L1java/lang/Object;");
      assertThat(generatedChildMember.annotatedSignature.comment).isEqualTo("# @Overrides(" + ParentOnlyPolyNull.class.getName() + ")");
   }

   @Test
   void testPolyNullParentStopsInheritanceFromMoreDistantConcreteContract(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var grandparentEEAFile = new EEAFile(PolyNullBoundaryGrandparent.class.getName());
      final var grandparentMember = grandparentEEAFile.addMember("identity", methodSignature);
      grandparentMember.annotatedSignature.value = "(Ljava/lang/Object;)L1java/lang/Object;";
      grandparentMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED;
      grandparentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullBoundaryGrandparent.class.getName()) //
            || classInfo.getName().equals(PolyNullBoundaryParent.class.getName()) //
            || classInfo.getName().equals(PolyNullBoundaryChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedParentMember = EEAFile.load(outputDir, PolyNullBoundaryParent.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedParentMember).isNotNull();
      assert generatedParentMember != null;
      assertThat(generatedParentMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedParentMember.annotatedSignature.comment).contains("@Generated(PolyNull)").doesNotContain(
         EEAFile.MARKER_POLY_NULL);

      final var generatedChildMember = EEAFile.load(outputDir, PolyNullBoundaryChild.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedChildMember).isNotNull();
      assert generatedChildMember != null;
      // The nearest PolyNull contract deliberately owns an unqualified return and hides the grandparent's non-null
      // return.
      assertThat(generatedChildMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedChildMember.annotatedSignature.comment).isEqualTo("# @Inherited(" + PolyNullBoundaryParent.class.getName() + ")");
   }

   @Test
   void testParentPolyNullBoundaryDoesNotClaimManualChildEvidence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var childEEAFile = new EEAFile(PolyNullBoundaryChild.class.getName());
      final var childMember = childEEAFile.addMember("identity", methodSignature);
      childMember.annotatedSignature.value = "(L1java/lang/Object;)Ljava/lang/Object;";
      childMember.annotatedSignature.comment = "# manual child parameter";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullBoundaryParent.class.getName()) //
            || classInfo.getName().equals(PolyNullBoundaryChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, PolyNullBoundaryChild.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      /* The parent's PolyNull is an inheritance boundary, not evidence copied into the child. Leaving a bare
       * relationship here would therefore claim the otherwise manual parameter and withdraw it on the next fixed-point
       * pass. */
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(childMember.annotatedSignature.value);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo(childMember.annotatedSignature.comment);
      assertThat(EEAGenerator.generateEEAFiles(config)).isZero();
   }

   @Test
   void testInheritedRelationshipWithdrawsStoredChildPolyNullOnGeneratorSilence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var childEEAFile = new EEAFile(PolyNullBoundaryChild.class.getName());
      final var childMember = childEEAFile.addMember("identity", methodSignature);
      // A bare relationship deliberately owns every stored contract element, including standalone PolyNull evidence.
      childMember.annotatedSignature.comment = "# @Inherited(" + PolyNullBoundaryParent.class.getName() + ") " + EEAFile.MARKER_POLY_NULL;
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullBoundaryParent.class.getName()) //
            || classInfo.getName().equals(PolyNullBoundaryChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, PolyNullBoundaryChild.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // The parent remains the inheritance boundary, but current silence withdraws the relationship-owned PolyNull.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo("# @Inherited(" + PolyNullBoundaryParent.class.getName() + ")");
   }

   @Test
   void testEarlierInputKeepPreventsLaterInputOverride(@TempDir final Path tempDir) throws IOException {
      final Path firstInputDir = tempDir.resolve("first-input");
      final Path secondInputDir = tempDir.resolve("second-input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var firstEEAFile = new EEAFile(PolyNullUnknownReturn.class.getName());
      final var keptMember = firstEEAFile.addMember("unknown", methodSignature);
      keptMember.annotatedSignature.value = "(L1java/lang/Object;)Ljava/lang/Object;";
      keptMember.annotatedSignature.comment = "# " + EEAFile.MARKER_KEEP + " first input";
      firstEEAFile.save(firstInputDir, SaveOption.REPLACE_EXISTING);

      final var secondEEAFile = new EEAFile(PolyNullUnknownReturn.class.getName());
      final var conflictingMember = secondEEAFile.addMember("unknown", methodSignature);
      conflictingMember.annotatedSignature.value = "(L0java/lang/Object;)Ljava/lang/Object;";
      conflictingMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED + " second input";
      secondEEAFile.save(secondInputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(firstInputDir);
      config.inputDirs.add(secondInputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullUnknownReturn.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, PolyNullUnknownReturn.class.getName()).findMatchingClassMember("unknown",
         methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // @Keep protects the first complete input contract before generator update rules are evaluated.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(keptMember.annotatedSignature.value);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo(keptMember.annotatedSignature.comment);
   }

   @Test
   void testLaterInputFillsMissingPositionsWithoutReplacingEarlierEvidence(@TempDir final Path tempDir) throws IOException {
      final Path firstInputDir = tempDir.resolve("first-input");
      final Path secondInputDir = tempDir.resolve("second-input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";

      final var firstEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var firstMember = firstEEAFile.addMember("cleared", methodSignature);
      firstMember.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/Object;)V";
      firstMember.annotatedSignature.comment = "# current module contract";
      firstEEAFile.save(firstInputDir, SaveOption.REPLACE_EXISTING);

      final var secondEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var secondMember = secondEEAFile.addMember("cleared", methodSignature);
      secondMember.annotatedSignature.value = "(L0java/lang/String;L0java/lang/Object;)V";
      secondMember.annotatedSignature.comment = "# @Keep predecessor disagreement";
      secondEEAFile.save(secondInputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(firstInputDir);
      config.inputDirs.add(secondInputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, ExplicitParameterParent.class.getName()).findMatchingClassMember(firstMember);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // The current module owns conflicts, while its predecessor can still supply an independent missing position.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo("(L1java/lang/String;L0java/lang/Object;)V");
      // The predecessor's @Keep still protects the complete merged member without replacing the current comment.
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo(firstMember.annotatedSignature.comment + " @Keep");
   }

   @Test
   void testRawInheritedAliasStopsAtUnavailableNamedParent(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var grandparentEEAFile = new EEAFile(PolyNullBoundaryGrandparent.class.getName());
      final var grandparentMember = grandparentEEAFile.addMember("identity", methodSignature);
      grandparentMember.annotatedSignature.value = "(Ljava/lang/Object;)L1java/lang/Object;";
      grandparentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(PolyNullBoundaryChild.class.getName());
      final var childMember = childEEAFile.addMember("identity", methodSignature);
      childMember.annotatedSignature.comment = "# @Inherited(" + PolyNullBoundaryParent.class.getName() + ")";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullBoundaryDescendant.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, PolyNullBoundaryDescendant.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // The stored alias is the nearest known contract while its named parent EEA cannot be checked.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo("# @Inherited(" + PolyNullBoundaryChild.class.getName() + ")");
   }

   @Test
   void testRawInheritedAliasFollowsAvailableNamedParent(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var grandparentEEAFile = new EEAFile(PolyNullBoundaryGrandparent.class.getName());
      final var grandparentMember = grandparentEEAFile.addMember("identity", methodSignature);
      grandparentMember.annotatedSignature.value = "(Ljava/lang/Object;)L1java/lang/Object;";
      grandparentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var parentEEAFile = new EEAFile(PolyNullBoundaryParent.class.getName());
      final var parentMember = parentEEAFile.addMember("identity", methodSignature);
      // This legacy unscoped form still owns its standalone PolyNull evidence during migration.
      parentMember.annotatedSignature.comment = "# " + EEAFile.MARKER_GENERATED + " " + EEAFile.MARKER_POLY_NULL;
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(PolyNullBoundaryChild.class.getName());
      final var childMember = childEEAFile.addMember("identity", methodSignature);
      childMember.annotatedSignature.comment = "# @Inherited(" + PolyNullBoundaryParent.class.getName() + ")";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(PolyNullBoundaryDescendant.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, PolyNullBoundaryDescendant.class.getName()).findMatchingClassMember("identity",
         methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // An available named parent is the current source of the aliased contract; the intermediate marker is not a
      // boundary.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo("# @Inherited(" + PolyNullBoundaryParent.class.getName() + ")");
   }

   @Test
   void testGenerateAdditiveUsesThePreservedParentContractForInheritance(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";
      final String legacyAnnotatedSignature = "(L1java/lang/String;L0java/lang/Object;)V";

      final var parentEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var parentMember = parentEEAFile.addMember("inherit", methodSignature);
      parentMember.annotatedSignature.value = legacyAnnotatedSignature;
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(ExplicitParameterChild.class.getName());
      final var childMember = childEEAFile.addMember("inherit", methodSignature);
      childMember.annotatedSignature.value = legacyAnnotatedSignature;
      childMember.annotatedSignature.comment = "# child contract explanation";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName()) //
            || classInfo.getName().equals(ExplicitParameterChild.class.getName());
      config.generationMode = EEAGenerator.GenerationMode.ADDITIVE;
      EEAGenerator.generateEEAFiles(config);

      final var generatedParent = EEAFile.load(outputDir, ExplicitParameterParent.class.getName()).findMatchingClassMember(parentMember);
      final var generatedChild = EEAFile.load(outputDir, ExplicitParameterChild.class.getName()).findMatchingClassMember(childMember);
      assertThat(generatedParent).isNotNull();
      assertThat(generatedChild).isNotNull();
      assert generatedParent != null;
      assert generatedChild != null;
      // The rejected parent replacement must not become the in-memory inheritance base for this additive run.
      assertThat(generatedParent.annotatedSignature.value).isEqualTo(legacyAnnotatedSignature);
      assertThat(generatedParent.annotatedSignature.comment).isEmpty();
      assertThat(generatedChild.annotatedSignature.value).isEqualTo(legacyAnnotatedSignature);
      assertThat(generatedChild.annotatedSignature.comment).isEqualTo("# @Inherited(" + ExplicitParameterParent.class.getName()
            + ") child contract explanation");
   }

   @Test
   void testRelationshipOwnershipDoesNotClaimIndependentChildEvidence(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";

      final var parentEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var parentMember = parentEEAFile.addMember("inherit", methodSignature);
      parentMember.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/Object;)V";
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(ExplicitParameterChild.class.getName());
      final var childMember = childEEAFile.addMember("inherit", methodSignature);
      childMember.annotatedSignature.value = "(Ljava/lang/String;L0java/lang/Object;)V";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName()) //
            || classInfo.getName().equals(ExplicitParameterChild.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedChild = EEAFile.load(outputDir, ExplicitParameterChild.class.getName()).findMatchingClassMember(childMember);
      assertThat(generatedChild).isNotNull();
      assert generatedChild != null;
      assertThat(generatedChild.annotatedSignature.value).isEqualTo("(L1java/lang/String;L0java/lang/Object;)V");
      // Gap 2 was copied by the generator. Gap 20 was independently maintained in the child and stays manual.
      assertThat(generatedChild.annotatedSignature.comment).isEqualTo("# @Generated(2) @Overrides(" + ExplicitParameterParent.class
         .getName() + ")");
   }

   @Test
   void testUnavailableRelationshipParentIsNotReplacedByAnotherParent(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;)Ljava/lang/String;";

      final var availableParentEEAFile = new EEAFile(AvailableRelationshipParent.class.getName());
      final var availableParentMethod = availableParentEEAFile.addMember("relationshipValue", methodSignature);
      availableParentMethod.annotatedSignature.value = "(L1java/lang/String;)L1java/lang/String;";
      availableParentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(MultipleRelationshipParents.class.getName());
      final var childMethod = childEEAFile.addMember("relationshipValue", methodSignature);
      childMethod.annotatedSignature.value = "(L0java/lang/String;)L0java/lang/String;";
      childMethod.annotatedSignature.comment = "# @Inherited(" + UnavailableRelationshipParent.class.getName() + ")";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(MultipleRelationshipParents.class.getName());
      EEAGenerator.generateEEAFiles(config);

      final var generatedChildMethod = EEAFile.load(outputDir, MultipleRelationshipParents.class.getName()).findMatchingClassMember(
         childMethod);
      assertThat(generatedChildMethod).isNotNull();
      assert generatedChildMethod != null;
      // Another annotated ancestor cannot replace the stored relationship until the named parent's EEA can be
      // checked.
      assertThat(generatedChildMethod.annotatedSignature.value).isEqualTo(childMethod.annotatedSignature.value);
      assertThat(generatedChildMethod.annotatedSignature.comment).isEqualTo(childMethod.annotatedSignature.comment);
   }

   @Test
   void testMostSpecificInterfaceContractWinsOverItsTransitiveBase(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;)V";

      final var baseEEAFile = new EEAFile(TransitiveInterfaceBase.class.getName());
      final var baseMember = baseEEAFile.addMember("accept", methodSignature);
      baseMember.annotatedSignature.value = "(L0java/lang/String;)V";
      baseEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var derivedEEAFile = new EEAFile(TransitiveInterfaceDerived.class.getName());
      final var derivedMember = derivedEEAFile.addMember("accept", methodSignature);
      derivedMember.annotatedSignature.value = "(L1java/lang/String;)V";
      derivedEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(TransitiveInterfaceImplementation.class.getName());
      config.deleteIfEmpty = false;
      EEAGenerator.generateEEAFiles(config);

      final var generatedMember = EEAFile.load(outputDir, TransitiveInterfaceImplementation.class.getName()).findMatchingClassMember(
         "accept", methodSignature);
      assertThat(generatedMember).isNotNull();
      assert generatedMember != null;
      // A redeclaration in Derived is the effective inherited contract; Base is not a competing unrelated parent.
      assertThat(generatedMember.annotatedSignature.value).isEqualTo(derivedMember.annotatedSignature.value);
      assertThat(generatedMember.annotatedSignature.comment).isEqualTo("# @Inherited(" + TransitiveInterfaceDerived.class.getName() + ")");
   }

   @Test
   void testGeneratorManagedRelationshipSignaturesAreRebased(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/String;Ljava/lang/Object;)V";
      final String genericMethodSignature = "(Ljava/util/List<Ljava/lang/String;>;)Ljava/util/List<Ljava/lang/String;>;";

      final var parentEEAFile = new EEAFile(ExplicitParameterParent.class.getName());
      final var parentMethod = parentEEAFile.addMember("update", methodSignature);
      parentMethod.annotatedSignature.value = "(L1java/lang/String;L0java/lang/Object;)V";
      final var inheritedParentMethod = parentEEAFile.addMember("inherit", methodSignature);
      inheritedParentMethod.annotatedSignature.value = parentMethod.annotatedSignature.value;
      final var matchingParentMethod = parentEEAFile.addMember("match", methodSignature);
      matchingParentMethod.annotatedSignature.value = parentMethod.annotatedSignature.value;
      final var extendedParentMethod = parentEEAFile.addMember("extend", methodSignature);
      extendedParentMethod.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/Object;)V";
      final var shrinkingParentMethod = parentEEAFile.addMember("shrinkInherited", methodSignature);
      shrinkingParentMethod.annotatedSignature.value = "(L1java/lang/String;Ljava/lang/Object;)V";
      parentEEAFile.addMember("clearInherited", methodSignature);
      parentEEAFile.addMember("cleared", methodSignature);
      final var genericParentMethod = parentEEAFile.addMember("merge", genericMethodSignature);
      genericParentMethod.annotatedSignature.value = "(Ljava/util/List<L0java/lang/String;>;)L0java/util/List<L1java/lang/String;>;";
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var childEEAFile = new EEAFile(ExplicitParameterChild.class.getName());
      final var childMethod = childEEAFile.addMember("update", methodSignature);
      // @Overrides is relationship metadata, not a manual baseline. Both the current parent and current child
      // annotation must replace these stale generated positions.
      childMethod.annotatedSignature.value = "(L1java/lang/String;L1java/lang/Object;)V";
      childMethod.annotatedSignature.comment = "# @Overrides(" + ExplicitParameterParent.class.getName() + ")";
      final var matchingChildMethod = childEEAFile.addMember("match", methodSignature);
      // An explicit child contract that agrees with the current parent must keep whole-signature inheritance.
      matchingChildMethod.annotatedSignature.value = "(L0java/lang/String;L1java/lang/Object;)V";
      matchingChildMethod.annotatedSignature.comment = "# @Inherited(" + ExplicitParameterParent.class.getName() + ")";
      final var inheritedChildMethod = childEEAFile.addMember("inherit", methodSignature);
      // This nullable first parameter represented an old local annotation that no longer exists.
      inheritedChildMethod.annotatedSignature.value = "(L0java/lang/String;L1java/lang/Object;)V";
      inheritedChildMethod.annotatedSignature.comment = childMethod.annotatedSignature.comment;
      final var extendedChildMethod = childEEAFile.addMember("extend", methodSignature);
      extendedChildMethod.annotatedSignature.value = "(L1java/lang/String;L0java/lang/Object;)V";
      extendedChildMethod.annotatedSignature.comment = childMethod.annotatedSignature.comment;
      final var shrinkingInheritedChildMethod = childEEAFile.addMember("shrinkInherited", methodSignature);
      shrinkingInheritedChildMethod.annotatedSignature.value = "(L1java/lang/String;L0java/lang/Object;)V";
      shrinkingInheritedChildMethod.annotatedSignature.comment = "# @Inherited(" + ExplicitParameterParent.class.getName() + ")";
      final var clearedInheritedChildMethod = childEEAFile.addMember("clearInherited", methodSignature);
      clearedInheritedChildMethod.annotatedSignature.value = "(L1java/lang/String;L0java/lang/Object;)V";
      clearedInheritedChildMethod.annotatedSignature.comment = shrinkingInheritedChildMethod.annotatedSignature.comment
            + " cleared explanation";
      final var clearedChildMethod = childEEAFile.addMember("cleared", methodSignature);
      // Neither local inference nor the current parent provides nullness evidence, so a legacy relationship must
      // withdraw its completely owned stored contract.
      clearedChildMethod.annotatedSignature.value = "(L0java/lang/String;L1java/lang/Object;)V";
      clearedChildMethod.annotatedSignature.comment = childMethod.annotatedSignature.comment;
      final var genericChildMethod = childEEAFile.addMember("merge", genericMethodSignature);
      // Exercise independent top-level and nested positions; the result must not copy any part of this stale value.
      genericChildMethod.annotatedSignature.value = "(L0java/util/List<L1java/lang/String;>;)L1java/util/List<L0java/lang/String;>;";
      genericChildMethod.annotatedSignature.comment = childMethod.annotatedSignature.comment;
      // The other fixtures reuse the marker-only baseline; only this member exercises explanatory-text preservation.
      childMethod.annotatedSignature.comment += " child explanation";
      childEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(ExplicitParameterParent.class.getName()) //
            || classInfo.getName().equals(ExplicitParameterChild.class.getName());

      EEAGenerator.generateEEAFiles(config);

      final var generatedChildEEAFile = EEAFile.load(outputDir, ExplicitParameterChild.class.getName());
      final var generatedChildMethod = generatedChildEEAFile.findMatchingClassMember("update", methodSignature);
      assertThat(generatedChildMethod).isNotNull();
      assert generatedChildMethod != null;
      assertThat(generatedChildMethod.annotatedSignature.value).isEqualTo("(L0java/lang/String;L0java/lang/Object;)V");
      // @Inherited describes the complete signature. Any local nullness difference makes the member an @Overrides
      // contract.
      // Both the local first parameter and the copied second parameter are generator-managed in the child.
      assertThat(generatedChildMethod.annotatedSignature.comment).isEqualTo("# @Overrides(" + ExplicitParameterParent.class.getName()
            + ") child explanation");

      final var generatedMatchingChildMethod = generatedChildEEAFile.findMatchingClassMember("match", methodSignature);
      assertThat(generatedMatchingChildMethod).isNotNull();
      assert generatedMatchingChildMethod != null;
      assertThat(generatedMatchingChildMethod.annotatedSignature.value).isEqualTo("(L1java/lang/String;L0java/lang/Object;)V");
      // A bare relationship intentionally promotes copied parent evidence to full ownership in the child.
      assertThat(generatedMatchingChildMethod.annotatedSignature.comment).isEqualTo("# @Inherited(" + ExplicitParameterParent.class
         .getName() + ")");

      final var generatedInheritedChildMethod = generatedChildEEAFile.findMatchingClassMember("inherit", methodSignature);
      assertThat(generatedInheritedChildMethod).isNotNull();
      assert generatedInheritedChildMethod != null;
      assertThat(generatedInheritedChildMethod.annotatedSignature.value).isEqualTo("(L1java/lang/String;L0java/lang/Object;)V");
      assertThat(generatedInheritedChildMethod.annotatedSignature.comment).isEqualTo("# @Inherited(" + ExplicitParameterParent.class
         .getName() + ")");

      final var generatedExtendedChildMethod = generatedChildEEAFile.findMatchingClassMember("extend", methodSignature);
      assertThat(generatedExtendedChildMethod).isNotNull();
      assert generatedExtendedChildMethod != null;
      assertThat(generatedExtendedChildMethod.annotatedSignature.value).isEqualTo(extendedParentMethod.annotatedSignature.value);
      assertThat(generatedExtendedChildMethod.annotatedSignature.comment).isEqualTo("# @Inherited(" + ExplicitParameterParent.class
         .getName() + ")");

      final var generatedShrinkingInheritedChildMethod = generatedChildEEAFile.findMatchingClassMember("shrinkInherited", methodSignature);
      assertThat(generatedShrinkingInheritedChildMethod).isNotNull();
      assert generatedShrinkingInheritedChildMethod != null;
      // @Inherited owns the complete old signature, so the child must follow the parent's smaller current contract.
      assertThat(generatedShrinkingInheritedChildMethod.annotatedSignature.value).isEqualTo(shrinkingParentMethod.annotatedSignature.value);
      assertThat(generatedShrinkingInheritedChildMethod.annotatedSignature.comment).isEqualTo("# @Inherited("
            + ExplicitParameterParent.class.getName() + ")");

      final var generatedClearedInheritedChildMethod = generatedChildEEAFile.findMatchingClassMember("clearInherited", methodSignature);
      assertThat(generatedClearedInheritedChildMethod).isNotNull();
      assert generatedClearedInheritedChildMethod != null;
      // An available parent with no annotations is current empty evidence, not a temporarily missing EEA.
      assertThat(generatedClearedInheritedChildMethod.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedClearedInheritedChildMethod.annotatedSignature.comment).isEqualTo("# cleared explanation");

      final var generatedClearedChildMethod = generatedChildEEAFile.findMatchingClassMember("cleared", methodSignature);
      assertThat(generatedClearedChildMethod).isNotNull();
      assert generatedClearedChildMethod != null;
      // The legacy relationship owned the complete stored contract. With neither current parent nor local evidence,
      // full generation withdraws both positions together with the stale relationship.
      assertThat(generatedClearedChildMethod.annotatedSignature.value).isEqualTo(methodSignature);
      assertThat(generatedClearedChildMethod.annotatedSignature.comment).isEmpty();

      final var generatedGenericChildMethod = generatedChildEEAFile.findMatchingClassMember("merge", genericMethodSignature);
      assertThat(generatedGenericChildMethod).isNotNull();
      assert generatedGenericChildMethod != null;
      assertThat(generatedGenericChildMethod.annotatedSignature.value).isEqualTo(
         "(L1java/util/List<L0java/lang/String;>;)L0java/util/List<L1java/lang/String;>;");
      assertThat(generatedGenericChildMethod.annotatedSignature.comment).isEqualTo("# @Overrides(" + ExplicitParameterParent.class.getName()
            + ")");

      // Re-reading the same stale input must converge to the same managed relationship contracts without output
      // churn.
      assertThat(EEAGenerator.generateEEAFiles(config)).isZero();
   }

   @Test
   void testGenerateHandlesSyntheticObjectTemplate(@TempDir final Path outputDir) throws IOException {
      final var config = new EEAGenerator.Config(outputDir, "java.lang");
      // Excluding scanned classes leaves only the synthetic Object fallback used for ClassGraph issue #703.
      config.classFilter = classInfo -> false;

      EEAGenerator.generateEEAFiles(config);

      assertThat(EEAFile.loadIfExists(outputDir, Object.class.getName())).isNotNull();
   }

   @Test
   void testGenerateDoesNotInheritAnnotationsOntoHiddenFields(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");

      final var parentEEAFile = new EEAFile(HiddenFieldParent.class.getName());
      final var parentField = parentEEAFile.addMember("value", "Ljava/lang/String;");
      parentField.annotatedSignature.value = "L1java/lang/String;";
      parentEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(inputDir);
      config.classFilter = classInfo -> classInfo.getName().equals(HiddenFieldParent.class.getName()) || classInfo.getName().equals(
         HiddenFieldChild.class.getName());
      config.deleteIfEmpty = false;

      EEAGenerator.generateEEAFiles(config);

      final var childEEAFile = EEAFile.load(outputDir, HiddenFieldChild.class.getName());
      final var childField = childEEAFile.findMatchingClassMember("value", "Ljava/lang/String;");
      assertThat(childField).isNotNull();
      assert childField != null;
      assertThat(childField.annotatedSignature.value).isEqualTo(childField.originalSignature.value);
      assertThat(childField.annotatedSignature.comment).doesNotContain("@Inherited");
   }

   @Test
   void testMainAcceptsBarePropertiesFilePath(@TempDir final Path tempDir) throws Exception {
      final Path inputDir = Files.createDirectory(tempDir.resolve("input"));
      final Path propertiesFile = Files.createTempFile(Path.of("."), "eea-generator-test-", ".properties");
      final var properties = new Properties();
      properties.setProperty(EEAGenerator.PROPERTY_ACTION, "minimize");
      properties.setProperty(EEAGenerator.PROPERTY_INPUT_DIRS, inputDir.toString());
      properties.setProperty(EEAGenerator.PROPERTY_OUTPUT_DIR, "target/" + propertiesFile.getFileName() + "-output");
      try (var writer = Files.newBufferedWriter(propertiesFile)) {
         properties.store(writer, null);
      }

      try {
         assertThatCode(() -> EEAGenerator.main(propertiesFile.getFileName().toString())).doesNotThrowAnyException();
      } finally {
         Files.deleteIfExists(propertiesFile);
      }
   }

   @Test
   void testInputDirsExtraIsRelativeToItsPropertiesFile(@TempDir final Path tempDir) throws Exception {
      final Path configDir = Files.createDirectory(tempDir.resolve("config"));
      final Path inputDir = Files.createDirectory(configDir.resolve("input"));
      final Path outputDir = configDir.resolve("output");
      final Path propertiesFile = configDir.resolve("eea-generator.properties");

      final var inputEEAFile = new EEAFile("test.Type");
      final var inputField = inputEEAFile.addMember("value", "Ljava/lang/String;");
      inputField.annotatedSignature.value = "L1java/lang/String;";
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var properties = new Properties();
      properties.setProperty(EEAGenerator.PROPERTY_ACTION, "minimize");
      properties.setProperty(EEAGenerator.PROPERTY_INPUT_DIRS_EXTRA, "input");
      properties.setProperty(EEAGenerator.PROPERTY_OUTPUT_DIR, "output");
      try (var writer = Files.newBufferedWriter(propertiesFile)) {
         properties.store(writer, null);
      }

      EEAGenerator.main(propertiesFile.toString());

      assertThat(outputDir.resolve(inputEEAFile.relativePath)).exists();
   }

   @Test
   void testMinimizeRejectsMissingInputDirsWithoutDeletingOutput(@TempDir final Path tempDir) throws IOException {
      final Path outputDir = Files.createDirectory(tempDir.resolve("output"));
      final Path existingOutput = Files.createFile(outputDir.resolve("existing.eea"));
      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(tempDir.resolve("missing"));

      assertThatThrownBy(() -> EEAGenerator.minimizeEEAFiles(config)) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessage("None of the specified input.dirs exist!");
      assertThat(existingOutput).exists();
   }

   @Test
   void testMinimizeTreatsExistingEmptyInputDirAsEmptySource(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = Files.createDirectory(tempDir.resolve("input"));
      final Path outputDir = Files.createDirectory(tempDir.resolve("output"));
      final Path existingOutput = Files.createFile(outputDir.resolve("existing.eea"));
      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(inputDir);

      assertThat(EEAGenerator.minimizeEEAFiles(config)).isOne();
      assertThat(existingOutput).doesNotExist();
   }

   @Test
   void testMinimizeStripsOwnershipComments(@TempDir final Path tempDir) throws IOException {
      final Path inputDir = tempDir.resolve("input");
      final Path outputDir = tempDir.resolve("output");
      final var inputEEAFile = new EEAFile("test.Type");
      for (final String[] memberAndComment : new String[][] { //
         {"generated", "# @Generated"}, //
         {"kept", "# @Keep - manually reviewed"}, //
         {"inherited", "# @Inherited(test.Parent)"}, //
         {"overriding", "# @Overrides(test.Parent)"} //
      }) {
         final var member = inputEEAFile.addMember(memberAndComment[0], "()Ljava/lang/String;");
         member.annotatedSignature.value = "()L1java/lang/String;";
         member.annotatedSignature.comment = memberAndComment[1];
      }
      inputEEAFile.save(inputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(inputDir);
      EEAGenerator.minimizeEEAFiles(config);

      final String minimizedContent = Files.readString(outputDir.resolve(inputEEAFile.relativePath));
      assertThat(minimizedContent).doesNotContain("@Generated", "@Keep", "@Inherited", "@Overrides");
      assertThat(EEAFile.load(outputDir, inputEEAFile.classHeader.name.value).getClassMembers()).hasSize(4);
   }

   @Test
   void testMinimizePreservesPolyNullReturnWhileAddingIndependentParameterAnnotation(@TempDir final Path tempDir) throws IOException {
      final Path firstInputDir = tempDir.resolve("first-input");
      final Path secondInputDir = tempDir.resolve("second-input");
      final Path outputDir = tempDir.resolve("output");
      final String methodSignature = "(Ljava/lang/Object;)Ljava/lang/Object;";

      final var polyNullEEAFile = new EEAFile("test.Type");
      final var polyNullMember = polyNullEEAFile.addMember("identity", methodSignature);
      polyNullMember.annotatedSignature.comment = "# @Generated(PolyNull)";
      polyNullEEAFile.save(firstInputDir, SaveOption.REPLACE_EXISTING);

      final var annotatedEEAFile = new EEAFile("test.Type");
      final var annotatedMember = annotatedEEAFile.addMember("identity", methodSignature);
      annotatedMember.annotatedSignature.value = "(L1java/lang/Object;)L1java/lang/Object;";
      annotatedEEAFile.save(secondInputDir, SaveOption.REPLACE_EXISTING);

      final var config = new EEAGenerator.Config(outputDir);
      config.inputDirs.add(firstInputDir);
      config.inputDirs.add(secondInputDir);
      EEAGenerator.minimizeEEAFiles(config);

      final var minimizedMember = EEAFile.load(outputDir, "test.Type").findMatchingClassMember("identity", methodSignature);
      assertThat(minimizedMember).isNotNull();
      assert minimizedMember != null;
      // First-source PolyNull owns only the top-level return; the later source can still contribute its parameter
      // marker.
      assertThat(minimizedMember.annotatedSignature.value).isEqualTo("(L1java/lang/Object;)Ljava/lang/Object;");
   }

   @Test
   void testPackageMissingOnClasspath() {
      final var rootPath = Path.of("src/test/resources/valid");
      final var config = new EEAGenerator.Config(rootPath, "org.no_npe.foobar");

      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalArgumentException.class) //
         .hasMessage("No classes found for package [org.no_npe.foobar] on classpath");
   }

   @Test
   void testTypeMissingOnClasspath() {
      final var rootPath = Path.of("src/test/resources/nonexistant_type");
      final var config = new EEAGenerator.Config(rootPath, EEAGeneratorTest.class.getPackageName());
      config.inputDirs.add(rootPath);

      assertThatThrownBy(() -> {
         EEAGenerator.validateEEAFiles(config);
      }) //
         .isInstanceOf(IllegalStateException.class) //
         .hasMessageMatching("Type .*NonexistantType.* defined in .* not found on classpath.");
   }
}
