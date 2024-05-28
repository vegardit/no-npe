/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;

import com.vegardit.no_npe.eea_generator.EEAFile.SaveOption;
import com.vegardit.no_npe.eea_generator.EEAFile.ValueWithComment;
import com.vegardit.no_npe.eea_generator.internal.MiscUtils;
import com.vegardit.no_npe.eea_generator.internal.Props;

import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassMemberInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList;
import io.github.classgraph.ScanResult;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public abstract class EEAGenerator {

   private static final Logger LOG = System.getLogger(EEAGenerator.class.getName());

   public static final Path DEFAULT_PROPERTES_FILE = Path.of("eea-generator.properties");

   public static final String JVM_PROPERTY_PREFIX = "eea-generator.";

   public static final String PROPERTY_ACTION = "action";
   public static final String PROPERTY_INPUT_DIRS = "input.dirs";
   public static final String PROPERTY_INPUT_DIRS_DEFAULT = PROPERTY_INPUT_DIRS + ".default";
   public static final String PROPERTY_OUTPUT_DIR = "output.dir";
   public static final String PROPERTY_OUTPUT_DIR_DEFAULT = PROPERTY_OUTPUT_DIR + ".default";
   public static final String PROPERTY_PACKAGES_INCLUDE = "packages.include";
   public static final String PROPERTY_CLASSES_EXCLUDE = "classes.exclude";
   public static final String PROPERTY_DELETE_IF_NO_ANNOTATABLE_MEMBERS = "deleteIfNoAnnotatableMembers";
   public static final String PROPERTY_DELETE_IF_NO_ANNOTATED_SIGNATURES = "deleteIfNoAnnotatedSignatures";
   public static final String PROPERTY_OMIT_REDUNDAND_ANNOTATED_SIGNATURES = "omitRedundantAnnotatedSignatures";
   public static final String PROPERTY_OMIT_CLASS_MEMBERS_WITHOUT_NULL_ANNOTATION = "omitClassMembersWithoutNullAnnotation";

   private static final EEAFile TEMPLATE_SERIALIZABLE;
   private static final EEAFile TEMPLATE_EXTERNALIZABLE;
   private static final EEAFile TEMPLATE_OBJECT;
   private static final EEAFile TEMPLATE_THROWABLE;

   static {
      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Serializable.eea")) {
         TEMPLATE_SERIALIZABLE = EEAFile.load(reader, "classpath:java/io/Serializable.eea");
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Externalizable.eea")) {
         TEMPLATE_EXTERNALIZABLE = EEAFile.load(reader, "classpath:java/io/Externalizable.eea");
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
      public final List<Path> inputDirs = new ArrayList<>();
      public final Path outputDir;
      public Predicate<ClassInfo> classFilter = clazz -> true;
      public boolean deleteIfNoAnnotatableMembers = true;
      public boolean deleteIfNoAnnotatedSignatures;
      public boolean omitClassMembersWithoutNullAnnotations;
      public boolean omitRedundantAnnotatedSignatures;

      public Config(final Path outputDir, final String... packages) {
         this.outputDir = outputDir;
         this.packages = packages;
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
         final var props = new Props(JVM_PROPERTY_PREFIX, filePropsPath);

         final var action = props.get(PROPERTY_ACTION, null).value;

         final var packages = "minimize".equals(action) //
               ? new @NonNull String[0] //
               : props.get(PROPERTY_PACKAGES_INCLUDE, null).value.split(",");

         final var classExclusionsStr = props.get(PROPERTY_CLASSES_EXCLUDE, "");
         final Pattern[] classExclusions = classExclusionsStr.value.isBlank() //
               ? new Pattern[0] //
               : Arrays.stream(classExclusionsStr.value.split(",")).map(Pattern::compile).toArray(Pattern[]::new);

         final var outputDirPropDefault = props.get(PROPERTY_OUTPUT_DIR_DEFAULT, "").value;
         final var outputDirProp = props.get(PROPERTY_OUTPUT_DIR, outputDirPropDefault.isEmpty() ? null : outputDirPropDefault);
         var outputDir = Path.of(outputDirProp.value);
         if (outputDirProp.source instanceof Path && !outputDir.isAbsolute()) {
            // if the specified outputDir value is relative and was source from properties file,
            // then make it relative to the properties file
            outputDir = ((Path) outputDirProp.source).getParent().resolve(outputDir);
         }
         outputDir = outputDir.normalize().toAbsolutePath();

         final var config = new Config(outputDir, packages);

         config.deleteIfNoAnnotatableMembers = Boolean.parseBoolean(props.get(PROPERTY_DELETE_IF_NO_ANNOTATABLE_MEMBERS, "true").value);
         config.deleteIfNoAnnotatedSignatures = Boolean.parseBoolean(props.get(PROPERTY_DELETE_IF_NO_ANNOTATED_SIGNATURES, "false").value);
         config.omitClassMembersWithoutNullAnnotations = Boolean.parseBoolean(props.get(PROPERTY_OMIT_CLASS_MEMBERS_WITHOUT_NULL_ANNOTATION,
            "false").value);
         config.omitRedundantAnnotatedSignatures = Boolean.parseBoolean(props.get(PROPERTY_OMIT_REDUNDAND_ANNOTATED_SIGNATURES,
            "false").value);
         config.classFilter = clazz -> {
            for (final var classExclusion : classExclusions) {
               if (classExclusion.matcher(clazz.getName()).find())
                  return false;
            }
            return true;
         };

         final var inputDirsProp = props.get(PROPERTY_INPUT_DIRS, props.get(PROPERTY_INPUT_DIRS_DEFAULT, "").value);
         for (final var inputDirStr : inputDirsProp.value.split(",")) {
            if (inputDirStr.isBlank()) {
               continue;
            }
            var inputDir = Path.of(inputDirStr);
            if (inputDirsProp.source instanceof Path && !inputDir.isAbsolute()) {
               // if the specified inputDir value is relative and was source from properties file,
               // then make it relative to the properties file
               inputDir = ((Path) inputDirsProp.source).getParent().resolve(inputDir);
            }
            config.inputDirs.add(inputDir.normalize().toAbsolutePath());
         }

         LOG.log(Level.INFO, "Effective input directories: " + config.inputDirs);
         LOG.log(Level.INFO, "Effective output directory: " + outputDir);

         switch (action) {
            case "generate":
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
         final var iox = ex.getCause();
         sanitizeStackTraces(iox);
         throw iox;
      } catch (final Exception ex) {
         sanitizeStackTraces(ex);
         throw ex;
      }
   }

   protected static @Nullable ValueWithComment computeAnnotatedSignature(final EEAFile.ClassMember member, final ClassInfo classInfo,
         final ClassMemberInfo memberInfo) {

      final var templates = new ArrayList<EEAFile>();
      final var isThrowable = !classInfo.getSuperclasses().filter(c -> c.getName().equals("java.lang.Throwable")).isEmpty();
      if (isThrowable) {
         templates.add(TEMPLATE_THROWABLE);
      }
      templates.add(TEMPLATE_EXTERNALIZABLE);
      templates.add(TEMPLATE_SERIALIZABLE);
      templates.add(TEMPLATE_OBJECT);

      for (final EEAFile template : templates) {
         final var matchingMember = template.findMatchingClassMember(member);
         if (matchingMember != null && matchingMember.annotatedSignature != null)
            return matchingMember.annotatedSignature;
      }

      // analyzing a method
      if (memberInfo instanceof MethodInfo) {
         final MethodInfo methodInfo = (MethodInfo) memberInfo;

         // mark the parameter of single-parameter methods as @NonNull,
         // if the class name matches "*Listener" and the parameter type name matches "*Event"
         if (classInfo.isInterface() //
               && classInfo.getName().endsWith("Listener") //
               && !methodInfo.isStatic() // non-static
               && member.originalSignature.value.endsWith(")V") // returns void
               && methodInfo.getParameterInfo().length == 1 // only 1 parameter
               && methodInfo.getParameterInfo()[0].getTypeDescriptor().toString().endsWith("Event"))

            // (Ljava/lang/String;)V -> (L1java/lang/String;)V
            return new ValueWithComment(insert(member.originalSignature.value, 2, "1"), null);

         if (hasObjectReturnType(member)) { // returns non-void
            if (hasNullableAnnotation(methodInfo.getAnnotationInfo()))
               // ()Ljava/lang/String -> ()L0java/lang/String;
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2, "0"),
                  null);

            if (hasNonNullAnnotation(methodInfo.getAnnotationInfo()))
               // ()Ljava/lang/String -> ()L1java/lang/String;
               return new ValueWithComment(insert(member.originalSignature.value, member.originalSignature.value.lastIndexOf(")") + 2, "1"),
                  null);
         }
      }

      // analyzing a field
      if (memberInfo instanceof FieldInfo) {
         final FieldInfo fieldInfo = (FieldInfo) memberInfo;
         if (hasNullableAnnotation(fieldInfo.getAnnotationInfo()))
            return new ValueWithComment(insert(member.originalSignature.value, 1, "0"));

         if (fieldInfo.isStatic() && fieldInfo.isFinal() // if the field is static and final we by default expect it to be non-null
               || hasNonNullAnnotation(fieldInfo.getAnnotationInfo()) //
         )
            // Ljava/lang/String; -> L1java/lang/String;
            return new ValueWithComment(insert(member.originalSignature.value, 1, "1"));
      }

      return null;
   }

   protected static boolean hasObjectReturnType(final EEAFile.ClassMember member) {
      final var sig = member.originalSignature.value;
      // object return type: (Ljava/lang/String;)Ljava/lang/String; or (Ljava/lang/String;)TT;
      // void return type: (Ljava/lang/String;)V
      // primitive return type: (Ljava/lang/String;)B
      return sig.charAt(sig.length() - 2) != ')';
   }

   protected static boolean hasNullableAnnotation(final AnnotationInfoList annos) {
      return annos.containsName("javax.annotation.Nullable") //
            || annos.containsName("jakarta.annotation.Nullable") //
            || annos.containsName("edu.umd.cs.findbugs.annotations.Nullable") //
            || annos.containsName("org.checkerframework.checker.nullness.qual.Nullable") //
            || annos.containsName("org.jetbrains.annotations.Nullable") //
            || annos.containsName("org.springframework.lang.Nullable") //
            || annos.containsName("org.wildfly.common.annotation.Nullable") //
            || annos.containsName("io.vertx.codegen.annotations.Nullable") //
            || annos.containsName("net.bytebuddy.utility.nullability.MaybeNull") //
            || annos.containsName("net.bytebuddy.utility.nullability.AlwaysNull");
   }

   protected static boolean hasNonNullAnnotation(final AnnotationInfoList annos) {
      return annos.containsName("javax.annotation.Nonnull") //
            || annos.containsName("jakarta.annotation.Nonnull") //
            || annos.containsName("edu.umd.cs.findbugs.annotations.NonNull") //
            || annos.containsName("org.checkerframework.checker.nullness.qual.NonNull") //
            || annos.containsName("org.jetbrains.annotations.NotNull") //
            || annos.containsName("org.springframework.lang.NonNull") //
            || annos.containsName("org.wildfly.common.annotation.NotNull") //
            || annos.containsName("net.bytebuddy.utility.nullability.NeverNull");
   }

   public static EEAFile computeEEAFile(final ClassInfo classInfo) {
      LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());

      final var eeaFile = new EEAFile(classInfo.getName());
      eeaFile.addEmptyLine();

      final var fields = classInfo.getDeclaredFieldInfo();
      final var methods = classInfo.getDeclaredMethodAndConstructorInfo();

      // class signature
      final var typeSig = classInfo.getTypeSignature();
      if (typeSig != null && !typeSig.getTypeParameters().isEmpty()) {
         final var typeParams = substringBetweenBalanced(classInfo.getTypeSignatureStr(), '<', '>');
         if (typeParams == null || !typeParams.isEmpty()) {
            eeaFile.classSignatureOriginal = new ValueWithComment('<' + typeParams + '>');
         }
      }

      // static fields
      for (final var f : getFields(fields, true)) {
         if (classInfo.isEnum()) {
            // omit enum values as they are always treated as non-null by eclipse compiler
            if (f.isFinal() && classInfo.getTypeSignatureStr().startsWith("Ljava/lang/Enum<" + f.getTypeDescriptorStr() + ">;")) {
               continue;
            }
         }

         final var member = eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         member.annotatedSignature = computeAnnotatedSignature(member, classInfo, f);
      }

      eeaFile.addEmptyLine();

      // static methods
      for (final var m : getMethods(methods, true)) {
         final var member = eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr());
         member.annotatedSignature = computeAnnotatedSignature(member, classInfo, m);
      }
      eeaFile.addEmptyLine();

      // instance fields
      for (final var f : getFields(fields, false)) {
         final var member = eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         member.annotatedSignature = computeAnnotatedSignature(member, classInfo, f);
      }
      eeaFile.addEmptyLine();

      // instance methods
      for (final var m : getMethods(methods, false)) {
         final var member = eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         member.annotatedSignature = computeAnnotatedSignature(member, classInfo, m);
      }
      return eeaFile;
   }

   /**
    * Instantiates {@link EEAFile} instances for all classes found in classpath in the given package or sub-packages.
    *
    * @param rootPackageName name the of root package to scan for classes
    * @throws IllegalArgumentException if no class was found
    */
   public static SortedMap<Path, EEAFile> computeEEAFiles(final String rootPackageName, final Predicate<ClassInfo> filter) {
      final var result = new TreeMap<Path, EEAFile>();
      try (ScanResult scanResult = new ClassGraph() //
         .enableAllInfo() //
         .enableSystemJarsAndModules() //
         .acceptPackages(rootPackageName) //
         .scan() //
      ) {
         final var classes = scanResult.getAllClasses();
         if (classes.isEmpty())
            throw new IllegalArgumentException("No classes found for package [" + rootPackageName + "] on classpath");

         for (final ClassInfo classInfo : classes) {

            // skip uninteresting classes
            final boolean isPackageVisible = !classInfo.isPublic() && !classInfo.isPrivate() && !classInfo.isProtected();
            if (isPackageVisible || classInfo.isPrivate() || classInfo.isAnonymousInnerClass()) {
               LOG.log(Level.DEBUG, "Ignoring non-accessible classes [{0}]...", classInfo.getName());
               continue;
            }

            if (!filter.test(classInfo)) {
               LOG.log(Level.DEBUG, "Ignoring class excluded by filter [{0}]...", classInfo.getName());
               continue;
            }

            LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());
            final var eeaFile = computeEEAFile(classInfo);
            result.put(eeaFile.relativePath, eeaFile);
         }
      }

      // TODO workaround for https://github.com/classgraph/classgraph/issues/703
      if (("java".equals(rootPackageName) || rootPackageName.startsWith("java.lang")) //
            && !result.containsKey(TEMPLATE_OBJECT.relativePath)) {
         result.put(TEMPLATE_OBJECT.relativePath, TEMPLATE_OBJECT);
      }
      return result;
   }

   /**
    * @param selectStatic if true static fields are returned otherwise instance fields
    * @return a sorted set of {@link FieldInfo} instances for all public or protected non-synthetic non-primitive fields
    */
   private static SortedSet<FieldInfo> getFields(final FieldInfoList fields, final boolean selectStatic) {
      final var result = new TreeSet<FieldInfo>((f1, f2) -> {
         final int rc = f1.getName().compareTo(f2.getName());
         return rc == 0 ? f1.getTypeSignatureOrTypeDescriptorStr().compareTo(f2.getTypeSignatureOrTypeDescriptorStr()) : rc;
      });

      for (final var f : fields) {
         if (!f.isSynthetic() //
               && (selectStatic ? f.isStatic() : !f.isStatic()) //
               && (f.isProtected() || f.isPublic()) //
               && (f.getTypeSignatureOrTypeDescriptorStr().contains(";") || f.getTypeSignatureOrTypeDescriptorStr().contains("["))) {
            result.add(f);
         }
      }
      return result;
   }

   /**
    * @param selectStatic if true static methods are returned otherwise instance methods
    * @return a sorted set of {@link MethodInfo} instances for all public or protected non-synthetic methods with
    *         a non-primitive return value or at least one non-primitive method parameter
    */
   private static SortedSet<MethodInfo> getMethods(final MethodInfoList methods, final boolean selectStatic) {
      final var result = new TreeSet<MethodInfo>((m1, m2) -> {
         final int rc = m1.getName().compareTo(m2.getName());
         return rc == 0 ? m1.getTypeSignatureOrTypeDescriptorStr().compareTo(m2.getTypeSignatureOrTypeDescriptorStr()) : rc;
      });

      for (final var m : methods) {
         // omit auto-generated methods of enums as they are always treated as non-null by eclipse compiler
         if (m.getClassInfo().isEnum()) {
            switch (m.getName()) {
               case "values":
                  if (m.getParameterInfo().length == 0) {
                     continue;
                  }
                  break;
               case "valueOf":
                  if (m.getParameterInfo().length == 1 && String.class.getName().equals(m.getParameterInfo()[0].getTypeDescriptor()
                     .toString())) {
                     continue;
                  }
                  break;
            }
         }
         if (!m.isSynthetic() //
               && (selectStatic ? m.isStatic() : !m.isStatic()) //
               && (m.isProtected() || m.isPublic()) //
               && (m.getTypeSignatureOrTypeDescriptorStr().contains(";") || m.getTypeSignatureOrTypeDescriptorStr().contains("["))) {
            result.add(m);
         }
      }
      return result;
   }

   /**
    * Scans the classpath for classes of {@link Config#packages}, applies EEAs from files in {@link Config#inputDirs} and
    * creates updated EEA files in {@link Config#outputDir}.
    *
    * @return number of updated and removed files
    * @throws IllegalArgumentException if no class was found
    */
   public static long generateEEAFiles(final Config cfg) throws IOException {
      final var saveOptions = Arrays.stream(new @Nullable SaveOption[] { //
         SaveOption.REPLACE_EXISTING, //
         cfg.deleteIfNoAnnotatableMembers ? SaveOption.DELETE_IF_NO_ANNOTATABLE_MEMBERS : null, //
         cfg.deleteIfNoAnnotatedSignatures ? SaveOption.DELETE_IF_NO_ANNOTATED_SIGNATURES : null, //
         cfg.omitRedundantAnnotatedSignatures ? SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES : null, //
         cfg.omitClassMembersWithoutNullAnnotations ? SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE : null //
      }).filter(Objects::nonNull).collect(Collectors.toSet());

      long totalModifications = 0;
      for (final var packageName : cfg.packages) {
         LOG.log(Level.INFO, "Updating EEA files of package [{0}]...", packageName);
         final var eeaFiles = computeEEAFiles(packageName, cfg.classFilter);
         LOG.log(Level.INFO, "Found {0} types on classpath.", eeaFiles.size());

         final var pkgModifications = new LongAdder();
         for (final var computedEEAFile : eeaFiles.values()) {
            for (final var inputDir : cfg.inputDirs) {
               final var existingEEAFile = EEAFile.loadIfExists(inputDir, computedEEAFile.className.value);
               if (existingEEAFile != null) {
                  computedEEAFile.applyAnnotationsAndCommentsFrom(existingEEAFile, true);
               }
            }

            if (computedEEAFile.save(cfg.outputDir, saveOptions)) {
               pkgModifications.increment();
            }
         }

         // remove obsolete files
         MiscUtils.forEachFileWithExtension(cfg.outputDir.resolve(packageName.replace('.', File.separatorChar)),
            ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, path -> {
               final Path relativePath = cfg.outputDir.relativize(path);
               if (!eeaFiles.containsKey(relativePath)) {
                  LOG.log(Level.WARNING, "Removing obsolete annotation file [{0}]...", path.toAbsolutePath());
                  Files.delete(path);
                  pkgModifications.increment();
               }
            });

         LOG.log(Level.INFO, "{0} EEA file(s) of package [{1}] updated or removed.", pkgModifications.sum(), packageName);
         totalModifications += pkgModifications.sum();
      }
      return totalModifications;
   }

   /**
    * Merges and minimizes EEA files.
    *
    * @return number of updated and removed files
    */
   public static long minimizeEEAFiles(final Config cfg) throws IOException {
      final var saveOptions = Set.of( //
         SaveOption.REPLACE_EXISTING, //
         SaveOption.DELETE_IF_NO_ANNOTATABLE_MEMBERS, //
         SaveOption.DELETE_IF_NO_ANNOTATED_SIGNATURES, //
         SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES, //
         SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE, //
         SaveOption.QUIET);

      if (cfg.inputDirs.isEmpty())
         throw new IllegalArgumentException("No input.dirs specified!");

      LOG.log(Level.INFO, "Minimizing EEA files...");

      final var mergedEEAFiles = new TreeMap<Path, EEAFile>();
      for (final Path inputDir : cfg.inputDirs) {
         LOG.log(Level.INFO, "Loading EEA files from [{0}]...", inputDir);
         MiscUtils.forEachFileWithExtension(inputDir, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, path -> {
            final Path relativePath = inputDir.relativize(path);
            final EEAFile mergedEEAFile = mergedEEAFiles.get(relativePath);
            final String expectedClassName = relativePathToClassName(relativePath);

            final EEAFile sourceEEAFile = EEAFile.load(inputDir, expectedClassName);
            if (mergedEEAFile == null) {
               mergedEEAFiles.put(relativePath, sourceEEAFile);
            } else {
               mergedEEAFile.applyAnnotationsAndCommentsFrom(sourceEEAFile, false);
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
      MiscUtils.forEachFileWithExtension(cfg.outputDir, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, path -> {
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
      return MiscUtils.removeSuffix(relativePath.toString(), ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX).replace(File.separatorChar,
         '.');
   }

   /**
    * Recursively validates all EEA files for the given {@link Config#packages} in {@link Config#outputDir}.
    *
    * @return number of validated files
    * @throws IllegalArgumentException if no class was found
    */
   public static long validateEEAFiles(final Config config) throws IOException {
      long totalValidations = 0;
      for (final var packageName : config.packages) {
         LOG.log(Level.INFO, "Validating EEA files of package [{0}]...", packageName);

         final Map<Path, EEAFile> computedEEAFiles = computeEEAFiles(packageName, config.classFilter);
         LOG.log(Level.INFO, "Found {0} types on classpath.", computedEEAFiles.size());

         final Path packagePath = config.outputDir.resolve(packageName.replace('.', File.separatorChar));
         final long count = MiscUtils.forEachFileWithExtension(packagePath, ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX, path -> {
            final Path relativePath = config.outputDir.relativize(path);
            final String expectedClassName = relativePathToClassName(relativePath);

            // ensure if the type actually exists on the class path
            final var computedEEAFile = computedEEAFiles.get(relativePath);
            if (computedEEAFile == null)
               throw new IllegalStateException("Type [" + expectedClassName + "] defined in [" + path + "] no found on classpath.");

            // try to parse the EEA file
            final var parsedEEAFile = EEAFile.load(path);

            // ensure the EEA file does not contain declarations of non-existing fields/methods
            for (final var parsedMember : parsedEEAFile.getClassMembers().collect(Collectors.toList())) {
               if (computedEEAFile.findMatchingClassMember(parsedMember) == null) {
                  final var candidates = computedEEAFile //
                     .getClassMembers().filter(m -> m.name.equals(parsedMember.name)) //
                     .map(m -> m.name + "\n" + " " + m.originalSignature) //
                     .collect(Collectors.joining("\n"));
                  throw new IllegalStateException("Unknown member declaration found in [" + path + "]: " + parsedMember + (candidates
                     .length() > 0 ? "\nPotential candidates: \n" + candidates : ""));
               }
            }
         });
         LOG.log(Level.INFO, "{0} EEA file(s) of package [{1}] validated.", count, packageName);
         totalValidations += count;
      }
      return totalValidations;
   }
}
