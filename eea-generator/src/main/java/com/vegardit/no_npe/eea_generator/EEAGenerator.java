/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.ClassGraphUtils.*;
import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;

import com.vegardit.no_npe.eea_generator.EEAFile.ClassMember;
import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.EEAFile.ValueWithComment;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.MethodReturnAnalysis;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.MethodSummaryResolver;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.Nullability;
import com.vegardit.no_npe.eea_generator.internal.BytecodeAnalyzer.StaticFieldResolver;
import com.vegardit.no_npe.eea_generator.internal.ClassGraphUtils;
import com.vegardit.no_npe.eea_generator.internal.ClassGraphUtils.MethodReturnKind;
import com.vegardit.no_npe.eea_generator.internal.Props;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassMemberInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * Generates and updates Eclipse external annotation contracts from library bytecode and stored EEA sources.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public abstract class EEAGenerator {

   private static final Logger LOG = System.getLogger(EEAGenerator.class.getName());

   public static final Path DEFAULT_PROPERTES_FILE = Path.of("eea-generator.properties");

   public static final String JVM_PROPERTY_PREFIX = "eea-generator.";

   public static final String PROPERTY_ACTION = "action";
   public static final String PROPERTY_INPUT_DIRS = "input.dirs";
   public static final String PROPERTY_INPUT_DIRS_EXTRA = PROPERTY_INPUT_DIRS + ".extra";
   public static final String PROPERTY_OUTPUT_DIR = "output.dir";
   public static final String PROPERTY_OUTPUT_DIR_DEFAULT = PROPERTY_OUTPUT_DIR + ".default";
   public static final String PROPERTY_PACKAGES_INCLUDE = "packages.include";
   public static final String PROPERTY_CLASSES_EXCLUDE = "classes.exclude";
   public static final String PROPERTY_DELETE_IF_EMPTY = "deleteIfEmpty";
   public static final String PROPERTY_OMIT_REDUNDAND_ANNOTATED_SIGNATURES = "omitRedundantAnnotatedSignatures";
   public static final String PROPERTY_OMIT_CLASS_MEMBERS_WITHOUT_NULL_ANNOTATION = "omitClassMembersWithoutNullAnnotation";

   private static final EEAFile TEMPLATE_EXTERNALIZABLE;
   private static final EEAFile TEMPLATE_SERIALIZABLE;
   private static final EEAFile TEMPLATE_OBJECT;
   private static final EEAFile TEMPLATE_THROWABLE;

   private static final ClassInfo OBJECT_CLASS_INFO = new ClassInfo("java.lang.Object", Modifier.PUBLIC, null) {};

   /** Controls whether generation may replace existing nullness evidence or only extend it. */
   public enum GenerationMode {
      /** Apply current evidence and withdraw previously generated positions for which current analysis is silent. */
      FULL,
      /** Add compatible current evidence without removing stored nullness or its provenance. */
      ADDITIVE
   }

   /** Tracks which parts of an EEA contract may be withdrawn when later generator analysis becomes silent. */
   private static final class GeneratedOwnership {
      boolean polyNull;
      final BitSet positions = new BitSet();

      GeneratedOwnership copy() {
         final var copy = new GeneratedOwnership();
         copy.positions.or(positions);
         copy.polyNull = polyNull;
         return copy;
      }

      boolean isEmpty() {
         return !polyNull && positions.isEmpty();
      }
   }

   /** Retains analyzer-only provenance that must not be serialized into an EEA ownership marker. */
   private static final class GeneratedMemberEvidence {
      final ValueWithComment annotatedSignature;
      final @Nullable BitSet polyNullDependencyPositions;

      GeneratedMemberEvidence(final ValueWithComment annotatedSignature, final @Nullable BitSet polyNullDependencyPositions) {
         this.annotatedSignature = annotatedSignature;
         this.polyNullDependencyPositions = polyNullDependencyPositions == null ? null : (BitSet) polyNullDependencyPositions.clone();
      }

      GeneratedMemberEvidence copy() {
         return new GeneratedMemberEvidence(annotatedSignature.clone(), polyNullDependencyPositions);
      }
   }

   /** Keeps a reconciled signature and its positional ownership together until both can be rendered atomically. */
   private static final class ReconciledContract {
      final ValueWithComment annotatedSignature;
      final GeneratedOwnership ownership;

      ReconciledContract(final ValueWithComment annotatedSignature, final GeneratedOwnership ownership) {
         this.annotatedSignature = annotatedSignature;
         this.ownership = ownership;
      }
   }

   static {
      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Externalizable.eea")) {
         TEMPLATE_EXTERNALIZABLE = EEAFile.load(reader, "classpath:java/io/Externalizable.eea");
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Serializable.eea")) {
         TEMPLATE_SERIALIZABLE = EEAFile.load(reader, "classpath:java/io/Serializable.eea");
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Object.eea")) {
         TEMPLATE_OBJECT = EEAFile.load(reader, "classpath:java/lang/Object.eea");
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Throwable.eea")) {
         TEMPLATE_THROWABLE = EEAFile.load(reader, "classpath:java/lang/Throwable.eea");
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }
   }

   public static class Config {
      public final String[] packages;

      /** Primary EEA roots. During generation, only the first root represents the current artifact. */
      public final List<Path> inputDirs = new ArrayList<>();

      /** Upstream EEA roots appended after {@link #inputDirs}. */
      public final List<Path> inputDirsExtra = new ArrayList<>();
      public final Path outputDir;
      public Predicate<ClassInfo> classFilter = clazz -> true;
      public boolean deleteIfEmpty = true;
      public boolean omitClassMembersWithoutNullAnnotations;
      public boolean omitRedundantAnnotatedSignatures;
      public GenerationMode generationMode = GenerationMode.FULL;

      public Config(final Path outputDir, final String... packages) {
         this.outputDir = outputDir;
         this.packages = packages;
      }
   }

   private static Path resolvePath(final String pathValue, final Object source) {
      Path path = Path.of(pathValue);
      if (source instanceof Path && !path.isAbsolute()) {
         // Property-file paths must keep the same meaning when the generator is launched from another working directory.
         path = ((Path) source).getParent().resolve(path);
      }
      return path.toAbsolutePath().normalize();
   }

   private static List<Path> getEffectiveInputDirs(final Config config) {
      final var result = new ArrayList<Path>(config.inputDirs.size() + config.inputDirsExtra.size());
      for (final List<Path> inputDirs : List.of(config.inputDirs, config.inputDirsExtra)) {
         for (final Path inputDir : inputDirs) {
            if (!result.contains(inputDir)) {
               result.add(inputDir);
            }
         }
      }
      return result;
   }

   private static void addInputDirs(final Config config, final Props.Prop<String> inputDirsProperty, final boolean extra) {
      final List<Path> destination = extra ? config.inputDirsExtra : config.inputDirs;
      for (final String inputDirStr : inputDirsProperty.value.split(",")) {
         if (inputDirStr.isBlank()) {
            continue;
         }
         final Path inputDir = resolvePath(inputDirStr, inputDirsProperty.source);
         if (!config.inputDirs.contains(inputDir) && !config.inputDirsExtra.contains(inputDir)) {
            destination.add(inputDir);
            if (!Files.exists(inputDir)) {
               LOG.log(Level.WARNING, "Input directory: " + inputDir + " does not exist!");
            }
         }
      }
   }

   /**
    * args[0]: optional path to properties file
    */
   public static void main(final String... args) throws Exception {
      try {
         configureJUL();

         // load properties from file if specified
         Path filePropsPath = null;
         if (args.length > 0) {
            filePropsPath = Path.of(args[0]);
         } else if (Files.exists(DEFAULT_PROPERTES_FILE)) {
            filePropsPath = DEFAULT_PROPERTES_FILE;
         }
         if (filePropsPath != null) {
            // Props retains this path as value provenance; making it absolute also gives bare filenames a usable parent directory.
            filePropsPath = filePropsPath.toAbsolutePath().normalize();
         }
         final var props = new Props(JVM_PROPERTY_PREFIX, filePropsPath);

         final String action = props.get(PROPERTY_ACTION, null).value;

         final String[] packages = "minimize".equals(action) //
               ? new String[0] //
               : props.get(PROPERTY_PACKAGES_INCLUDE, null).value.split(",");

         final var classExclusionsStr = props.get(PROPERTY_CLASSES_EXCLUDE, "");
         final Pattern[] classExclusions = classExclusionsStr.value.isBlank() //
               ? new Pattern[0] //
               : Arrays.stream(classExclusionsStr.value.split(",")).map(Pattern::compile).toArray(Pattern[]::new);

         final var outputDirPropDefault = props.get(PROPERTY_OUTPUT_DIR_DEFAULT, "").value;
         final var outputDirProp = props.get(PROPERTY_OUTPUT_DIR, outputDirPropDefault.isEmpty() ? null : outputDirPropDefault);
         final Path outputDir = resolvePath(outputDirProp.value, outputDirProp.source);

         final var config = new Config(outputDir, packages);

         config.deleteIfEmpty = Boolean.parseBoolean(props.get(PROPERTY_DELETE_IF_EMPTY, "true").value);
         config.omitClassMembersWithoutNullAnnotations = Boolean.parseBoolean(props.get(PROPERTY_OMIT_CLASS_MEMBERS_WITHOUT_NULL_ANNOTATION,
            "false").value);
         config.omitRedundantAnnotatedSignatures = Boolean.parseBoolean(props.get(PROPERTY_OMIT_REDUNDAND_ANNOTATED_SIGNATURES,
            "false").value);
         config.classFilter = clazz -> {
            for (final Pattern classExclusion : classExclusions) {
               if (classExclusion.matcher(clazz.getName()).find())
                  return false;
            }
            return true;
         };

         final var inputDirsProp = props.get(PROPERTY_INPUT_DIRS, "");
         final var inputDirsExtraProp = props.get(PROPERTY_INPUT_DIRS_EXTRA, "");
         // Keep the lists separate: the first primary root is local, while every later root is upstream provenance.
         addInputDirs(config, inputDirsProp, false);
         addInputDirs(config, inputDirsExtraProp, true);

         LOG.log(Level.INFO, "Effective input directories: " + getEffectiveInputDirs(config));
         LOG.log(Level.INFO, "Effective output directory: " + outputDir);

         switch (action) {
            case "generate":
               generateEEAFiles(config);
               break;
            case "generate-additive":
               config.generationMode = GenerationMode.ADDITIVE;
               generateEEAFiles(config);
               break;
            case "minimize":
               minimizeEEAFiles(config);
               break;
            case "validate":
               validateEEAFiles(config);
               break;
            default:
               throw new IllegalArgumentException("Unsupported value for [action] parameter: " + action);
         }
      } catch (final UncheckedIOException ex) {
         final Exception iox = ex.getCause();
         sanitizeStackTraces(iox);
         throw iox;
      } catch (final Exception ex) {
         sanitizeStackTraces(ex);
         throw ex;
      }
   }

   protected static ValueWithComment computeAnnotatedSignature(final EEAFile.ClassMember member, final ClassInfo classInfo,
         final ClassMemberInfo memberInfo, final BytecodeAnalyzer bytecodeAnalyzer) {

      return withObjectDefaults(member, computeGeneratedMemberEvidence(member, classInfo, memberInfo, bytecodeAnalyzer).annotatedSignature);
   }

   private static GeneratedMemberEvidence computeGeneratedMemberEvidence(final EEAFile.ClassMember member, final ClassInfo classInfo,
         final ClassMemberInfo memberInfo, final BytecodeAnalyzer bytecodeAnalyzer) {

      final GeneratedMemberEvidence localEvidence = computeHeuristicAnnotatedSignature(member, classInfo, memberInfo, bytecodeAnalyzer);
      if (memberInfo instanceof MethodInfo) {
         // Explicit parameters belong to the declaration even when template defaults are applied by a later pass.
         applyExplicitParameterAnnotations(localEvidence.annotatedSignature, (MethodInfo) memberInfo);
      }
      final ValueWithComment annotatedSignature = withTemplateDefaults(member.originalSignature.value, localEvidence.annotatedSignature,
         findTemplateAnnotatedSignature(member, classInfo));
      return new GeneratedMemberEvidence(annotatedSignature, EEAFile.hasPolyNullContractMarker(annotatedSignature.comment) //
            ? localEvidence.polyNullDependencyPositions //
            : null);
   }

   private static ValueWithComment withTemplateDefaults(final String originalSignature, final ValueWithComment localSignature,
         final @Nullable ValueWithComment templateSignature) {
      if (templateSignature == null)
         return localSignature;
      // Template defaults fill gaps; concrete declaration evidence owns only the positions it establishes.
      String value = overlayNullAnnotations(originalSignature, templateSignature.value, localSignature.value);
      if (EEAFile.hasPolyNullContractMarker(localSignature.comment)) {
         // PolyNull owns the otherwise unmarked root return, so a concrete template return cannot fill that gap.
         value = removeMethodReturnNullAnnotation(originalSignature, value);
      }
      return new ValueWithComment(value, localSignature.comment.isEmpty() ? templateSignature.comment : localSignature.comment);
   }

   private static ValueWithComment withObjectDefaults(final ClassMember member, final ValueWithComment localSignature) {
      // Standalone computation has no inheritance pass. Generation must defer these defaults until Object's inputs
      // are layered: saving them as local evidence or initial child values would make additive mode retain stale defaults.
      final ClassMember templateMember = TEMPLATE_OBJECT.findMatchingClassMember(member);
      return withTemplateDefaults(member.originalSignature.value, localSignature, templateMember == null ? null
            : templateMember.annotatedSignature);
   }

   private static void applyObjectDefaults(final EEAFile eeaFile) {
      eeaFile.getClassMembers().forEach(member -> member.annotatedSignature = withObjectDefaults(member, member.annotatedSignature));
   }

   private static void applyExplicitParameterAnnotations(final ValueWithComment annotatedSignature, final MethodInfo methodInfo) {
      final var parameterInfo = methodInfo.getParameterInfo();
      final int parameterInfoOffset = determineParameterInfoOffset(methodInfo);
      for (int parameterInfoIndex = parameterInfoOffset; parameterInfoIndex < parameterInfo.length; parameterInfoIndex++) {
         // ClassGraph returns an empty list here, not null; requireNonNull bridges its unannotated API into our
         // default.
         final var annotations = Objects.requireNonNull(parameterInfo[parameterInfoIndex].getAnnotationInfo());
         // For nested classes the value annotation is attached to the final suffix. Do not search enclosing segments
         // or
         // generic arguments: those annotations do not qualify the parameter value represented by the marker below.
         final var parameterType = parameterInfo[parameterInfoIndex].getTypeSignatureOrTypeDescriptor();
         final var typeAnnotations = getTopLevelValueTypeAnnotationInfo(parameterType);
         final String nullMarker;
         // Keep the same precedence as return values and fields if malformed metadata declares both contracts.
         if (hasNullableAnnotation(annotations, typeAnnotations)) {
            nullMarker = "0";
         } else if (hasNonNullAnnotation(annotations, typeAnnotations)) {
            nullMarker = "1";
         } else {
            continue;
         }

         // Explicit parameter contracts override a heuristic or template at that position without discarding
         // independent annotations.
         final int signatureParameterIndex = parameterInfoIndex - parameterInfoOffset;
         annotatedSignature.value = annotateMethodParameter(annotatedSignature.value, signatureParameterIndex, nullMarker);
      }
   }

   private static void applyInferredParameterAnnotations(final ValueWithComment annotatedSignature, final MethodInfo methodInfo,
         final BytecodeAnalyzer bytecodeAnalyzer) {
      final int parameterInfoOffset = determineParameterInfoOffset(methodInfo);
      /* Bytecode indexes include compiler-generated leading parameters, while a generic EEA signature can omit them.
       * Use the same ClassGraph alignment rule as explicit parameter annotations so both evidence sources address the
       * same parameter-use position. */
      final var parameterAnalysis = bytecodeAnalyzer.determineMethodParameterAnalysis(methodInfo);
      for (final int descriptorParameterIndex : parameterAnalysis.getDefinitelyNullableParameterIndexes()) {
         final int signatureParameterIndex = descriptorParameterIndex - parameterInfoOffset;
         if (signatureParameterIndex >= 0) {
            annotatedSignature.value = annotateMethodParameter(annotatedSignature.value, signatureParameterIndex, "0");
         }
      }
      for (final int descriptorParameterIndex : parameterAnalysis.getDefinitelyNonNullParameterIndexes()) {
         final int signatureParameterIndex = descriptorParameterIndex - parameterInfoOffset;
         if (signatureParameterIndex >= 0) {
            annotatedSignature.value = annotateMethodParameter(annotatedSignature.value, signatureParameterIndex, "1");
         }
      }
   }

   private static int determineParameterInfoOffset(final MethodInfo methodInfo) {
      int parameterInfoOffset = 0;
      if (methodInfo.getTypeSignature() != null) {
         // ClassGraph right-aligns generic types to descriptor arity and pads leading compiler-generated parameters
         // with null because those parameters do not exist in the generic EEA signature.
         final var parameterInfo = methodInfo.getParameterInfo();
         while (parameterInfoOffset < parameterInfo.length && parameterInfo[parameterInfoOffset].getTypeSignature() == null) {
            parameterInfoOffset++;
         }
      }
      return parameterInfoOffset;
   }

   private static @Nullable BitSet mapPolyNullDependencyPositions(final String originalSignature, final MethodInfo methodInfo,
         final MethodReturnAnalysis returnAnalysis) {
      final var dependencyPositions = new BitSet();
      final int parameterInfoOffset = determineParameterInfoOffset(methodInfo);
      for (final int descriptorParameterIndex : returnAnalysis.getNullDependentParameterIndexes()) {
         final int signatureParameterIndex = descriptorParameterIndex - parameterInfoOffset;
         if (signatureParameterIndex < 0)
            // A compiler-generated parameter omitted from the generic signature cannot safely drive an EEA refinement.
            return null;

         final int parameterStart = findMethodParameterTypeStart(originalSignature, signatureParameterIndex);
         final char typeMarker = originalSignature.charAt(parameterStart);
         if (typeMarker != 'L' && typeMarker != 'T' && typeMarker != '[')
            // The dependency analysis should only produce references, but declining here keeps malformed metadata safe.
            return null;
         dependencyPositions.set(parameterStart + 1);
      }
      return dependencyPositions.isEmpty() ? null : dependencyPositions;
   }

   private static String annotateMethodParameter(final String signature, final int parameterIndex, final String nullMarker) {
      final int parameterStart = findMethodParameterTypeStart(signature, parameterIndex);
      final char typeMarker = signature.charAt(parameterStart);
      if (typeMarker != 'L' && typeMarker != 'T' && typeMarker != '[')
         // EEA cannot express nullness for primitive values even if a declaration annotation permits that target.
         return signature;

      // Top-level EEA null markers follow the reference token (L, T, or the first [), not nested
      // component/type-argument tokens.
      final int annotationIndex = parameterStart + 1;
      if (signature.charAt(annotationIndex) == '0' || signature.charAt(annotationIndex) == '1')
         return signature.substring(0, annotationIndex) + nullMarker + signature.substring(annotationIndex + 1);
      return insert(signature, annotationIndex, nullMarker);
   }

   private static int findMethodParameterTypeStart(final String signature, final int parameterIndex) {
      int parameterStart = signature.indexOf('(') + 1;
      if (parameterStart == 0)
         throw new IllegalArgumentException("Not a method signature: " + signature);

      for (int i = 0; i < parameterIndex; i++) {
         if (parameterStart >= signature.length() || signature.charAt(parameterStart) == ')')
            throw new IllegalArgumentException("Parameter " + parameterIndex + " not found in method signature: " + signature);
         parameterStart = skipTypeSignature(signature, parameterStart);
      }
      if (parameterStart >= signature.length() || signature.charAt(parameterStart) == ')')
         throw new IllegalArgumentException("Parameter " + parameterIndex + " not found in method signature: " + signature);
      return parameterStart;
   }

   private static char[] extractNullAnnotations(final String originalSignature, final String annotatedSignature) {
      // Marker insertions shift all later string indexes. Align to the raw signature so every marker is represented
      // by
      // the stable gap before an original character, including markers inside nested generic types.
      final var annotations = new char[originalSignature.length() + 1];
      int originalIndex = 0;
      for (int annotatedIndex = 0; annotatedIndex < annotatedSignature.length(); annotatedIndex++) {
         final char annotatedChar = annotatedSignature.charAt(annotatedIndex);
         if (originalIndex < originalSignature.length() && annotatedChar == originalSignature.charAt(originalIndex)) {
            originalIndex++;
         } else if (annotatedChar == ExternalAnnotationProvider.NULLABLE || annotatedChar == ExternalAnnotationProvider.NONNULL) {
            if (annotations[originalIndex] != 0)
               throw new IllegalArgumentException("Multiple null annotations at index " + originalIndex + " in signature: "
                     + annotatedSignature);
            annotations[originalIndex] = annotatedChar;
         } else
            throw new IllegalArgumentException("Annotated signature does not match original signature [" + originalSignature + "]: "
                  + annotatedSignature);
      }
      if (originalIndex != originalSignature.length())
         throw new IllegalArgumentException("Annotated signature does not match original signature [" + originalSignature + "]: "
               + annotatedSignature);
      return annotations;
   }

   private static String overlayNullAnnotations(final String originalSignature, final String baseAnnotatedSignature,
         final String overridingAnnotatedSignature) {
      final char[] baseAnnotations = extractNullAnnotations(originalSignature, baseAnnotatedSignature);
      final char[] overridingAnnotations = extractNullAnnotations(originalSignature, overridingAnnotatedSignature);
      for (int i = 0; i <= originalSignature.length(); i++) {
         // A locally generated marker owns only its exact type position; missing local markers continue to inherit
         // the
         // current parent marker at that position. Aligning both inputs to the raw signature also handles nested
         // types.
         baseAnnotations[i] = overridingAnnotations[i] == 0 ? baseAnnotations[i] : overridingAnnotations[i];
      }
      return applyNullAnnotations(originalSignature, baseAnnotations);
   }

   private static String applyNullAnnotations(final String originalSignature, final char[] annotations) {
      final var result = new StringBuilder(originalSignature.length() + annotations.length);
      for (int i = 0; i <= originalSignature.length(); i++) {
         if (annotations[i] != 0) {
            result.append(annotations[i]);
         }
         if (i < originalSignature.length()) {
            result.append(originalSignature.charAt(i));
         }
      }
      return result.toString();
   }

   private static String removeMethodReturnNullAnnotation(final String originalSignature, final String annotatedSignature) {
      final int returnTypeStart = originalSignature.lastIndexOf(')') + 1;
      if (returnTypeStart <= 0 || returnTypeStart >= originalSignature.length())
         throw new IllegalArgumentException("Not a method signature: " + originalSignature);

      final char returnTypeMarker = originalSignature.charAt(returnTypeStart);
      if (returnTypeMarker != 'L' && returnTypeMarker != 'T' && returnTypeMarker != '[')
         return annotatedSignature;

      final char[] annotations = extractNullAnnotations(originalSignature, annotatedSignature);
      // PolyNull evidence deliberately leaves only the returned reference unqualified. Parameter, component, and
      // type-argument
      // markers are independent contracts and must survive regeneration.
      annotations[returnTypeStart + 1] = 0;
      return applyNullAnnotations(originalSignature, annotations);
   }

   private static int skipTypeSignature(final String signature, final int typeStart) {
      switch (signature.charAt(typeStart)) {
         case '[': {
            int componentStart = typeStart + 1;
            if (signature.charAt(componentStart) == '0' || signature.charAt(componentStart) == '1') {
               componentStart++;
            }
            return skipTypeSignature(signature, componentStart);
         }
         case 'L': {
            int typeArgumentDepth = 0;
            for (int i = typeStart + 1; i < signature.length(); i++) {
               switch (signature.charAt(i)) {
                  case '<':
                     typeArgumentDepth++;
                     break;
                  case '>':
                     typeArgumentDepth--;
                     break;
                  case ';':
                     // Semicolons inside type arguments terminate nested types, not the parameter's top-level
                     // class type.
                     if (typeArgumentDepth == 0)
                        return i + 1;
                     break;
                  default:
                     break;
               }
            }
            break;
         }
         case 'T': {
            final int typeEnd = signature.indexOf(';', typeStart + 1);
            if (typeEnd >= 0)
               return typeEnd + 1;
            break;
         }
         case 'B':
         case 'C':
         case 'D':
         case 'F':
         case 'I':
         case 'J':
         case 'S':
         case 'Z':
            return typeStart + 1;
         default:
            break;
      }
      throw new IllegalArgumentException("Invalid type at index " + typeStart + " in method signature: " + signature);
   }

   private static @Nullable ValueWithComment findTemplateAnnotatedSignature(final EEAFile.ClassMember member, final ClassInfo classInfo) {
      final var templates = new ArrayList<EEAFile>();
      if (isThrowable(classInfo)) {
         templates.add(TEMPLATE_THROWABLE); // to inherit constructor parameter annotations
      }
      templates.add(TEMPLATE_EXTERNALIZABLE);
      templates.add(TEMPLATE_SERIALIZABLE);
      // Object is a real inheritance source resolved after input layering, not declaration-local template evidence.

      for (final EEAFile template : templates) {
         final ClassMember matchingMember = template.findMatchingClassMember(member);
         if (matchingMember != null && matchingMember.hasNullAnnotations())
            return matchingMember.annotatedSignature;
      }
      return null;
   }

   private static GeneratedMemberEvidence computeHeuristicAnnotatedSignature(final EEAFile.ClassMember member, final ClassInfo classInfo,
         final ClassMemberInfo memberInfo, final BytecodeAnalyzer bytecodeAnalyzer) {

      final @Nullable MethodReturnAnalysis returnAnalysis;
      if (memberInfo instanceof MethodInfo) {
         final MethodReturnKind returnKind = ClassGraphUtils.getMethodReturnKind((MethodInfo) memberInfo);
         returnAnalysis = returnKind == MethodReturnKind.ARRAY || returnKind == MethodReturnKind.OBJECT //
               ? bytecodeAnalyzer.determineMethodReturnAnalysis((MethodInfo) memberInfo) //
               : null;
      } else {
         returnAnalysis = null;
      }

      final ValueWithComment annotatedSignature = computePrimaryHeuristicAnnotatedSignature(member, classInfo, memberInfo, bytecodeAnalyzer,
         returnAnalysis);
      if (memberInfo instanceof MethodInfo) {
         // Bytecode parameter evidence is independent of return, listener, and template evidence, so overlay it instead
         // of making it compete with the primary heuristic's early-return decision.
         applyInferredParameterAnnotations(annotatedSignature, (MethodInfo) memberInfo, bytecodeAnalyzer);
      }
      final @Nullable BitSet polyNullDependencyPositions = returnAnalysis != null //
            && returnAnalysis.getNullability() == Nullability.POLY_NULL //
            && EEAFile.hasPolyNullContractMarker(annotatedSignature.comment) //
                  ? mapPolyNullDependencyPositions(member.originalSignature.value, (MethodInfo) memberInfo, returnAnalysis) //
                  : null;
      return new GeneratedMemberEvidence(annotatedSignature, polyNullDependencyPositions);
   }

   private static ValueWithComment computePrimaryHeuristicAnnotatedSignature(final EEAFile.ClassMember member, final ClassInfo classInfo,
         final ClassMemberInfo memberInfo, final BytecodeAnalyzer bytecodeAnalyzer, final @Nullable MethodReturnAnalysis returnAnalysis) {

      // analyzing a method
      if (memberInfo instanceof MethodInfo) {
         final MethodInfo methodInfo = (MethodInfo) memberInfo;

         final var returnKind = ClassGraphUtils.getMethodReturnKind(methodInfo);
         if (returnKind == MethodReturnKind.ARRAY || returnKind == MethodReturnKind.OBJECT) {

            assert returnAnalysis != null;
            final Nullability returnTypeNullability = returnAnalysis.getNullability();
            // JSpecify and other TYPE_USE contracts live on the result value rather than on MethodInfo itself. The
            // helper
            // selects a nested class's final suffix without treating an enclosing segment or generic argument as
            // the result.
            final var returnTypeAnnotations = getTopLevelValueTypeAnnotationInfo(methodInfo.getTypeSignatureOrTypeDescriptor()
               .getResultType());

            /*
             * mark the return value of a method as @Nullable if the byte code analysis of the method body
             * determines it returns null values or the method is annotated with a known nullable annotation.
             */
            if (returnTypeNullability == Nullability.DEFINITELY_NULL //
                  || hasNullableAnnotation(methodInfo.getAnnotationInfo(), returnTypeAnnotations))
               // ()Ljava/lang/String -> ()L0java/lang/String;
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2,
                  "0"));

            /* A declared non-null result narrows a parameter-dependent bytecode summary. For example, the parameter on
             * the nullable dependency path may itself be declared non-null, so inferred PolyNull must not weaken the
             * explicit result contract. */
            if (hasNonNullAnnotation(methodInfo.getAnnotationInfo(), returnTypeAnnotations))
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2,
                  "1"));

            /*
             * record PolyNull evidence if the method returns null only when it was invoked with a null argument
             */
            if (returnTypeNullability == Nullability.POLY_NULL)
               /*
                * PolyNull has no 0/1 marker in the signature, so its source comment must record both the evidence
                * and its generated ownership.
                */
               return new ValueWithComment(member.originalSignature.value, "# " + EEAFile.MARKER_GENERATED + "(PolyNull)");

            /*
             * mark the return value of a method as @NonNull if:
             * - bytecode analysis proves every return value non-null, or
             * - the method is static, has no arguments, and is named "getInstance"
             */
            if (returnTypeNullability == Nullability.NEVER_NULL //
                  || methodInfo.getName().equals("getInstance") && methodInfo.isStatic() && methodInfo.getParameterInfo().length == 0)
               // ()Ljava/lang/String -> ()L1java/lang/String;
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2,
                  "1"));

            /*
             * JSR-305 When.UNKNOWN expresses no nullness contract, so it deliberately leaves this last-resort type
             * convention enabled. Only concrete nullable or non-null evidence above suppresses the fallback.
             */
            if (returnTypeNullability == Nullability.UNKNOWN //
                  && methodInfo.getTypeDescriptor().getResultType() instanceof ClassRefTypeSignature //
                  && Optional.class.getName().equals(((ClassRefTypeSignature) methodInfo.getTypeDescriptor().getResultType())
                     .getFullyQualifiedClassName()))
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2,
                  "1"));

            /*
             * mark the return values of builder methods as @NonNull.
             */
            if (classInfo.getName().endsWith("Builder") //
                  && !methodInfo.isStatic() // non-static
                  && methodInfo.isPublic() //
                  && methodInfo.getTypeDescriptor().getResultType() instanceof ClassRefTypeSignature //
                  && (methodInfo.getName().equals("build") && methodInfo.getParameterInfo().length == 0 //
                        || Objects.equals(((ClassRefTypeSignature) methodInfo.getTypeDescriptor().getResultType()).getClassInfo(),
                           classInfo)))
               // (...)Lcom/example/MyBuilder -> (...)L1com/example/MyBuilder;
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2,
                  "1"));

         } else {
            /*
             * mark the parameter of Comparable#compareTo(Object) as @NonNull.
             */
            if (methodInfo.getName().equals("compareTo") //
                  && classInfo.implementsInterface("java.lang.Comparable") //
                  && !methodInfo.isStatic() // non-static
                  && member.originalSignature.value.endsWith(")I") // returns Integer
                  && methodInfo.isPublic() //
                  && methodInfo.getParameterInfo().length == 1 // only 1 parameter
                  && methodInfo.getParameterInfo()[0].getTypeDescriptor() instanceof ClassRefTypeSignature)
               // (Lcom/example/Entity;)I -> (L1com/example/Entity;)I
               return new ValueWithComment(annotateMethodParameter(member.originalSignature.value, 0, "1"));

            /*
             * mark the parameter of single-parameter void methods as @NonNull,
             * if the class name matches "*Listener" and the parameter type name matches "*Event"
             */
            if (classInfo.isInterface() //
                  && classInfo.getName().endsWith("Listener") //
                  && !methodInfo.isStatic() // non-static
                  && member.originalSignature.value.endsWith(")V") // returns void
                  && methodInfo.getParameterInfo().length == 1 // only 1 parameter
                  && methodInfo.getParameterInfo()[0].getTypeDescriptor().toString().endsWith("Event"))
               // (Ljava/lang/String;)V -> (L1java/lang/String;)V
               return new ValueWithComment(annotateMethodParameter(member.originalSignature.value, 0, "1"));

            /*
             * mark the parameter of single-parameter methods as @NonNull
             * with signature matching: void (add|remove)*Listener(*Listener)
             */
            if (!methodInfo.isStatic() // non-static
                  && (methodInfo.getName().startsWith("add") || methodInfo.getName().startsWith("remove")) //
                  && methodInfo.getName().endsWith("Listener") //
                  && member.originalSignature.value.endsWith(")V") // returns void
                  && methodInfo.getParameterInfo().length == 1 // only 1 parameter
                  && methodInfo.getParameterInfo()[0].getTypeDescriptor().toString().endsWith("Listener"))
               // This heuristic proves a parameter precondition, not that every legal substitution for T is
               // non-null.
               // Annotating <1T...> or its bound (<T::L1...>) would reject an explicit @Nullable T even when the
               // supplied value is known non-null.
               // <T::Lcom/example/MyListener;>(TT;)V -> <T::Lcom/example/MyListener;>(T1T;)V
               // <X:Ljava/lang/Object;T::Lcom/example/MyListener;>(TT;)V ->
               // <X:Ljava/lang/Object;T::Lcom/example/MyListener;>(T1T;)V
               return new ValueWithComment(annotateMethodParameter(member.originalSignature.value, 0, "1"));

         }
      }

      // analyzing a field
      if (memberInfo instanceof FieldInfo) {
         final FieldInfo fieldInfo = (FieldInfo) memberInfo;
         // For a nested class field, the final suffix is the value type; enclosing segments and arguments are
         // independent.
         final var fieldTypeAnnotations = getTopLevelValueTypeAnnotationInfo(fieldInfo.getTypeSignatureOrTypeDescriptor());
         // An explicit field contract remains authoritative when initializer analysis disagrees or has insufficient
         // evidence.
         if (hasNullableAnnotation(fieldInfo.getAnnotationInfo(), fieldTypeAnnotations))
            return new ValueWithComment(insert(member.originalSignature.value, 1, "0"));

         if (hasNonNullAnnotation(fieldInfo.getAnnotationInfo(), fieldTypeAnnotations) //
               || bytecodeAnalyzer.isDefinitelyNonNullStaticField(fieldInfo))
            /*
             * Finality only freezes an initializer's result; it does not prevent that result from being null.
             * Missing initializer proof therefore remains unspecified rather than becoming a false non-null
             * contract.
             */
            // Ljava/lang/String; -> L1java/lang/String;
            return new ValueWithComment(insert(member.originalSignature.value, 1, "1"));
      }

      return new ValueWithComment(member.originalSignature.value);
   }

   protected static EEAFile computeEEAFile(final ClassInfo classInfo) {
      final EEAFile eeaFile = computeEEAFile(classInfo, new BytecodeAnalyzer(classInfo), null);
      applyObjectDefaults(eeaFile);
      return eeaFile;
   }

   private static EEAFile computeEEAFile(final ClassInfo classInfo, final BytecodeAnalyzer bytecodeAnalyzer,
         final @Nullable Map<ClassMember, GeneratedMemberEvidence> generatedMemberEvidence) {
      LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());

      final var eeaFile = new EEAFile(classInfo.getName());

      final var fields = classInfo.getDeclaredFieldInfo();
      final var methods = classInfo.getDeclaredMethodAndConstructorInfo();

      final String typeSigStr = classInfo.getTypeSignatureStr();
      if (typeSigStr != null) {

         // class signature
         final String superTypesSigStr;
         if (typeSigStr.startsWith("<")) {
            final String typeParams = substringBetweenBalanced(typeSigStr, '<', '>');
            if (typeParams != null) {
               eeaFile.classHeader.originalSignature.value = '<' + typeParams + '>';
               eeaFile.classHeader.annotatedSignature.value = eeaFile.classHeader.originalSignature.value;

               superTypesSigStr = typeSigStr.substring(typeParams.length() + 2);
            } else {
               superTypesSigStr = typeSigStr;
            }
         } else {
            superTypesSigStr = typeSigStr;
         }

         final Function<String, List<String>> superTypesSigSplitter = signature -> {
            final String[] chunks = signature.split(";");
            final var result = new ArrayList<String>();
            final var sb = new StringBuilder();
            for (final String chunk : chunks) {
               sb.append(chunk);

               if (sb.length() > 0 && countOccurrences(sb, '<') == countOccurrences(sb, '>')) {
                  sb.deleteCharAt(0); // remove the leading L of e.g. Ljava/lang/Object;
                  result.add(sb.toString());
                  sb.setLength(0);
               } else {
                  sb.append(';');
               }
            }
            return result;
         };

         // super signatures
         for (final String superTypeSig : superTypesSigSplitter.apply(superTypesSigStr)) {
            if (!superTypeSig.contains("<")) {
               continue;
            }
            final String superTypeName = superTypeSig.split("<", 2)[0];
            final String superTypeParams = '<' + substringBetweenBalanced(superTypeSig, '<', '>') + '>';
            assert superTypeParams != null;
            eeaFile.superTypes.add(new ClassMember(new ValueWithComment(superTypeName), new ValueWithComment(superTypeParams)));
         }
      }
      eeaFile.addEmptyLine();

      // static fields
      for (final FieldInfo f : getStaticFields(fields)) {
         if (classInfo.isEnum()) {
            // omit enum values as they are always treated as non-null by Eclipse compiler
            if (f.isFinal() && startsWith(classInfo.getTypeSignatureStr(), "Ljava/lang/Enum<" + f.getTypeDescriptorStr() + ">;")) {
               continue;
            }
         }

         final var member = eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         setGeneratedMemberEvidence(member, computeGeneratedMemberEvidence(member, classInfo, f, bytecodeAnalyzer),
            generatedMemberEvidence);
      }
      eeaFile.addEmptyLine();

      // static methods
      for (final MethodInfo m : getStaticMethods(methods)) {
         final var member = eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr());
         setGeneratedMemberEvidence(member, computeGeneratedMemberEvidence(member, classInfo, m, bytecodeAnalyzer),
            generatedMemberEvidence);
      }
      eeaFile.addEmptyLine();

      // instance fields
      for (final FieldInfo f : getInstanceFields(fields)) {
         final var member = eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         setGeneratedMemberEvidence(member, computeGeneratedMemberEvidence(member, classInfo, f, bytecodeAnalyzer),
            generatedMemberEvidence);
      }
      eeaFile.addEmptyLine();

      // instance methods
      for (final MethodInfo m : getInstanceMethods(methods)) {
         final var member = eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         setGeneratedMemberEvidence(member, computeGeneratedMemberEvidence(member, classInfo, m, bytecodeAnalyzer),
            generatedMemberEvidence);
      }
      return eeaFile;
   }

   private static void setGeneratedMemberEvidence(final ClassMember member, final GeneratedMemberEvidence evidence,
         final @Nullable Map<ClassMember, GeneratedMemberEvidence> generatedMemberEvidence) {
      member.annotatedSignature = evidence.annotatedSignature;
      if (generatedMemberEvidence != null) {
         // Stored inputs mutate the member during layering; retain an independent snapshot of current analyzer evidence.
         generatedMemberEvidence.put(member, evidence.copy());
      }
   }

   private static EEAFile createObjectTemplate() {
      // Object is also a generated output file, so layering must mutate a private copy rather than the shared defaults.
      final var template = new EEAFile(Object.class.getName());
      template.applyAnnotationsAndCommentsFrom(TEMPLATE_OBJECT, true, true);
      return template;
   }

   /**
    * Instantiates {@link EEAFile} instances for all classes found in classpath in the given package or sub-packages.
    *
    * @param rootPackageName name the of root package to scan for classes
    * @throws IllegalArgumentException if no class was found
    */
   protected static SortedMap<ClassInfo, EEAFile> computeEEAFiles(final String rootPackageName, final Predicate<ClassInfo> filter) {
      final var eeaFiles = computeEEAFiles(rootPackageName, filter, null, createObjectTemplate());
      eeaFiles.values().forEach(EEAGenerator::applyObjectDefaults);
      return eeaFiles;
   }

   private static SortedMap<ClassInfo, EEAFile> computeEEAFiles(final String rootPackageName, final Predicate<ClassInfo> filter,
         final @Nullable Map<ClassMember, GeneratedMemberEvidence> generatedMemberEvidence, final EEAFile objectTemplate) {
      final var result = new TreeMap<ClassInfo, EEAFile>();

      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .enableSystemJarsAndModules() //
         .acceptPackages(rootPackageName) //
         .scan() //
      ) {
         final List<ClassInfo> classes = scanResult.getAllClasses();
         if (classes.isEmpty())
            throw new IllegalArgumentException("No classes found for package [" + rootPackageName + "] on classpath");

         // Scan-scoped resolvers keep classpath resolution consistent and avoid re-analyzing shared external evidence.
         final var staticFieldResolver = new StaticFieldResolver(scanResult);
         final var methodSummaryResolver = new MethodSummaryResolver(staticFieldResolver);

         for (final ClassInfo classInfo : classes) {
            if (classInfo.getName().equals("java.lang.AbstractStringBuilder")) { // https://github.com/vegardit/no-npe/issues/257
               LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());
               final var eeaFile = computeEEAFile(classInfo, new BytecodeAnalyzer(classInfo, methodSummaryResolver),
                  generatedMemberEvidence);
               result.put(classInfo, eeaFile);
               continue;
            }

            // skip uninteresting classes
            if (hasPackageVisibility(classInfo) || classInfo.isPrivate() || classInfo.isAnonymousInnerClass()) {
               LOG.log(Level.DEBUG, "Ignoring non-accessible classes [{0}]...", classInfo.getName());
               continue;
            }

            if (!filter.test(classInfo)) {
               LOG.log(Level.DEBUG, "Ignoring class excluded by filter [{0}]...", classInfo.getName());
               continue;
            }

            LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());
            final var eeaFile = computeEEAFile(classInfo, new BytecodeAnalyzer(classInfo, methodSummaryResolver), generatedMemberEvidence);
            result.put(classInfo, eeaFile);
         }
      }

      // TODO workaround for https://github.com/classgraph/classgraph/issues/703
      if ("java".equals(rootPackageName) || rootPackageName.startsWith("java.lang")) {
         result.putIfAbsent(OBJECT_CLASS_INFO, objectTemplate);
      }
      return result;
   }

   /**
    * Scans the classpath for classes of {@link Config#packages}, applies EEAs from files in {@link Config#inputDirs} and
    * {@link Config#inputDirsExtra}, and creates updated EEA files in {@link Config#outputDir}.
    *
    * @return number of updated and removed files
    * @throws IllegalArgumentException if no class was found
    */
   public static long generateEEAFiles(final Config cfg) throws IOException {
      final var saveOptions = Arrays.stream(new @Nullable SaveOption[] { //
         SaveOption.REPLACE_EXISTING, //
         cfg.deleteIfEmpty ? SaveOption.DELETE_IF_EMPTY : null, //
         cfg.omitRedundantAnnotatedSignatures ? SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES : null, //
         cfg.omitClassMembersWithoutNullAnnotations ? SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE : null //
      }).filter(Objects::nonNull).collect(Collectors.toSet());

      final var eeaFiles = new HashMap<ClassInfo, EEAFile>();
      final var generatedMemberEvidence = new HashMap<ClassMember, GeneratedMemberEvidence>();
      // All packages and inheritance in this invocation share its working Object contract; other invocations cannot see it.
      final EEAFile objectTemplate = createObjectTemplate();
      final List<Path> inputDirs = getEffectiveInputDirs(cfg);
      final @Nullable Path localInputDir = cfg.inputDirs.isEmpty() ? null : cfg.inputDirs.get(0);

      long totalModifications = 0;
      for (final String packageName : cfg.packages) {
         LOG.log(Level.INFO, "Scanning EEA files of package [{0}]...", packageName);

         // create EEAFile instances based on class signatures found on classpath
         final Map<ClassInfo, EEAFile> eeaFilesOfPackage = computeEEAFiles(packageName, cfg.classFilter, generatedMemberEvidence,
            objectTemplate);
         LOG.log(Level.INFO, "Found {0} types on classpath.", eeaFilesOfPackage.size());

         // extend computed EEAFiles with annotations found on matching *.eea files in input dirs
         for (final var computedEEAEntry : eeaFilesOfPackage.entrySet()) {
            final EEAFile computedEEAFile = computedEEAEntry.getValue();
            /* The java.lang.Object ClassGraph workaround inserts a template EEA without scanning its bytecode. Record
             * that template here while preserving richer dependency evidence captured for normally scanned members. */
            computedEEAFile.getClassMembers().forEach(member -> generatedMemberEvidence.computeIfAbsent(member,
               ignored -> new GeneratedMemberEvidence(member.annotatedSignature.clone(), null)));
            final var layeredInputSignatures = new HashMap<ClassMember, ValueWithComment>();
            for (final Path inputDir : inputDirs) {
               final var existingEEAFile = EEAFile.loadIfExists(inputDir, computedEEAFile.classHeader.name.value);
               if (existingEEAFile != null) {
                  final boolean upstreamInput = !inputDir.equals(localInputDir);
                  computedEEAFile.applyAnnotationsAndCommentsFrom(existingEEAFile, true, false);
                  existingEEAFile.getClassMembers().forEach(inputMember -> {
                     ClassMember computedMember = computedEEAFile.findMatchingClassMember(inputMember);
                     if (computedMember == null && upstreamInput && inputMember.hasImportedMarker()) {
                        // Imported contracts retain the predecessor's @Keep compatibility promise even when the
                        // member is absent from the scanned successor version.
                        computedMember = inputMember.clone();
                        computedEEAFile.addMember(computedMember);
                     }
                     if (computedMember == null || !hasLayeredInputContract(inputMember))
                        return;

                     final ValueWithComment currentInput = prepareLayeredInputContract(inputMember.annotatedSignature, upstreamInput);
                     if (currentInput == null)
                        return;
                     final ValueWithComment earlierInput = layeredInputSignatures.get(computedMember);
                     layeredInputSignatures.put(computedMember, earlierInput == null //
                           ? currentInput //
                           : mergeLayeredInputContract(computedMember.originalSignature.value, earlierInput, currentInput));
                  });
               }
            }
            computedEEAFile.getClassMembers().forEach(member -> {
               final ValueWithComment layeredInput = layeredInputSignatures.get(member);
               if (layeredInput != null) {
                  member.annotatedSignature = layeredInput;
               } else {
                  final GeneratedMemberEvidence generatedEvidence = generatedMemberEvidence.get(member);
                  if (generatedEvidence != null) {
                     // An unconfirmed local @Imported value is a cache of upstream state, not independent manual
                     // evidence. Reset it before normal bytecode reconciliation when its source disappeared.
                     member.annotatedSignature = generatedEvidence.annotatedSignature.clone();
                  }
               }
            });

            computedEEAFile.getClassMembers().forEach(member -> {
               final GeneratedMemberEvidence generatedEvidence = generatedMemberEvidence.get(member);
               if (generatedEvidence == null || member.hasProtectedContractMarker())
                  return;

               final boolean hasRelationshipMarker = getRelationshipParent(member.annotatedSignature.comment) != null;
               if (!hasRelationshipMarker) {
                  // Relationship-owned contracts are deferred until the current parent EEA is known. Resetting
                  // them here
                  // would discard the parent identity needed to distinguish an unavailable EEA from an empty
                  // contract.
                  // Both generation modes may own other unprotected local contracts; additive mode decides below
                  // whether the
                  // proposed contract retains all stored evidence.
                  resetGeneratedAnnotatedSignature(member, generatedEvidence, false, cfg.generationMode);
               }
            });
         }

         eeaFiles.putAll(eeaFilesOfPackage);

         // remove obsolete files
         final var pkgDeletions = new LongAdder();
         final var eeaFilesByPath = new HashMap<Path, EEAFile>();
         eeaFilesOfPackage.values().forEach(f -> eeaFilesByPath.put(f.relativePath, f));
         forEachFileWithExtension(cfg.outputDir.resolve(packageName.replace('.', File.separatorChar)),
            ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, path -> {
               final Path relativePath = cfg.outputDir.relativize(path);
               if (!eeaFilesByPath.containsKey(relativePath)) {
                  LOG.log(Level.WARNING, "Removing obsolete annotation file [{0}]...", path.toAbsolutePath());
                  Files.delete(path);
                  pkgDeletions.increment();
               }
            });

         LOG.log(Level.INFO, "{0} EEA file(s) of package [{1}] updated or removed.", pkgDeletions.sum(), packageName);
         totalModifications += pkgDeletions.sum();
      }

      // will hold additional EEA files found in input dirs that are not part of the packages defined in {@link Config#packages}
      final var superEEAFiles = new HashMap<ClassInfo, EEAFile>();
      final Function<ClassInfo, @Nullable EEAFile> getSuperEEAFile = classInfo -> {
         EEAFile eeaFile = eeaFiles.get(classInfo);
         if (eeaFile != null)
            return eeaFile;
         eeaFile = superEEAFiles.get(classInfo);
         if (eeaFile != null)
            return eeaFile;

         for (final Path inputDir : inputDirs) {
            try {
               eeaFile = EEAFile.loadIfExists(inputDir, classInfo.getName().replace('.', '/'));
               if (eeaFile != null) {
                  superEEAFiles.put(classInfo, eeaFile);
                  return eeaFile;
               }
            } catch (final IOException ex) {
               throw new UncheckedIOException(ex);
            }
         }
         return null;
      };

      // determine inherited annotated signatures
      final var recomputeInheritance = new AtomicBoolean(true);
      while (recomputeInheritance.get()) {
         recomputeInheritance.set(false);
         eeaFiles.forEach((classInfo, eeaFile) -> {
            if (classInfo == OBJECT_CLASS_INFO)
               return;

            final var superClasses = new ArrayList<>(classInfo.getSuperclasses());
            superClasses.add(OBJECT_CLASS_INFO);
            final var interfaces = classInfo.getInterfaces();

            eeaFile.getClassMembers().forEach(member -> {
               switch (member.getType()) {
                  case CONSTRUCTOR:
                     return; // exclude constructors
                  case FIELD:
                     // A declared subclass field hides a matching superclass field; it does not override its nullability contract.
                     return;
                  case METHOD:
                     if (isStaticMethod(classInfo, member.name.value, member.originalSignature.value))
                        return; // exclude static methods
                     break;
               }

               ValueWithComment inheritableAnnotatedSignature = null;
               ClassInfo inheritedFrom = null;
               final String previousRelationshipParent = getRelationshipParent(member.annotatedSignature.comment);
               /*
                * Resolve the named parent before selecting the first usable contract below. That scan can stop
                * early, but an earlier alternative parent must not hide that the stored relationship is still an
                * ancestor.
                */
               final ClassInfo previousRelationshipAncestor = previousRelationshipParent == null //
                     ? null //
                     : findAncestor(classInfo, previousRelationshipParent);
               final boolean previousRelationshipParentIsAncestor = previousRelationshipAncestor != null;
               final boolean previousRelationshipParentEEAAvailable = previousRelationshipAncestor != null //
                     && (OBJECT_CLASS_INFO.getName().equals(previousRelationshipAncestor.getName()) //
                           || getSuperEEAFile.apply(previousRelationshipAncestor) != null);

               /*
                * 1) scan super classes (which have precedence) for inheritable annotated signature
                */
               for (final ClassInfo superClass : superClasses) {
                  // The synthetic parent and the Object output must share the fully layered contract.
                  final EEAFile superClassEEA = superClass == OBJECT_CLASS_INFO //
                        ? objectTemplate
                        : getSuperEEAFile.apply(superClass);
                  if (superClassEEA == null) {
                     continue;
                  }
                  final var superClassMember = superClassEEA.findMatchingClassMember(member.name.value, member.originalSignature.value);
                  if (superClassMember != null && hasInheritableContractEvidence(superClass, superClassMember, getSuperEEAFile)) {
                     inheritableAnnotatedSignature = superClassMember.annotatedSignature;
                     inheritedFrom = superClass;
                     break;
                  }
               }

               /*
                * 2) scan interfaces if no inheritable annotated signature was found in any super class
                */
               boolean hasConflictingIFaceAnnotatedSignatures = false;
               if (inheritableAnnotatedSignature == null) {
                  final Map<ClassInfo, ValueWithComment> interfaceContracts = new LinkedHashMap<>();
                  for (final ClassInfo iface : interfaces) {
                     final EEAFile ifaceEEA = getSuperEEAFile.apply(iface);
                     if (ifaceEEA == null) {
                        continue;
                     }
                     final var ifaceMember = ifaceEEA.findMatchingClassMember(member.name.value, member.originalSignature.value);
                     if (ifaceMember == null) {
                        continue;
                     }

                     if (hasInheritableContractEvidence(iface, ifaceMember, getSuperEEAFile)) {
                        interfaceContracts.put(iface, ifaceMember.annotatedSignature);
                     }
                  }

                  for (final var interfaceContract : interfaceContracts.entrySet()) {
                     final ClassInfo iface = interfaceContract.getKey();
                     /*
                      * A subinterface redeclaration is the more specific contract. Ignore only its transitive
                      * ancestors; unrelated maximal interfaces still conflict because neither contract overrides
                      * the other.
                      */
                     final boolean supersededBySubinterface = interfaceContracts.keySet().stream().anyMatch(candidate -> candidate != iface
                           && candidate.getInterfaces().contains(iface));
                     if (supersededBySubinterface) {
                        continue;
                     }

                     if (inheritableAnnotatedSignature == null) {
                        inheritableAnnotatedSignature = interfaceContract.getValue();
                        inheritedFrom = iface;
                     } else if (!inheritableAnnotatedSignature.value.equals(interfaceContract.getValue().value)) {
                        hasConflictingIFaceAnnotatedSignatures = true;
                        inheritableAnnotatedSignature = null;
                        break;
                     }
                  }
               }

               /*
                * 3) apply inheritable annotated signature if applicable
                */
               if (member.hasProtectedContractMarker())
                  return;

               final GeneratedMemberEvidence generatedEvidence = generatedMemberEvidence.get(member);
               final boolean hasGeneratedRelationshipMarker = previousRelationshipParent != null;
               final boolean canDiscardStoredRelationship = !previousRelationshipParentIsAncestor //
                     || previousRelationshipParentEEAAvailable;

               if (hasGeneratedRelationshipMarker && !canDiscardStoredRelationship)
                  /*
                   * The named parent is still an ancestor, but its contract is unavailable. Another ancestor
                   * cannot prove that the stored relationship is stale, so preserve it until the named parent can
                   * be read.
                   */
                  return;

               if (hasConflictingIFaceAnnotatedSignatures) {
                  // Conflicting parents provide no safe merge base. A stored generator relationship therefore
                  // falls
                  // back to current local inference instead of retaining annotations from a formerly selected
                  // parent.
                  if (hasGeneratedRelationshipMarker && canDiscardStoredRelationship && generatedEvidence != null //
                        && resetGeneratedAnnotatedSignature(member, generatedEvidence, true, cfg.generationMode)) {
                     recomputeInheritance.set(true);
                  }
                  return;
               }

               if (inheritableAnnotatedSignature == null) {
                  // A missing EEA is unknown, whereas an available parent without annotations is a current empty
                  // contract. Preserve the stored relationship only in the former case to avoid destructive
                  // guesses.
                  if (hasGeneratedRelationshipMarker && canDiscardStoredRelationship && generatedEvidence != null //
                        && resetGeneratedAnnotatedSignature(member, generatedEvidence, true, cfg.generationMode)) {
                     recomputeInheritance.set(true);
                  }
                  return;
               }

               assert inheritedFrom != null;
               final ValueWithComment currentLocalSignature = generatedEvidence == null //
                     ? new ValueWithComment(member.originalSignature.value) //
                     : generatedEvidence.annotatedSignature;
               final ReconciledContract currentRelationshipContract = createCurrentRelationshipContract(member.originalSignature.value,
                  inheritableAnnotatedSignature, currentLocalSignature);
               final ReconciledContract reconciledContract = reconcileContract(member.originalSignature.value, member.annotatedSignature,
                  currentRelationshipContract, cfg.generationMode, generatedEvidence == null //
                        ? null //
                        : generatedEvidence.polyNullDependencyPositions);
               if (inheritedFrom == OBJECT_CLASS_INFO && currentLocalSignature.comment.isEmpty()) {
                  // Object's explanation used to arrive with its early template default. Carry only that explanation
                  // now; parent protection and ownership must never become a local child decision.
                  final String explanation = getExplanatoryComment(inheritableAnnotatedSignature.comment);
                  if (!explanation.isEmpty() && getExplanatoryComment(reconciledContract.annotatedSignature.comment).isEmpty()) {
                     final ValueWithComment signature = reconciledContract.annotatedSignature;
                     // EEA comments include '#'; appending an explanation must not introduce a second comment delimiter.
                     signature.comment = signature.comment.isEmpty() ? explanation
                           : signature.comment + " " + explanation.substring(1).strip();
                  }
               }
               setRelationshipComment(reconciledContract.annotatedSignature, inheritableAnnotatedSignature.value.equals(
                  reconciledContract.annotatedSignature.value) ? EEAFile.MARKER_INHERITED : EEAFile.MARKER_OVERRIDES, inheritedFrom
                     .getName());
               setGeneratedOwnershipMarker(member.originalSignature.value, reconciledContract.annotatedSignature,
                  reconciledContract.ownership);
               if (applyGeneratedAnnotatedSignature(member, reconciledContract.annotatedSignature, cfg.generationMode)) {
                  recomputeInheritance.set(true);
               }
            });
         });
      }

      // save updated EEA files
      long updates = 0;
      for (final var computedEEAFile : eeaFiles.values()) {
         if (computedEEAFile.save(cfg.outputDir, saveOptions)) {
            updates++;
         }
      }
      LOG.log(Level.INFO, "{0} EEA file(s) updated.", updates);
      totalModifications += updates;

      return totalModifications;
   }

   /**
    * @return true if executing this method changes the value or comment of the target's annotated signature
    */
   private static boolean resetGeneratedAnnotatedSignature(final ClassMember target, final GeneratedMemberEvidence generatedEvidence,
         final boolean discardStoredRelationship, final GenerationMode generationMode) {
      final var currentContract = new ReconciledContract(generatedEvidence.annotatedSignature, createGeneratedOwnership(
         target.originalSignature.value, generatedEvidence.annotatedSignature));
      final ReconciledContract reconciledContract = reconcileContract(target.originalSignature.value, target.annotatedSignature,
         currentContract, generationMode, generatedEvidence.polyNullDependencyPositions);
      if (discardStoredRelationship) {
         // Positional evidence can remain useful after its old parent disappears, but the relationship itself
         // cannot.
         removeRelationshipMarker(reconciledContract.annotatedSignature);
      }
      setGeneratedOwnershipMarker(target.originalSignature.value, reconciledContract.annotatedSignature, reconciledContract.ownership);
      return applyGeneratedAnnotatedSignature(target, reconciledContract.annotatedSignature, generationMode);
   }

   private static boolean hasLayeredInputContract(final ClassMember member) {
      return member.hasNullAnnotations() || member.annotatedSignature.hasComment();
   }

   private static @Nullable ValueWithComment prepareLayeredInputContract(final ValueWithComment annotatedSignature,
         final boolean upstreamInput) {
      validateProtectionMarkers(annotatedSignature);
      final boolean imported = EEAFile.hasCommentMarker(annotatedSignature.comment, EEAFile.MARKER_IMPORTED);
      if (!upstreamInput && imported)
         // A local import is only a materialized upstream contract. It becomes current again only if an upstream
         // protected source confirms or refreshes it during this run.
         return null;
      if (upstreamInput && (imported || EEAFile.hasCommentMarker(annotatedSignature.comment, EEAFile.MARKER_KEEP)))
         return createImportedContract(annotatedSignature);
      return annotatedSignature.clone();
   }

   private static ValueWithComment createImportedContract(final ValueWithComment source) {
      final var imported = source.clone();
      final boolean hasPolyNull = EEAFile.hasPolyNullContractMarker(imported.comment);

      // Only the upstream protection and contract semantics cross the artifact boundary. Generator and relationship
      // ownership describe the source artifact and would misclassify the imported member in its successor.
      imported.comment = EEAFile.removeCommentMarker(imported.comment, EEAFile.MARKER_KEEP);
      imported.comment = EEAFile.removeCommentMarker(imported.comment, EEAFile.MARKER_IMPORTED);
      while (true) {
         final String commentWithoutGenerated = EEAFile.removeGeneratedMarker(imported.comment);
         if (commentWithoutGenerated.equals(imported.comment)) {
            break;
         }
         imported.comment = commentWithoutGenerated;
      }
      imported.comment = EEAFile.removeRelationshipMarker(imported.comment, EEAFile.MARKER_INHERITED);
      imported.comment = EEAFile.removeRelationshipMarker(imported.comment, EEAFile.MARKER_OVERRIDES);
      imported.comment = EEAFile.removeCommentMarker(imported.comment, EEAFile.MARKER_POLY_NULL);
      if (hasPolyNull) {
         // Imported protection adopts PolyNull as manual contract evidence; retaining generated ownership would make
         // the protected contract self-contradictory on a later run.
         prependCommentMarker(imported, EEAFile.MARKER_POLY_NULL);
      }
      prependCommentMarker(imported, EEAFile.MARKER_IMPORTED);
      return imported;
   }

   private static void validateProtectionMarkers(final ValueWithComment annotatedSignature) {
      if (EEAFile.hasCommentMarker(annotatedSignature.comment, EEAFile.MARKER_KEEP) //
            && EEAFile.hasCommentMarker(annotatedSignature.comment, EEAFile.MARKER_IMPORTED))
         throw new IllegalArgumentException("@Keep and @Imported cannot be combined in comment [" + annotatedSignature.comment + "]");
   }

   private static ValueWithComment mergeLayeredInputContract(final String originalSignature,
         final ValueWithComment earlierAnnotatedSignature, final ValueWithComment laterAnnotatedSignature) {
      if (EEAFile.hasCommentMarker(earlierAnnotatedSignature.comment, EEAFile.MARKER_KEEP))
         return earlierAnnotatedSignature;
      if (EEAFile.hasCommentMarker(laterAnnotatedSignature.comment, EEAFile.MARKER_IMPORTED))
         // A later protected predecessor is the newer source of the complete imported contract, not another
         // positional fallback.
         return laterAnnotatedSignature;
      if (EEAFile.hasCommentMarker(earlierAnnotatedSignature.comment, EEAFile.MARKER_IMPORTED))
         return earlierAnnotatedSignature;

      final char[] earlierAnnotations = extractNullAnnotations(originalSignature, earlierAnnotatedSignature.value);
      final char[] laterAnnotations = extractNullAnnotations(originalSignature, laterAnnotatedSignature.value);
      final char[] additionalAnnotations = new char[earlierAnnotations.length];
      final GeneratedOwnership laterOwnership = readGeneratedOwnership(originalSignature, laterAnnotatedSignature);
      final var additionalOwnership = new GeneratedOwnership();
      final int returnPosition = getMethodReturnNullAnnotationPosition(originalSignature);
      final boolean hasEarlierPolyNull = EEAFile.hasPolyNullContractMarker(earlierAnnotatedSignature.comment);
      final boolean hasLaterPolyNull = EEAFile.hasPolyNullContractMarker(laterAnnotatedSignature.comment);

      for (int i = 0; i < additionalAnnotations.length; i++) {
         if (earlierAnnotations[i] == 0 && laterAnnotations[i] != 0 && !(hasEarlierPolyNull && i == returnPosition)) {
            additionalAnnotations[i] = laterAnnotations[i];
            additionalOwnership.positions.set(i, laterOwnership.positions.get(i));
         }
      }

      final boolean addPolyNull = hasLaterPolyNull && !hasEarlierPolyNull //
            && (returnPosition < 0 || earlierAnnotations[returnPosition] == 0);
      if (addPolyNull && returnPosition >= 0) {
         // PolyNull owns the top-level return meaning; only independent later positions can accompany it.
         additionalAnnotations[returnPosition] = 0;
         additionalOwnership.positions.clear(returnPosition);
      }
      additionalOwnership.polyNull = addPolyNull && laterOwnership.polyNull;

      final var additions = new ValueWithComment(applyNullAnnotations(originalSignature, additionalAnnotations));
      setPolyNullMarker(additions, addPolyNull);
      final ReconciledContract mergedContract = reconcileContract(originalSignature, earlierAnnotatedSignature, new ReconciledContract(
         additions, additionalOwnership), GenerationMode.ADDITIVE);
      setGeneratedOwnershipMarker(originalSignature, mergedContract.annotatedSignature, mergedContract.ownership);
      return mergedContract.annotatedSignature;
   }

   private static ReconciledContract createCurrentRelationshipContract(final String originalSignature,
         final ValueWithComment inheritableAnnotatedSignature, final ValueWithComment generatedLocalSignature) {
      final char[] relationshipAnnotations = extractNullAnnotations(originalSignature, inheritableAnnotatedSignature.value);
      // Ownership is relative to the child: evidence copied from a parent is generator-managed in the child even
      // when the parent stores that evidence as manual.
      final GeneratedOwnership relationshipOwnership = createGeneratedOwnership(originalSignature, inheritableAnnotatedSignature);
      final char[] localAnnotations = extractNullAnnotations(originalSignature, generatedLocalSignature.value);
      for (int i = 0; i < localAnnotations.length; i++) {
         if (localAnnotations[i] != 0) {
            relationshipAnnotations[i] = localAnnotations[i];
            relationshipOwnership.positions.set(i);
         }
      }

      final boolean hasLocalPolyNull = EEAFile.hasPolyNullContractMarker(generatedLocalSignature.comment);
      final int returnPosition = getMethodReturnNullAnnotationPosition(originalSignature);
      if (hasLocalPolyNull && returnPosition >= 0) {
         /*
          * PolyNull is local analysis metadata, not an annotation copied from the parent. It supersedes a concrete
          * parent return marker while leaving the parent's independent parameter and nested-type evidence intact.
          */
         relationshipAnnotations[returnPosition] = 0;
         relationshipOwnership.positions.clear(returnPosition);
      }
      relationshipOwnership.polyNull = hasLocalPolyNull;

      final var annotatedSignature = new ValueWithComment(applyNullAnnotations(originalSignature, relationshipAnnotations));
      setPolyNullMarker(annotatedSignature, hasLocalPolyNull);
      return new ReconciledContract(annotatedSignature, relationshipOwnership);
   }

   private static ReconciledContract reconcileContract(final String originalSignature, final ValueWithComment storedAnnotatedSignature,
         final ReconciledContract currentContract, final GenerationMode generationMode) {
      // Layered stored contracts have no bytecode dependency provenance, so their PolyNull evidence must not be
      // reclassified from coincidental non-null parameter markers.
      return reconcileContract(originalSignature, storedAnnotatedSignature, currentContract, generationMode, null);
   }

   private static ReconciledContract reconcileContract(final String originalSignature, final ValueWithComment storedAnnotatedSignature,
         final ReconciledContract currentContract, final GenerationMode generationMode,
         final @Nullable BitSet generatedPolyNullDependencyPositions) {
      final char[] storedAnnotations = extractNullAnnotations(originalSignature, storedAnnotatedSignature.value);
      final GeneratedOwnership storedOwnership = readGeneratedOwnership(originalSignature, storedAnnotatedSignature);
      final char[] currentAnnotations = extractNullAnnotations(originalSignature, currentContract.annotatedSignature.value);
      final GeneratedOwnership currentOwnership = currentContract.ownership;
      final char[] reconciledAnnotations = storedAnnotations.clone();
      final GeneratedOwnership reconciledOwnership = storedOwnership.copy();
      final int returnPosition = getMethodReturnNullAnnotationPosition(originalSignature);
      final boolean hasStoredPolyNull = EEAFile.hasPolyNullContractMarker(storedAnnotatedSignature.comment);
      boolean hasCurrentPolyNull = EEAFile.hasPolyNullContractMarker(currentContract.annotatedSignature.comment);

      if (hasStoredPolyNull && returnPosition >= 0) {
         // PolyNull owns the top-level return meaning; a concrete marker on the same stored contract is not
         // independent.
         storedAnnotations[returnPosition] = 0;
         reconciledAnnotations[returnPosition] = 0;
         storedOwnership.positions.clear(returnPosition);
         reconciledOwnership.positions.clear(returnPosition);
      }
      if (hasCurrentPolyNull && returnPosition >= 0) {
         currentAnnotations[returnPosition] = 0;
         currentOwnership.positions.clear(returnPosition);
      }

      for (int i = 0; i < reconciledAnnotations.length; i++) {
         if (i == returnPosition && hasCurrentPolyNull && generatedPolyNullDependencyPositions != null && areAllPositionsNonNull(
            generatedPolyNullDependencyPositions, reconciledAnnotations)) {
            /* JVM signatures place every parameter before the return, so their effective markers have already been
             * reconciled here. PolyNull is conditional on those exact inputs; when all are non-null, its nullable
             * branch is outside the contract and the generated return becomes unconditionally non-null. Keep the
             * dependency positions transient because persisted ownership positions describe annotations, not data-flow. */
            hasCurrentPolyNull = false;
            currentAnnotations[returnPosition] = '1';
            currentOwnership.positions.set(returnPosition);
            currentOwnership.polyNull = false;
         }

         final char currentAnnotation = currentAnnotations[i];
         if (currentAnnotation != 0) {
            final boolean acceptsCurrentEvidence = generationMode == GenerationMode.FULL //
                  || storedAnnotations[i] == 0 //
                  || storedAnnotations[i] == currentAnnotation;
            if (acceptsCurrentEvidence) {
               reconciledAnnotations[i] = currentAnnotation;
               reconciledOwnership.positions.set(i, currentOwnership.positions.get(i));
            }
         } else if (generationMode == GenerationMode.FULL && storedOwnership.positions.get(i)) {
            /* Silence withdraws only generator-owned evidence. An unowned marker is a manual assertion and therefore
             * remains until current analysis produces an actual value at the same position. */
            reconciledAnnotations[i] = 0;
            reconciledOwnership.positions.clear(i);
         }
      }

      boolean hasReconciledPolyNull = hasStoredPolyNull;
      if (generationMode == GenerationMode.FULL) {
         if (hasCurrentPolyNull) {
            hasReconciledPolyNull = true;
            reconciledOwnership.polyNull = currentOwnership.polyNull;
            if (returnPosition >= 0) {
               reconciledAnnotations[returnPosition] = 0;
               reconciledOwnership.positions.clear(returnPosition);
            }
         } else if (returnPosition >= 0 && currentAnnotations[returnPosition] != 0) {
            hasReconciledPolyNull = false;
            reconciledOwnership.polyNull = false;
         } else if (storedOwnership.polyNull) {
            hasReconciledPolyNull = false;
            reconciledOwnership.polyNull = false;
         }
      } else if (hasCurrentPolyNull) {
         if (returnPosition < 0 || storedAnnotations[returnPosition] == 0) {
            hasReconciledPolyNull = true;
            reconciledOwnership.polyNull = currentOwnership.polyNull;
         }
      } else if (hasStoredPolyNull && returnPosition >= 0 && currentAnnotations[returnPosition] != 0) {
         /*
          * A concrete return conflicts with stored PolyNull. Additive mode keeps the dependency and accepts
          * independent positions, but it must not also add the incompatible concrete return marker.
          */
         reconciledAnnotations[returnPosition] = 0;
         reconciledOwnership.positions.clear(returnPosition);
      }

      final ValueWithComment reconciledSignature = storedAnnotatedSignature.clone();
      reconciledSignature.value = applyNullAnnotations(originalSignature, reconciledAnnotations);
      setPolyNullMarker(reconciledSignature, hasReconciledPolyNull);
      return new ReconciledContract(reconciledSignature, reconciledOwnership);
   }

   private static boolean areAllPositionsNonNull(final BitSet positions, final char[] annotations) {
      for (int position = positions.nextSetBit(0); position >= 0; position = positions.nextSetBit(position + 1)) {
         if (position >= annotations.length || annotations[position] != '1')
            return false;
      }
      return !positions.isEmpty();
   }

   private static GeneratedOwnership createGeneratedOwnership(final String originalSignature,
         final ValueWithComment generatedAnnotatedSignature) {
      final var ownership = new GeneratedOwnership();
      final char[] annotations = extractNullAnnotations(originalSignature, generatedAnnotatedSignature.value);
      for (int i = 0; i < annotations.length; i++) {
         if (annotations[i] != 0) {
            ownership.positions.set(i);
         }
      }
      ownership.polyNull = EEAFile.hasPolyNullContractMarker(generatedAnnotatedSignature.comment);
      return ownership;
   }

   private static GeneratedOwnership readGeneratedOwnership(final String originalSignature, final ValueWithComment annotatedSignature) {
      final char[] annotations = extractNullAnnotations(originalSignature, annotatedSignature.value);
      final var ownership = new GeneratedOwnership();
      final EEAFile.GeneratedMarkerRange marker = EEAFile.findGeneratedMarker(annotatedSignature.comment);
      final boolean hasUnscopedGeneratedOwnership = marker != null && marker.isUnscoped() //
            || marker == null && getRelationshipParent(annotatedSignature.comment) != null;
      if (hasUnscopedGeneratedOwnership) {
         /*
          * Bare generated and relationship markers deliberately cover every stored contract element. Otherwise a
          * user addition to a fully managed signature could silently become manual merely because its exact position
          * was not listed.
          */
         for (int i = 0; i < annotations.length; i++) {
            if (annotations[i] != 0) {
               ownership.positions.set(i);
            }
         }
         ownership.polyNull = EEAFile.hasPolyNullContractMarker(annotatedSignature.comment);
         return ownership;
      }
      if (marker == null)
         return ownership;

      final String arguments = marker.arguments;
      assert arguments != null;
      // Parentheses opt into partial ownership; an empty token must not negate a bare relationship's full ownership.
      for (final String argument : arguments.split(",", -1)) {
         final String token = argument.strip();
         if ("PolyNull".equals(token)) {
            if (ownership.polyNull)
               throw invalidGeneratedMarker(annotatedSignature.comment, "duplicate PolyNull ownership");
            ownership.polyNull = true;
            continue;
         }

         final int position;
         try {
            position = Integer.parseInt(token);
         } catch (final NumberFormatException ex) {
            throw invalidGeneratedMarker(annotatedSignature.comment, "unsupported ownership token [" + token + "]");
         }
         if (position < 0 || position >= annotations.length)
            throw invalidGeneratedMarker(annotatedSignature.comment, "position [" + position + "] is outside the raw signature");
         if (annotations[position] == 0)
            throw invalidGeneratedMarker(annotatedSignature.comment, "position [" + position + "] has no nullness marker");
         if (ownership.positions.get(position))
            throw invalidGeneratedMarker(annotatedSignature.comment, "duplicate position [" + position + "]");
         ownership.positions.set(position);
      }
      return ownership;
   }

   private static IllegalArgumentException invalidGeneratedMarker(final String comment, final String reason) {
      return new IllegalArgumentException("Invalid generated ownership marker in comment [" + comment + "]: " + reason);
   }

   private static void setGeneratedOwnershipMarker(final String originalSignature, final ValueWithComment annotatedSignature,
         final GeneratedOwnership ownership) {
      String commentWithoutOwnership = annotatedSignature.comment;
      while (true) {
         final String updatedComment = EEAFile.removeGeneratedMarker(commentWithoutOwnership);
         if (updatedComment.equals(commentWithoutOwnership)) {
            break;
         }
         commentWithoutOwnership = updatedComment;
      }
      annotatedSignature.comment = commentWithoutOwnership;
      if (ownership.polyNull) {
         /*
          * @Generated(PolyNull) is both the evidence and its ownership. Remove the standalone form after current
          * analysis has claimed it so the rendered contract does not state the same fact twice.
          */
         annotatedSignature.comment = EEAFile.removeCommentMarker(annotatedSignature.comment, EEAFile.MARKER_POLY_NULL);
      }
      if (ownership.isEmpty()) {
         if (getRelationshipParent(annotatedSignature.comment) != null && !ownershipCoversStoredContract(originalSignature,
            annotatedSignature, ownership)) {
            /*
             * A bare relationship denotes complete ownership. Leaving it beside manual evidence would reclassify that
             * evidence as generated on the next fixed-point pass. Preserve evidence-free relationships because they
             * still act as inheritance boundaries.
             */
            removeRelationshipMarker(annotatedSignature);
         }
         return;
      }

      if (!ownership.polyNull && getRelationshipParent(annotatedSignature.comment) != null //
            && ownershipCoversStoredContract(originalSignature, annotatedSignature, ownership))
         // A bare relationship already denotes complete child ownership; an additional @Generated marker is noise.
         return;

      /*
       * Unscoped ownership is safe only when the complete signature offers no independent position that could
       * later become manual. For larger signatures, exact positions preserve that distinction when analysis
       * withdraws earlier generated evidence. PolyNull is separate dependency evidence and stays explicit. A
       * relationship marker already carries unscoped ownership, so adding bare @Generated there would be redundant.
       */
      if (!ownership.polyNull && ownership.positions.cardinality() == 1 && hasExactlyOneSimpleNullnessPosition(originalSignature)) {
         if (getRelationshipParent(annotatedSignature.comment) == null) {
            prependCommentMarker(annotatedSignature, EEAFile.MARKER_GENERATED);
         }
         return;
      }

      final var marker = new StringBuilder(EEAFile.MARKER_GENERATED).append('(');
      boolean needsSeparator = false;
      for (int position = ownership.positions.nextSetBit(0); position >= 0; position = ownership.positions.nextSetBit(position + 1)) {
         if (needsSeparator) {
            marker.append(',');
         }
         marker.append(position);
         needsSeparator = true;
      }
      if (ownership.polyNull) {
         if (needsSeparator) {
            marker.append(',');
         }
         marker.append("PolyNull");
      }
      marker.append(')');
      prependCommentMarker(annotatedSignature, marker.toString());
   }

   private static boolean ownershipCoversStoredContract(final String originalSignature, final ValueWithComment annotatedSignature,
         final GeneratedOwnership ownership) {
      final char[] annotations = extractNullAnnotations(originalSignature, annotatedSignature.value);
      for (int i = 0; i < annotations.length; i++) {
         if (annotations[i] != 0 && !ownership.positions.get(i))
            return false;
      }
      return ownership.polyNull || !EEAFile.hasPolyNullContractMarker(annotatedSignature.comment);
   }

   private static boolean hasExactlyOneSimpleNullnessPosition(final String signature) {
      /*
       * Singleton compaction is optional. Keep exact positions for generic and inner-class signatures rather than
       * risk claiming a nested position that a user later maintains manually.
       */
      if (signature.indexOf('<') >= 0 || signature.indexOf('.') >= 0)
         return false;

      int positionCount = 0;
      int typeStart = 0;
      if (signature.charAt(0) == '(') {
         typeStart = 1;
         while (signature.charAt(typeStart) != ')') {
            positionCount += countSimpleTypeNullnessPositions(signature, typeStart);
            if (positionCount > 1)
               return false;
            typeStart = skipTypeSignature(signature, typeStart);
         }
         typeStart++;
      }

      positionCount += countSimpleTypeNullnessPositions(signature, typeStart);
      if (positionCount != 1)
         return false;
      final int signatureEnd = signature.charAt(typeStart) == 'V' ? typeStart + 1 : skipTypeSignature(signature, typeStart);
      // A trailing throws signature is valid JVM syntax but outside this deliberately narrow descriptor subset.
      return signatureEnd == signature.length();
   }

   private static int countSimpleTypeNullnessPositions(final String signature, final int typeStart) {
      int positions = 0;
      int valueStart = typeStart;
      while (signature.charAt(valueStart) == '[') {
         positions++;
         valueStart++;
      }
      final char valueType = signature.charAt(valueStart);
      return valueType == 'L' || valueType == 'T' ? positions + 1 : positions;
   }

   private static void prependCommentMarker(final ValueWithComment annotatedSignature, final String marker) {
      if (annotatedSignature.comment.isBlank()) {
         annotatedSignature.comment = "# " + marker;
      } else {
         // EEA accepts both "#text" and "# text". Normalize the boundary so source rewrites converge.
         final String retainedComment = annotatedSignature.comment.substring(1).stripLeading();
         annotatedSignature.comment = "# " + marker + (retainedComment.isEmpty() ? "" : " " + retainedComment);
      }
   }

   private static void setPolyNullMarker(final ValueWithComment annotatedSignature, final boolean enabled) {
      if (enabled) {
         addCommentMarker(annotatedSignature, EEAFile.MARKER_POLY_NULL);
      } else {
         annotatedSignature.comment = EEAFile.removeCommentMarker(annotatedSignature.comment, EEAFile.MARKER_POLY_NULL);
      }
   }

   private static int getMethodReturnNullAnnotationPosition(final String originalSignature) {
      final int returnTypeStart = originalSignature.lastIndexOf(')') + 1;
      if (returnTypeStart <= 0 || returnTypeStart >= originalSignature.length())
         return -1;
      final char returnTypeMarker = originalSignature.charAt(returnTypeStart);
      return returnTypeMarker == 'L' || returnTypeMarker == 'T' || returnTypeMarker == '[' ? returnTypeStart + 1 : -1;
   }

   /**
    * @return true if the proposed signature is allowed and changes the target
    */
   private static boolean applyGeneratedAnnotatedSignature(final ClassMember target, final ValueWithComment proposedAnnotatedSignature,
         final GenerationMode generationMode) {
      final ValueWithComment storedAnnotatedSignature = target.annotatedSignature;
      if (generationMode == GenerationMode.ADDITIVE //
            && !isAdditiveContractUpdate(target.originalSignature.value, storedAnnotatedSignature, proposedAnnotatedSignature))
         // Keep the stored value in memory as well as on disk. Descendants must inherit the contract this run will
         // actually write, not a destructive parent update that additive mode rejected.
         return false;
      target.annotatedSignature = proposedAnnotatedSignature;
      return !Objects.equals(storedAnnotatedSignature.value, proposedAnnotatedSignature.value) //
            || !Objects.equals(storedAnnotatedSignature.comment, proposedAnnotatedSignature.comment);
   }

   private static boolean isAdditiveContractUpdate(final String originalSignature, final ValueWithComment storedAnnotatedSignature,
         final ValueWithComment proposedAnnotatedSignature) {
      final char[] storedAnnotations = extractNullAnnotations(originalSignature, storedAnnotatedSignature.value);
      final char[] proposedAnnotations = extractNullAnnotations(originalSignature, proposedAnnotatedSignature.value);
      for (int i = 0; i < storedAnnotations.length; i++) {
         if (storedAnnotations[i] != 0 && storedAnnotations[i] != proposedAnnotations[i])
            return false;
      }
      // PolyNull is contract evidence even though it occupies no JVM-signature position.
      // Ownership and relationship markers are intentionally excluded: they must describe the accepted contract and
      // may
      // therefore change when additive evidence turns an inherited contract into an override.
      return !EEAFile.hasPolyNullContractMarker(storedAnnotatedSignature.comment) //
            || EEAFile.hasPolyNullContractMarker(proposedAnnotatedSignature.comment);
   }

   private static boolean addCommentMarker(final ValueWithComment annotatedSignature, final String marker) {
      if (EEAFile.hasCommentMarker(annotatedSignature.comment, marker))
         return false;
      annotatedSignature.comment = annotatedSignature.comment.isBlank() //
            ? "# " + marker //
            : annotatedSignature.comment + " " + marker;
      return true;
   }

   private static @Nullable ClassInfo findAncestor(final ClassInfo classInfo, final String ancestorName) {
      for (final ClassInfo ancestor : classInfo.getSuperclasses()) {
         if (ancestor.getName().equals(ancestorName))
            return ancestor;
      }
      // Keep this lookup aligned with the inheritance scan, which appends the synthetic Object EEA template.
      if (OBJECT_CLASS_INFO.getName().equals(ancestorName))
         return OBJECT_CLASS_INFO;
      for (final ClassInfo ancestor : classInfo.getInterfaces()) {
         if (ancestor.getName().equals(ancestorName))
            return ancestor;
      }
      return null;
   }

   private static boolean hasInheritableContractEvidence(final ClassInfo relationshipOwner, final ClassMember member,
         final Function<ClassInfo, @Nullable EEAFile> getSuperEEAFile) {
      /*
       * A metadata-only PolyNull contract deliberately owns an unqualified top-level return. It must stop the search
       * before a more distant concrete return marker is selected, even though its EEA value contains no 0/1 marker.
       */
      if (member.hasNullAnnotations() || member.hasPolyNullMarker())
         return true;

      /*
       * A raw @Overrides is not a boundary: it represents local evidence, and the generator does not emit it without
       * either signature markers or PolyNull evidence. Only @Inherited can be a metadata-only alias to another contract.
       */
      final String inheritedParentName = EEAFile.getRelationshipMarkerParent(member.annotatedSignature.comment, EEAFile.MARKER_INHERITED);
      if (inheritedParentName == null)
         return false;

      final ClassInfo inheritedParent = findAncestor(relationshipOwner, inheritedParentName);
      if (inheritedParent == null)
         return false;

      /*
       * An available named parent will be examined later in the flattened ancestor scan. If its EEA is unavailable,
       * however, this alias must remain a boundary or a more distant contract could leak through it.
       */
      return !OBJECT_CLASS_INFO.getName().equals(inheritedParent.getName()) && getSuperEEAFile.apply(inheritedParent) == null;
   }

   private static @Nullable String getRelationshipParent(final String comment) {
      final String inheritedParent = EEAFile.getRelationshipMarkerParent(comment, EEAFile.MARKER_INHERITED);
      return inheritedParent != null //
            ? inheritedParent //
            : EEAFile.getRelationshipMarkerParent(comment, EEAFile.MARKER_OVERRIDES);
   }

   private static String getExplanatoryComment(final String comment) {
      String explanation = EEAFile.removeGeneratedMarker(comment);
      explanation = EEAFile.removeRelationshipMarker(explanation, EEAFile.MARKER_INHERITED);
      explanation = EEAFile.removeRelationshipMarker(explanation, EEAFile.MARKER_OVERRIDES);
      for (final String marker : new String[] {EEAFile.MARKER_KEEP, EEAFile.MARKER_IMPORTED, EEAFile.MARKER_POLY_NULL}) {
         explanation = EEAFile.removeCommentMarker(explanation, marker);
      }
      return explanation;
   }

   private static void setRelationshipComment(final ValueWithComment annotatedSignature, final String marker, final String parentType) {
      /*
       * Relationship and generated-position ownership are separate axes. Replace only the relationship so positional
       * provenance, PolyNull state, and explanatory text survive reclassification.
       */
      annotatedSignature.comment = EEAFile.removeRelationshipMarker(annotatedSignature.comment, EEAFile.MARKER_INHERITED);
      annotatedSignature.comment = EEAFile.removeRelationshipMarker(annotatedSignature.comment, EEAFile.MARKER_OVERRIDES);
      prependCommentMarker(annotatedSignature, marker + "(" + parentType + ")");
   }

   private static void removeRelationshipMarker(final ValueWithComment annotatedSignature) {
      for (final String marker : new String[] {EEAFile.MARKER_INHERITED, EEAFile.MARKER_OVERRIDES}) {
         final String remainingComment = EEAFile.removeRelationshipMarker(annotatedSignature.comment, marker);
         if (!remainingComment.equals(annotatedSignature.comment)) {
            annotatedSignature.comment = remainingComment;
            return;
         }
      }
   }

   /**
    * Merges and minimizes EEA files.
    *
    * @return number of updated and removed files
    */
   public static long minimizeEEAFiles(final Config cfg) throws IOException {
      final var saveOptions = Set.of( //
         SaveOption.REPLACE_EXISTING, //
         SaveOption.DELETE_IF_EMPTY, //
         SaveOption.OMIT_COMMENTS, //
         SaveOption.OMIT_EMPTY_LINES, //
         SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES, //

         // currently does not work reliable, see https://github.com/eclipse-jdt/eclipse.jdt.core/issues/2512
         // SaveOption.OMIT_MEMBERS_WITH_INHERITED_ANNOTATED_SIGNATURES, //

         SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE, //
         SaveOption.QUIET);

      final List<Path> inputDirs = getEffectiveInputDirs(cfg);
      if (inputDirs.isEmpty())
         throw new IllegalArgumentException("No input.dirs specified!");

      // An existing empty source intentionally clears stale output; missing sources instead indicate failed input discovery.
      if (inputDirs.stream().noneMatch(Files::isDirectory))
         throw new IllegalArgumentException("None of the specified input.dirs exist!");

      LOG.log(Level.INFO, "Minimizing EEA files...");

      final var mergedEEAFiles = new TreeMap<Path, EEAFile>();
      for (final Path inputDir : inputDirs) {
         LOG.log(Level.INFO, "Loading EEA files from [{0}]...", inputDir);
         forEachFileWithExtension(inputDir, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, //
            path -> {
               final Path relativePath = inputDir.relativize(path);
               final EEAFile mergedEEAFile = mergedEEAFiles.get(relativePath);
               final String expectedClassName = relativePathToClassName(relativePath);

               final EEAFile sourceEEAFile = EEAFile.load(inputDir, expectedClassName);
               if (mergedEEAFile == null) {
                  mergedEEAFiles.put(relativePath, sourceEEAFile);
               } else {
                  mergedEEAFile.applyAnnotationsAndCommentsFrom(sourceEEAFile, false, true);
               }
            });
      }
      LOG.log(Level.INFO, "Found {0} types.", mergedEEAFiles.size());

      final var totalModifications = new LongAdder();
      for (final EEAFile eeaFile : mergedEEAFiles.values()) {
         if (eeaFile.save(cfg.outputDir, saveOptions)) {
            totalModifications.increment();
         }
      }

      // remove obsolete files
      forEachFileWithExtension(cfg.outputDir, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, //
         path -> {
            final Path relativePath = cfg.outputDir.relativize(path);
            if (!mergedEEAFiles.containsKey(relativePath)) {
               LOG.log(Level.DEBUG, "Removing obsolete annotation file [{0}]...", path.toAbsolutePath());
               Files.delete(path);
               totalModifications.increment();
            }
         });

      LOG.log(Level.INFO, "{0} EEA file(s) minimized or removed.", totalModifications.sum());
      return totalModifications.sum();
   }

   private static String relativePathToClassName(final Path relativePath) {
      return removeSuffix(relativePath.toString(), ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX).replace(File.separatorChar, '.');
   }

   /**
    * Recursively validates all EEA files for the given {@link Config#packages} in {@link Config#outputDir}.
    *
    * @return number of validated files
    * @throws IllegalArgumentException if no class was found
    */
   public static long validateEEAFiles(final Config config) throws IOException {
      long totalValidations = 0;

      for (final String packageName : config.packages) {
         LOG.log(Level.INFO, "Validating EEA files of package [{0}]...", packageName);

         final Map<Path, EEAFile> eeaFilesOfPkg = remap(computeEEAFiles(packageName, config.classFilter), v -> v.relativePath);
         LOG.log(Level.INFO, "Found {0} types on classpath.", eeaFilesOfPkg.size());

         final Path packagePath = config.outputDir.resolve(packageName.replace('.', File.separatorChar));
         final long count = forEachFileWithExtension(packagePath, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, //
            path -> {
               final Path relativePath = config.outputDir.relativize(path);
               final String expectedClassName = relativePathToClassName(relativePath);

               // ensure if the type actually exists on the class path
               final var computedEEAFile = eeaFilesOfPkg.get(relativePath);
               if (computedEEAFile == null)
                  throw new IllegalStateException("Type [" + expectedClassName + "] defined in [" + path + "] not found on classpath.");

               // try to parse the EEA file
               final var parsedEEAFile = EEAFile.load(path);

               // EEA files may be partial. Check supplied type declarations without requiring every scanned declaration.
               final String parsedTypeParameters = parsedEEAFile.classHeader.originalSignature.value;
               if (!parsedTypeParameters.isEmpty() && !parsedTypeParameters.equals(computedEEAFile.classHeader.originalSignature.value))
                  throw new IllegalStateException("Class type parameter signature mismatch in [" + path + "]:\n" + "  Stored: "
                        + parsedTypeParameters + "\n  Scanned: " + computedEEAFile.classHeader.originalSignature.value);

               for (final ClassMember parsedSuperType : parsedEEAFile.superTypes) {
                  // Shared type arguments do not identify a supertype; require its name as well as its raw signature.
                  if (computedEEAFile.superTypes.stream().noneMatch(superType -> superType.name.value.equals(parsedSuperType.name.value)
                        && superType.originalSignature.value.equals(parsedSuperType.originalSignature.value)))
                     throw new IllegalStateException("Unknown supertype declaration found in [" + path + "]:\n" + parsedSuperType);
               }

               // ensure the EEA file does not contain declarations of non-existing fields/methods
               parsedEEAFile.getClassMembers().forEach(parsedMember -> {
                  // Validation must reject malformed ownership even when whole-member protection bypasses generation policy.
                  readGeneratedOwnership(parsedMember.originalSignature.value, parsedMember.annotatedSignature);
                  validateProtectionMarkers(parsedMember.annotatedSignature);
                  if (computedEEAFile.findMatchingClassMember(parsedMember) == null) {

                     // Preserve the originating @Keep compatibility promise for older library versions across imports.
                     if (parsedMember.hasProtectedContractMarker())
                        return;

                     final var candidates = computedEEAFile.getClassMembers() //
                        .filter(m -> m.name.equals(parsedMember.name)) //
                        .map(m -> m.name + "\n" + " " + m.originalSignature) //
                        .collect(Collectors.joining("\n"));
                     throw new IllegalStateException("Unknown member declaration found in [" + path + "]:\n" + parsedMember + (candidates
                        .isEmpty() ? "" : "\nPotential candidates: \n" + candidates));
                  }
               });
            });
         LOG.log(Level.INFO, "{0} EEA file(s) of package [{1}] validated.", count, packageName);
         totalValidations += count;
      }
      return totalValidations;
   }
}
