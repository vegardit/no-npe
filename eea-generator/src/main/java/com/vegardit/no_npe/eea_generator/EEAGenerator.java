/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;

import com.vegardit.no_npe.eea_generator.EEAFile.LoadOptions;
import com.vegardit.no_npe.eea_generator.EEAFile.SaveOptions;
import com.vegardit.no_npe.eea_generator.EEAFile.ValueWithComment;
import com.vegardit.no_npe.eea_generator.internal.Props;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
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
   public static final String PROPERTY_OUTPUT_DIR = "output.dir";
   public static final String PROPERTY_PACKAGES_INCLUDE = "packages.include";

   public static class Config {
      public @NonNull String[] packages;
      public Path outputDir;
      public Predicate<ClassInfo> classFilter = c -> true;

      public Config(final Path outputDir, final @NonNull String... packages) {
         this.outputDir = outputDir;
         this.packages = packages;
      }
   }

   /**
    * args[0]: optional path to properties file
    */
   public static void main(final @NonNull String... args) throws IOException {
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
      final var packages = props.get(PROPERTY_PACKAGES_INCLUDE, null).value.split(",");
      final var outputDirProp = props.get(PROPERTY_OUTPUT_DIR, "src/main/resources");
      var outputDir = Path.of(outputDirProp.value);
      if (outputDirProp.source instanceof Path && !outputDir.isAbsolute()) {
         // if the specified outputDir value is relative and was source from properties file,
         // then make it relative to the properties file
         outputDir = ((Path) outputDirProp.source).resolve(outputDir);
      }

      final var config = new Config(outputDir, packages);
      switch (action) {
         case "generate":
            updateEEAFiles(config);
            break;
         case "validate":
            validateEEAFiles(config);
            break;
         default:
            throw new IllegalArgumentException("Unsupported value for [action] parameter: " + action);
      }
   }

   public static EEAFile computeEEAFile(final ClassInfo classInfo) {
      LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());

      final var eeaFile = new EEAFile(classInfo.getName());
      eeaFile.addEmptyLine();

      final var fields = classInfo.getDeclaredFieldInfo();
      final var methods = classInfo.getDeclaredMethodAndConstructorInfo();

      final var typeSig = classInfo.getTypeSignature();
      if (typeSig != null && !typeSig.getTypeParameters().isEmpty()) {
         final var typeParams = getSubstringBetweenBalanced(classInfo.getTypeSignatureStr(), '<', '>');
         if (typeParams == null || !typeParams.isEmpty()) {
            eeaFile.classSignatureOriginal = new ValueWithComment('<' + typeParams + '>');
         }
      }

      // static fields
      for (final var f : getFields(fields, true)) {
         final var member = eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()); // CHECKSTYLE:IGNORE .*
         if (f.isFinal()) {
            // if the static non-primitive field is final we by default expect it to be non-null,
            // which can be manually adjusted in the generated field
            member.annotatedSignature = new ValueWithComment(insert(member.originalSignature.value, 1, "1"));
         }
      }

      eeaFile.addEmptyLine();

      // static methods
      for (final var m : getMethods(methods, true)) {
         eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr());
      }
      eeaFile.addEmptyLine();

      // instance fields
      getFields(fields, false).stream().forEach(f -> eeaFile.addMember(f.getName(), f.getTypeSignatureOrTypeDescriptorStr()));
      eeaFile.addEmptyLine();

      // instance methods
      for (final var m : getMethods(methods, false)) {
         eeaFile.addMember(m.getName(), m.getTypeSignatureOrTypeDescriptorStr());
      }
      return eeaFile;
   }

   /**
    * Instantiates {@link EEAFile} instances for all classes found in classpath in the given package or sub-packages.
    *
    * @param rootPackageName name the of root package to scan for classes
    * @throws IllegalArgumentException if no class was found
    */
   public static SortedMap<Path, EEAFile> computeEEAFiles( //
      final String rootPackageName, //
      @Nullable final Predicate<ClassInfo> filter //
   ) {
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

            if (filter != null && !filter.test(classInfo)) {
               LOG.log(Level.DEBUG, "Ignoring class excluded by filter [{0}]...", classInfo.getName());
            }

            LOG.log(Level.DEBUG, "Scanning class [{0}]...", classInfo.getName());
            final var eeaFile = computeEEAFile(classInfo);
            result.put(eeaFile.relativePath, eeaFile);
         }
      }

      // TODO workaround for https://github.com/classgraph/classgraph/issues/703
      if (("java".equals(rootPackageName) || rootPackageName.startsWith("java.lang")) //
         && !result.containsKey(EEAFile.TEMPLATE_OBJECT.relativePath)) {
         result.put(EEAFile.TEMPLATE_OBJECT.relativePath, EEAFile.TEMPLATE_OBJECT);
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
    * Recursively creates or updates all EEA files for the given {@link Config#packages} in {@link Config#outputDir}.
    *
    * @return number of updated and removed files
    * @throws IllegalArgumentException if no class was found
    */
   public static long updateEEAFiles(final Config config) throws IOException {
      try {
         long totalModifications = 0;
         for (final var packageName : config.packages) {
            LOG.log(Level.INFO, "Updating EEA files of package [{0}]...", packageName);
            final var pkgModifications = new LongAdder();

            final var eeaFiles = computeEEAFiles(packageName, config.classFilter);
            LOG.log(Level.INFO, "Found {0} types on classpath.", eeaFiles.size());
            for (final var computedEEAFile : eeaFiles.values()) {
               final var existingEEAFile = new EEAFile(computedEEAFile.className.value);
               existingEEAFile.load(config.outputDir, LoadOptions.IGNORE_NONE_EXISTING);
               computedEEAFile.applyAnnotationsAndCommentsFrom(existingEEAFile, true);
               if (computedEEAFile.save(config.outputDir, SaveOptions.REPLACE_EXISTING)) {
                  pkgModifications.increment();
               }
            }

            // remove obsolete files
            Files.walk(config.outputDir.resolve(packageName.replace('.', File.separatorChar))) //
               .filter(Files::isRegularFile) //
               .filter(p -> p.getFileName().toString().endsWith(ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX)) //
               .forEach(path -> {
                  final var relativePath = config.outputDir.relativize(path);
                  if (!eeaFiles.containsKey(relativePath)) {
                     LOG.log(Level.WARNING, "Removing obsolete annotation file [{0}]...", path.toAbsolutePath());
                     try {
                        Files.delete(path);
                        pkgModifications.increment();
                     } catch (final IOException ex) {
                        throw new RuntimeException(ex);
                     }
                  }
               });

            LOG.log(Level.INFO, "{0} EEA file(s) modified for package [{1}]", pkgModifications.longValue(), packageName);
            totalModifications += pkgModifications.longValue();
         }
         return totalModifications;
      } catch (final RuntimeException ex) {
         sanitizeStackTraces(ex);
         final var cause = ex.getCause();
         if (cause instanceof IOException)
            throw (IOException) cause;
         throw ex;
      }
   }

   /**
    * Recursively validates all EEA files for the given {@link Config#packages} in {@link Config#outputDir}.
    *
    * @return number of validated files
    * @throws IllegalArgumentException if no class was found
    */
   public static long validateEEAFiles(final Config config) throws IOException {
      try {
         long totalValidations = 0;
         for (final var packageName : config.packages) {
            LOG.log(Level.INFO, "Validating EEA files of package [{0}]...", packageName);

            final var computedEEAFiles = computeEEAFiles(packageName, config.classFilter);
            LOG.log(Level.INFO, "Found {0} types on classpath.", computedEEAFiles.size());

            final var pkgValidations = new LongAdder();
            final var packagePath = config.outputDir.resolve(packageName.replace('.', File.separatorChar));
            if (Files.exists(packagePath)) {
               Files.walk(packagePath) //
                  .filter(Files::isRegularFile) //
                  .filter(p -> p.getFileName().toString().endsWith(ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX)) //
                  .forEach(path -> {
                     pkgValidations.increment();
                     final var relativePath = config.outputDir.relativize(path);
                     var className = relativePath.toString().replace(File.separatorChar, '.');
                     className = className.substring(0, className.length() - ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX.length());

                     // try to parse the EEA file
                     final var parsedEEAFile = new EEAFile(className);
                     try {
                        parsedEEAFile.load(config.outputDir);
                     } catch (final IOException ex) {
                        throw new RuntimeException(ex);
                     }

                     // check if the type actually exists
                     final var computedEEAFile = computedEEAFiles.get(relativePath);
                     if (computedEEAFile == null)
                        throw new IllegalStateException("Type [" + className + "] not found on classpath [" + path + "]");

                     // check the EEA file does not contain declarations of non existing fields/methods
                     for (final var parsedMember : parsedEEAFile.getClassMembers().collect(Collectors.toList())) {
                        if (computedEEAFile.findMatchingClassMember(parsedMember).isEmpty()) {
                           final var candidates = computedEEAFile //
                              .getClassMembers().filter(m -> m.name.equals(parsedMember.name)) //
                              .map(m -> m.name + "\n" + " " + m.originalSignature) //
                              .collect(Collectors.joining("\n"));
                           throw new IllegalStateException("Unknown member declaration found in [" + path + "]: " + parsedMember
                              + (candidates.length() > 0 ? "\nPotential candidates: \n" + candidates : ""));
                        }
                     }
                  });
            }
            LOG.log(Level.INFO, "{0} EEA file(s) validated for package [{1}]", pkgValidations.longValue(), packageName);
            totalValidations += pkgValidations.longValue();
         }
         return totalValidations;
      } catch (final RuntimeException ex) {
         sanitizeStackTraces(ex);
         final var cause = ex.getCause();
         if (cause instanceof IOException)
            throw (IOException) cause;
         throw ex;
      }
   }
}
