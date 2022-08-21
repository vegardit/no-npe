/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;

import java.io.BufferedReader;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;

/**
 * Represents an .eea file.
 *
 * See https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations#File_layout
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class EEAFile {

   public static class ClassMember implements Entry, Comparable<ClassMember> {
      public final String name;

      /** Signature without Null Analysis annotations */
      public final String originalSignature;

      public ClassMember(final String name, final String originalSignature) {
         this.name = name;
         this.originalSignature = originalSignature;
      }

      @Override
      public int compareTo(final ClassMember o) {
         int rc = name.compareTo(o.name);
         if (rc == 0) {
            rc = originalSignature.compareTo(o.originalSignature);
         }
         return rc;
      }

      @Override
      public boolean equals(final Object obj) {
         if (this == obj)
            return true;
         if (obj == null || getClass() != obj.getClass())
            return false;
         final ClassMember other = (ClassMember) obj;
         return Objects.equals(name, other.name) && Objects.equals(originalSignature, other.originalSignature);
      }

      @Override
      public int hashCode() {
         return Objects.hash(name, originalSignature);
      }

      @Override
      public String toString() {
         return name + " " + originalSignature;
      }
   }

   public interface Entry {
   }

   public enum LoadOptions {
      IGNORE_NONE_EXISTING
   }

   public enum SaveOptions {
      SAVE_EMPTY,
      REPLACE_EXISTING
   }

   private static final Logger LOG = System.getLogger(EEAFile.class.getName());

   private static final EEAFile TEMPLATE_SERIALIZABLE;
   private static final EEAFile TEMPLATE_EXTERNALIZABLE;
   private static final EEAFile TEMPLATE_OBJECT;
   private static final EEAFile TEMPLATE_THROWABLE;

   static {
      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Serializable.eea")) {
         TEMPLATE_SERIALIZABLE = new EEAFile(Serializable.class.getName());
         TEMPLATE_SERIALIZABLE.load("classpath:org/no_npe/utils/Serializable.eea", reader);
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Externalizable.eea")) {
         TEMPLATE_EXTERNALIZABLE = new EEAFile(Externalizable.class.getName());
         TEMPLATE_EXTERNALIZABLE.load("classpath:org/no_npe/utils/Externalizable.eea", reader);
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Object.eea")) {
         TEMPLATE_OBJECT = new EEAFile(Object.class.getName());
         TEMPLATE_OBJECT.load("classpath:org/no_npe/utils/Object.eea", reader);
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }

      try (var reader = getUTF8ResourceAsReader(EEAFile.class, "Throwable.eea")) {
         TEMPLATE_THROWABLE = new EEAFile(Throwable.class.getName());
         TEMPLATE_THROWABLE.load("classpath:org/no_npe/utils/Throwable.eea", reader);
      } catch (final Exception ex) {
         throw new IllegalStateException(ex);
      }
   }

   public static final Entry EMPTY_LINE = new Entry() {
      @Override
      public String toString() {
         return "EMPTY_LINE";
      }
   };

   public final Path relativePath;
   public final String className;

   /** ordered list of class member entries and empty-line placeholders */
   private final List<Entry> entries = new ArrayList<>();
   private final Map<ClassMember, String> annotatedSignatures = new HashMap<>();

   public EEAFile(final EEAFile original) {
      this(original.className);
      entries.addAll(original.entries);
      annotatedSignatures.putAll(original.annotatedSignatures);
   }

   public EEAFile(final String className) {
      if (className == null || className.isBlank())
         throw new IllegalArgumentException("[className] is null or blank!");

      this.className = className;
      relativePath = Path.of(className.replace('.', File.separatorChar) + ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX);
   }

   public void addEmptyLine() {
      if (!entries.isEmpty() && getLastElement(entries) != EMPTY_LINE) {
         entries.add(EMPTY_LINE);
      }
   }

   public void addMember(final ClassMember member) {
      entries.add(member);
   }

   public ClassMember addMember(final String name, final String originalSignature) {
      final var member = new ClassMember(name, originalSignature);
      entries.add(member);
      return member;
   }

   /**
    * Copies annotated signatures for compatible class members from the given EEA file
    *
    * @param overrideOnConflict if true existing annotated signatures are overriden
    */
   public void applyAnnotatedSignaturesFrom(final EEAFile source, final boolean overrideOnConflict) {
      LOG.log(Level.DEBUG, "Applying annotations from [{0}]...", source.relativePath);
      getClassMembers().forEach(member -> {
         final var theirSig = source.annotatedSignatures.get(member);
         if (theirSig != null) {
            if (overrideOnConflict) {
               annotatedSignatures.put(member, theirSig);
            } else {
               annotatedSignatures.putIfAbsent(member, theirSig);
            }
         }
      });
   }

   /**
    * Tries to compute annotate signatures for members without null annotations.
    * This method uses simple heuristics.
    */
   public void computeAnnotatedSignatures() {
      getClassMembers().forEach(member -> {
         if (!annotatedSignatures.containsKey(member)) {
            final var guessedSig = guessAnnotatedSignature(member);
            if (guessedSig != null) {
               setAnnotatedSignature(member, guessedSig);
            }
         }
      });
   }

   public boolean containsMember(final ClassMember member) {
      return entries.contains(member);
   }

   @Override
   public boolean equals(final Object obj) {
      if (this == obj)
         return true;
      if (obj == null || getClass() != obj.getClass())
         return false;
      final EEAFile other = (EEAFile) obj;
      return Objects.equals(className, other.className) //
         && Objects.equals(entries, other.entries) //
         && Objects.equals(annotatedSignatures, other.annotatedSignatures);
   }

   /**
    * @return true if a corresponding EEAFile exists on the local file sytem
    */
   public boolean exists(final Path rootPath) {
      return Files.exists(rootPath.resolve(relativePath));
   }

   /**
    * @return null if unknown member or same as original signature
    */
   public String getAnnotatedSignature(final ClassMember member) {
      return annotatedSignatures.get(member);
   }

   public Stream<ClassMember> getClassMembers() {
      return entries.stream() //
         .filter(ClassMember.class::isInstance) //
         .map(ClassMember.class::cast);
   }

   public Stream<ClassMember> getClassMembersWithName(final String name) {
      return getClassMembers() //
         .filter(m -> m.name.equals(name));
   }

   protected String guessAnnotatedSignature(final ClassMember member) {
      if (className.endsWith("Exception") || className.endsWith("Error")) {
         final var sig = TEMPLATE_THROWABLE.annotatedSignatures.get(member);
         if (sig != null)
            return sig;
      }

      var sig = TEMPLATE_EXTERNALIZABLE.annotatedSignatures.get(member);
      if (sig != null)
         return sig;

      sig = TEMPLATE_SERIALIZABLE.annotatedSignatures.get(member);
      if (sig != null)
         return sig;

      return TEMPLATE_OBJECT.annotatedSignatures.get(member);
   }

   @Override
   public int hashCode() {
      return Objects.hash(annotatedSignatures, entries, relativePath, className);
   }

   /**
    * Populates this instance with the content of a corresponding file on the local file system.
    *
    * @throws IOException in case the file cannot be read or contains syntax errors
    */
   public void load(final Path rootPath, final LoadOptions... options) throws IOException {

      final var path = rootPath.resolve(relativePath);

      if (arrayContains(options, LoadOptions.IGNORE_NONE_EXISTING) && !exists(rootPath)) {
         LOG.log(Level.DEBUG, "File [{0}] does not exist, skipping.", path);
         return;
      }

      try (var r = Files.newBufferedReader(path)) {
         load(path.toAbsolutePath().toString(), r);
      }
   }

   protected void load(final String path, final BufferedReader eeaFile) throws IOException {
      LOG.log(Level.DEBUG, "Loading [{0}]...", path);
      entries.clear();
      annotatedSignatures.clear();

      // read class name header
      final String header = eeaFile.readLine();
      ExternalAnnotationProvider.assertClassHeader(header, className.replace('.', '/'));

      int lineNumber = 1;
      String line;

      while ((line = eeaFile.readLine()) != null) {
         lineNumber++;
         if (line.strip().isBlank()) {
            addEmptyLine();
            continue;
         }

         // read and validate class member, i.e. field or method name
         if (line.startsWith(" ") || line.strip().contains(" "))
            throw new IOException("Illegal format for field or method name [" + line + "] at " + path + ":" + lineNumber);
         final var memberName = line.strip();

         // read and validate original signature
         final var originalSignature = ExternalAnnotationProvider.extractSignature(eeaFile.readLine());
         lineNumber++;
         if (originalSignature == null)
            throw new IOException("Illegal format for original signature at " + path + ":" + lineNumber);
         if (!originalSignature.equals(removeNullAnnotations(originalSignature)))
            throw new IOException("Original signature contains null annotations at " + path + ":" + lineNumber);

         final var entry = new ClassMember(memberName, originalSignature);
         if (entries.contains(entry))
            throw new IOException("Duplicate entry \"" + memberName + " " + originalSignature + "\" found at " + path + ":" + lineNumber);

         // read and validate annotated signature
         final var annotatedSignature = ExternalAnnotationProvider.extractSignature(eeaFile.readLine());
         lineNumber++;
         if (annotatedSignature == null)
            throw new IOException("Illegal format for annotated signature at " + path + ":" + lineNumber);
         if (!originalSignature.equals(removeNullAnnotations(annotatedSignature)))
            throw new IOException("Signature mismatch at " + path + ":" + lineNumber + "\n" //
               + "1: " + originalSignature + "\n" //
               + "2: " + annotatedSignature + "\n");

         // store the parsed member entry
         entries.add(entry);
         if (!originalSignature.equals(annotatedSignature)) {
            annotatedSignatures.put(entry, annotatedSignature);
         }
      }
      removeTrailingEmptyLines();
   }

   /**
    * see https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations#Textual_encoding_of_signatures
    */
   protected String removeNullAnnotations(final String annotatedSignature) {
      return annotatedSignature //
         .replace("[0", "[") //
         .replace("[1", "[") //
         .replace("L0", "L") //
         .replace("L1", "L") //
         .replace("T0", "T") //
         .replace("T1", "T") //
         .replace("-0", "-") //
         .replace("-1", "-") //
         .replace("+0", "+") //
         .replace("+1", "+") //
         .replace("*0", "*") //
         .replace("*1", "*");
   }

   protected void removeTrailingEmptyLines() {
      while (!entries.isEmpty() && entries.get(entries.size() - 1) == EMPTY_LINE) {
         entries.remove(entries.size() - 1);
      }
   }

   /**
    * @return true if modifications where written to disk, false was already up-to-date
    */
   public boolean save(final Path rootPath, final SaveOptions... options) throws IOException {
      final var path = rootPath.resolve(relativePath);

      removeTrailingEmptyLines();

      final boolean exists = exists(rootPath);
      final boolean replaceExisting = arrayContains(options, SaveOptions.REPLACE_EXISTING);
      final boolean saveEmpty = arrayContains(options, SaveOptions.SAVE_EMPTY);

      // create a copy of the this EEAFile instance and register annotated signatures that can be computed/guessed.
      // this instance is used to compare against the EEA file on the local file system to decide if an update is necessary.
      final var thisWithComputedAnnotations = new EEAFile(this);
      thisWithComputedAnnotations.computeAnnotatedSignatures();

      if (exists) {
         var original = new EEAFile(className);
         try {
            original.load(rootPath);
         } catch (final Exception ex) {
            original = null;
         }

         if (replaceExisting) {
            if (!saveEmpty && entries.isEmpty()) {
               LOG.log(Level.WARNING, "Deleting empty file [{0}]...", path.toAbsolutePath());
               Files.deleteIfExists(path);
               return true;
            }
            if (thisWithComputedAnnotations.equals(original)) {
               LOG.log(Level.DEBUG, "Skipping saving unchanged file [{0}]...", path.toAbsolutePath());
               return false;
            }
            LOG.log(Level.INFO, "Updating [{0}]...", path.toAbsolutePath());
         } else {
            if (thisWithComputedAnnotations.equals(original)) {
               LOG.log(Level.DEBUG, "Skipping saving unchanged file [{0}]...", path.toAbsolutePath());
               return false;
            }
            throw new IOException("File [" + path + "] already exists!");
         }
      } else {
         if (!saveEmpty && entries.isEmpty()) {
            LOG.log(Level.DEBUG, "Skip creating empty file [{0}]...", path.toAbsolutePath());
            return false;
         }
         LOG.log(Level.INFO, "Creating [{0}]...", path.toAbsolutePath());
         Files.createDirectories(path.getParent());
      }

      final var openOpts = replaceExisting //
         ? List.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) //
         : List.of(StandardOpenOption.CREATE_NEW);
      try (var w = Files.newBufferedWriter(path, openOpts.toArray(OpenOption[]::new))) {

         writeLine(w, ExternalAnnotationProvider.CLASS_PREFIX, className.replace('.', '/'));
         writeLine(w);

         int i = 0;
         for (final var entry : entries) {
            i++;
            if (entry == EMPTY_LINE) {
               if (i != entries.size()) {
                  writeLine(w);
               }
            } else {
               final var member = (ClassMember) entry;
               writeLine(w, member.name);
               writeLine(w, " ", member.originalSignature);
               writeLine(w, " ", thisWithComputedAnnotations.annotatedSignatures.getOrDefault(member, member.originalSignature));
            }
         }
      }
      return true;
   }

   public void setAnnotatedSignature(final ClassMember member, final String annotatedSignature) {
      if (!entries.contains(member))
         throw new IllegalArgumentException("[member] Not found: " + member);

      if (annotatedSignature == null || member.originalSignature.equals(annotatedSignature)) {
         annotatedSignatures.remove(member);
      } else {
         annotatedSignatures.put(member, annotatedSignature);
      }
   }
}
