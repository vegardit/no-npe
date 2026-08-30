/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.EEAFile.*;
import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.remap;
import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.Test;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;

/**
 * Verifies EEA parsing, merging, marker ownership, and source/artifact rendering rules.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
class EEAFileTest {

   public static final class TestEntity {
      public static final String STATIC_STRING = "MyStaticString";

      public String name;

      public TestEntity(final String name) { // CHECKSTYLE:IGNORE RedundantModifier
         this.name = name;
      }

      public @Nullable String keepTest1() {
         return null;
      }

      public String keepTest2() {
         return name;
      }
   }

   public static final class TestEntity2 {
      public static final String STATIC_STRING = "MyStaticString";
   }

   static final String TEST_ENTITY_NAME_WITH_SLASHES = TestEntity.class.getName().replace('.', '/');
   static final String WRONG_TYPE_NAME = EEAFileTest.class.getName() + "$WrongType";
   static final String WRONG_TYPE_NAME_WITH_SLASHES = WRONG_TYPE_NAME.replace('.', '/');

   @Test
   void testEEAFile() throws IOException {
      final var eeaFile = load(Path.of("src/test/resources/valid"), TestEntity.class.getName());

      assertThat(eeaFile.classHeader.name.value).isEqualTo(TestEntity.class.getName());
      assertThat(eeaFile.classHeader.name.comment).isEqualTo("# a class comment");
      assertThat(eeaFile.classHeader.name).hasToString(TestEntity.class.getName() + " # a class comment");
      assertThat(eeaFile.relativePath).isEqualTo(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));

      assertThat(eeaFile.getClassMembers()).isNotEmpty();
      final var field = eeaFile.findMatchingClassMember("STATIC_STRING", "Ljava/lang/String;");
      assert field != null;
      assertThat(field.originalSignature.value).isEqualTo("Ljava/lang/String;");

      final var annotatedSignature = field.annotatedSignature;
      assertThat(annotatedSignature).isNotNull();
      assert annotatedSignature != null;

      assertThat(annotatedSignature.value).isEqualTo("L1java/lang/String;");
      assertThat(annotatedSignature.comment).isEqualTo("# an annotated signature comment");

      assertThat(eeaFile.renderFileContent(Set.of())) //
         .isEqualTo(Files.readAllLines(Path.of("src/test/resources/valid").resolve(eeaFile.relativePath)));

      assertThat(eeaFile.renderFileContent(Set.of(SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES))) //
         .isNotEqualTo(Files.readAllLines(Path.of("src/test/resources/valid").resolve(eeaFile.relativePath)));
   }

   @Test
   void testIllegalOriginalSignatureComments() {
      assertThatThrownBy(() -> { //
         load(Path.of("src/test/resources/illegal_original_signature_comments"), TestEntity.class.getName());
      }) //
         .isInstanceOf(java.io.IOException.class) //
         .hasMessageMatching("Comments after original signatures are not supported .*");
   }

   @Test
   void testIllegalMemberComments() {
      assertThatThrownBy(() -> { //
         load(Path.of("src/test/resources/illegal_member_comments"), TestEntity.class.getName());
      }) //
         .isInstanceOf(java.io.IOException.class) //
         .hasMessageMatching("Comments after member name are not supported .*");
   }

   @Test
   void testMetadataOnlyContractSurvivesSourceCompaction() {
      final var eeaFile = new EEAFile("test.MetadataOnly");
      final var member = eeaFile.addMember("identity", "(Ljava/lang/Object;)Ljava/lang/Object;");
      member.annotatedSignature.comment = "# " + MARKER_GENERATED + "(PolyNull)";

      final Set<SaveOption> sourceCompaction = Set.of(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE,
         SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES);
      assertThat(eeaFile.renderFileContent(sourceCompaction)).contains( //
         "identity", //
         " (Ljava/lang/Object;)Ljava/lang/Object; # " + MARKER_GENERATED + "(PolyNull)");

      // Minimized artifacts intentionally discard source-only markers and therefore no longer need this member.
      assertThat(eeaFile.renderFileContent(Set.of( //
         SaveOption.OMIT_COMMENTS, //
         SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE, //
         SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES))).doesNotContain("identity");
   }

   @Test
   void testInheritedSignatureOmissionRetainsOverridesAndExplicitKeeps() {
      final var eeaFile = new EEAFile("test.Child");
      for (final String[] memberAndComment : new String[][] { //
         {"inherited", "# @Inherited(test.Parent)"}, //
         {"overriding", "# @Overrides(test.Parent)"}, //
         {"keptInherited", "# @Inherited(test.Parent) @Keep"} //
      }) {
         final var member = eeaFile.addMember(memberAndComment[0], "()Ljava/lang/String;");
         member.annotatedSignature.value = "()L1java/lang/String;";
         member.annotatedSignature.comment = memberAndComment[1];
      }

      // Artifact rendering strips ownership comments, but it must consult them first to remove only redundant inheritance.
      final var renderedContent = eeaFile.renderFileContent(Set.of( //
         SaveOption.OMIT_COMMENTS, //
         SaveOption.OMIT_MEMBERS_WITH_INHERITED_ANNOTATED_SIGNATURES));
      assertThat(renderedContent).doesNotContain("inherited").contains("overriding", "keptInherited");
   }

   @Test
   void testGeneratorMetadataUsesAnnotatedSignatureComment() {
      final var member = new EEAFile.ClassMember("identity", "(Ljava/lang/Object;)Ljava/lang/Object;");
      member.name.comment = "# " + MARKER_GENERATED;
      member.originalSignature.comment = "# " + MARKER_POLY_NULL;

      // ECJ permits contract metadata only on the annotated-signature line. The in-memory helpers must enforce the same
      // boundary so source compaction cannot retain and render a member that the loader would later reject.
      assertThat(member.hasGeneratedMarker()).isFalse();
      assertThat(member.hasPolyNullMarker()).isFalse();
      assertThat(member.hasSourceContractMarker()).isFalse();

      member.annotatedSignature.comment = "# " + MARKER_GENERATED + "(PolyNull)";
      assertThat(member.hasGeneratedMarker()).isTrue();
      assertThat(member.hasPolyNullMarker()).isTrue();
      assertThat(member.hasSourceContractMarker()).isTrue();

      member.annotatedSignature.comment = "";
      member.name.comment = "# " + MARKER_KEEP;
      assertThat(member.hasKeepMarker()).isFalse();
      assertThat(member.hasSourceContractMarker()).isFalse();

      // @Keep protects the complete member, but EEA member metadata belongs on the annotated-signature line.
      member.annotatedSignature.comment = "# " + MARKER_KEEP;
      assertThat(member.hasKeepMarker()).isTrue();
      assertThat(member.hasSourceContractMarker()).isTrue();
   }

   @Test
   void testGeneratorMarkersRequireWholeTokens() {
      final var member = new EEAFile.ClassMember("identity", "(Ljava/lang/Object;)Ljava/lang/Object;");
      member.annotatedSignature.comment = "# @GeneratedBackup @Generated(2)Suffix @PolyNullable @Inherited(test.Parent)Suffix @Overrides()";

      // Control markers, including @Keep, require whole tokens so similarly named prose cannot change generator policy.
      assertThat(member.hasGeneratedMarker()).isFalse();
      assertThat(member.hasPolyNullMarker()).isFalse();
      assertThat(member.hasSourceContractMarker()).isFalse();
      assertThat(getRelationshipMarkerParent(member.annotatedSignature.comment, MARKER_INHERITED)).isNull();
      assertThat(getRelationshipMarkerParent(member.annotatedSignature.comment, MARKER_OVERRIDES)).isNull();

      // EEA comments permit omitting the whitespace after '#'; that introducer is a token boundary, not marker text.
      member.annotatedSignature.comment = "#@Generated";
      assertThat(member.hasGeneratedMarker()).isTrue();
      assertThat(removeCommentMarker(member.annotatedSignature.comment, MARKER_GENERATED)).isEmpty();
      member.annotatedSignature.comment = "# @Generated(2,23,PolyNull) explanation";
      final var generatedMarker = findGeneratedMarker(member.annotatedSignature.comment);
      assertThat(generatedMarker).isNotNull();
      assert generatedMarker != null;
      assertThat(generatedMarker.arguments).isEqualTo("2,23,PolyNull");
      assertThat(member.hasPolyNullMarker()).isTrue();
      assertThat(removeGeneratedMarker(member.annotatedSignature.comment)).isEqualTo("# explanation");
      member.annotatedSignature.comment = "#@Generated";
      assertThat(member.hasGeneratedMarker()).isTrue();
      assertThat(removeGeneratedMarker(member.annotatedSignature.comment)).isEmpty();
      member.annotatedSignature.comment = "#@PolyNull";
      assertThat(member.hasPolyNullMarker()).isTrue();
      member.annotatedSignature.comment = "#@Inherited(test.Parent)";
      assertThat(getRelationshipMarkerParent(member.annotatedSignature.comment, MARKER_INHERITED)).isEqualTo("test.Parent");
      assertThat(removeRelationshipMarker(member.annotatedSignature.comment, MARKER_INHERITED)).isEmpty();
      member.annotatedSignature.comment = "#@Overrides(test.Parent)";
      assertThat(getRelationshipMarkerParent(member.annotatedSignature.comment, MARKER_OVERRIDES)).isEqualTo("test.Parent");

      member.annotatedSignature.comment = "# @Generated @PolyNull @Inherited(test.Parent$Nested) explanation";
      assertThat(member.hasGeneratedMarker()).isTrue();
      assertThat(member.hasPolyNullMarker()).isTrue();
      assertThat(member.hasSourceContractMarker()).isTrue();
      assertThat(getRelationshipMarkerParent(member.annotatedSignature.comment, MARKER_INHERITED)).isEqualTo("test.Parent$Nested");
      assertThat(removeRelationshipMarker(member.annotatedSignature.comment, MARKER_INHERITED)).isEqualTo(
         "# @Generated @PolyNull explanation");

      member.annotatedSignature.comment = "# @KeepReason";
      assertThat(member.hasKeepMarker()).isFalse();

      member.annotatedSignature.comment = "# @Keep";
      assertThat(member.hasKeepMarker()).isTrue();
   }

   @Test
   void testWrongTypeHeader() {
      assertThatThrownBy(() -> { //
         load(Path.of("src/test/resources/wrong_type"), TestEntity.class.getName());
      }) //
         .isInstanceOf(java.io.IOException.class) //
         .hasMessageMatching("Mismatch between file path of \\[.*\\.eea\\] and contained class name definition .*");
   }

   @Test
   void testApplyAnnotationsAndCommentsFrom() throws IOException {
      final var computedEEAFiles = remap(EEAGenerator.computeEEAFiles(EEAFileTest.class.getPackageName(), c -> true), v -> v.relativePath);
      final var computedEEAFile = computedEEAFiles.get(Path.of(TEST_ENTITY_NAME_WITH_SLASHES + ".eea"));
      assertThat(computedEEAFile).isNotNull();
      assert computedEEAFile != null;

      final var method = computedEEAFile.findMatchingClassMember("name", "Ljava/lang/String;");
      assert method != null;
      assertThat(method.hasNullAnnotations()).isFalse();
      assertThat(method.name.comment).isEmpty();

      final var loadedEEAFile = load(Path.of("src/test/resources/valid"), computedEEAFile.classHeader.name.value);
      computedEEAFile.applyAnnotationsAndCommentsFrom(loadedEEAFile, false, false);
      final var annotatedSignature = method.annotatedSignature;
      assert annotatedSignature != null;
      assertThat(annotatedSignature.value).isEqualTo("L1java/lang/String;");
   }

   @Test
   void testRemoveNullAnnotations() {
      assertThat(removeNullAnnotations("L0java/lang/Object;")).isEqualTo("Ljava/lang/Object;");
      assertThat(removeNullAnnotations("L1java/lang/Class<*>;L1java/lang/Class<*>;)L1java/lang/invoke/MethodType;")).isEqualTo(
         "Ljava/lang/Class<*>;Ljava/lang/Class<*>;)Ljava/lang/invoke/MethodType;");
      assertThat(removeNullAnnotations("<T::Ljava/lang/annotation/Annotation;>(L1java/lang/Class<TT;>;)[1T1T;")).isEqualTo(
         "<T::Ljava/lang/annotation/Annotation;>(Ljava/lang/Class<TT;>;)[TT;");
      assertThat(removeNullAnnotations("<1T::Ljava/util/EventListener;>(TT;)V")).isEqualTo("<T::Ljava/util/EventListener;>(TT;)V");
      assertThat(removeNullAnnotations("<T1:Ljava/lang/Object;>")).isEqualTo("<T1:Ljava/lang/Object;>"); // T1 is the name of the generic variable
   }
}
