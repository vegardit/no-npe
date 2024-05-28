/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import static com.vegardit.no_npe.eea_generator.internal.MiscUtils.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jdt.internal.compiler.classfmt.ExternalAnnotationProvider;

import com.vegardit.no_npe.eea_generator.internal.MiscUtils;

/**
 * Represents an .eea file.
 *
 * See https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations#File_layout
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class EEAFile {

   /**
    * Represents a type/class member, e.g. a field or method incl. it's original signature.
    */
   public static class ClassMember {

      public boolean isFollowedByEmptyLine = false;
      public final ValueWithComment name;

      /** Plain signature without external Null Analysis annotations */
      public final ValueWithComment originalSignature;

      /** Signature with external Null Analysis annotations */
      public @Nullable ValueWithComment annotatedSignature;

      public ClassMember(final String name, final String originalSignature) {
         this.name = ValueWithComment.parse(name);
         this.originalSignature = ValueWithComment.parse(originalSignature);
      }

      public ClassMember(final ValueWithComment name, final ValueWithComment originalSignature) {
         this.name = name;
         this.originalSignature = originalSignature;
      }

      @Override
      public String toString() {
         return name + System.lineSeparator() //
               + " " + originalSignature //
               + " " + annotatedSignature;
      }
   }

   public enum SaveOption {
      DELETE_IF_NO_ANNOTATABLE_MEMBERS,
      DELETE_IF_NO_ANNOTATED_SIGNATURES,
      OMIT_REDUNDANT_ANNOTATED_SIGNATURES,
      OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE,
      REPLACE_EXISTING,
      QUIET
   }

   public static final class ValueWithComment implements Cloneable {
      public static final char COMMENT_SEPARATOR = ' ';

      public static ValueWithComment parse(String text) {
         text = text.strip();
         final int separatorPos = text.indexOf(COMMENT_SEPARATOR);
         if (separatorPos == -1)
            return new ValueWithComment(text);
         return new ValueWithComment(text.substring(0, separatorPos), text.substring(separatorPos + 1));
      }

      public static String toString(final String value, @Nullable final String comment) {
         return comment == null || comment.isBlank() ? value : value + COMMENT_SEPARATOR + comment;
      }

      public String value;
      public @Nullable String comment;

      public ValueWithComment(final String value) {
         this.value = value;
      }

      public ValueWithComment(final String value, @Nullable final String comment) {
         this.value = value;
         this.comment = comment;
      }

      @Override
      public ValueWithComment clone() {
         return new ValueWithComment(value, comment);
      }

      public boolean hasComment() {
         return comment != null && !comment.isBlank();
      }

      @Override
      public String toString() {
         return toString(value, comment);
      }
   }

   private static final Logger LOG = System.getLogger(EEAFile.class.getName());

   /**
    * Used to match the 0/1 null annotation of types generic type variables, which is especially tricky
    * in cases such as
    *
    * <pre>
    * {@code
    * (L1com/example/L1;)V
    * <T1:Ljava/lang/Object;>(Ljava/lang/Class<T1T1;>)V
    * }
    * </pre>
    *
    * where the name of the type variable itself is T0 or T1 or when the class name itself is L0 or L1.
    */
   protected static final Pattern PATTERN_CAPTURE_NULL_ANNOTATION_OF_TYPENAMES = Pattern.compile(
      "[TL]([01])[a-zA-Z_][a-zA-Z_0-9$\\/*]*[<;]");

   public final ValueWithComment className;
   public @Nullable ValueWithComment classSignatureOriginal;
   public @Nullable ValueWithComment classSignatureAnnotated;
   public final Path relativePath;

   /** ordered list of declared class members */
   private final List<ClassMember> members = new ArrayList<>();

   /**
    * @throws IOException in case the file cannot be read or contains syntax errors
    */
   public static @Nullable EEAFile loadIfExists(final Path rootPath, final String className) throws IOException {
      final var eeaFilePath = rootPath.resolve(classNameToRelativePath(className));
      if (!Files.exists(eeaFilePath)) {
         LOG.log(Level.DEBUG, "File [{0}] does not exist, skipping.", eeaFilePath);
         return null;
      }
      return load(eeaFilePath);
   }

   /**
    * @throws IOException in case the file cannot be read or contains syntax errors
    */
   public static EEAFile load(final Path rootPath, final String className) throws IOException {
      final var eeaFilePath = rootPath.resolve(classNameToRelativePath(className));
      return load(eeaFilePath);
   }

   /**
    * @throws IOException in case the file cannot be read or contains syntax errors
    */
   public static EEAFile load(final Path filePath) throws IOException {
      try (var reader = Files.newBufferedReader(filePath)) {
         return load(reader, filePath.toString());
      }
   }

   public static EEAFile load(final BufferedReader reader, final String path) throws IOException {
      final Deque<String> lines = reader.lines().collect(Collectors.toCollection(ArrayDeque::new));

      // read type header
      String line = lines.pollFirst();
      int lineNumber = 1;
      assert line != null;
      final var classNameRaw = ValueWithComment.parse(line.substring(ExternalAnnotationProvider.CLASS_PREFIX.length()));
      ExternalAnnotationProvider.assertClassHeader(line, classNameRaw.value);

      final var eeaFile = new EEAFile(classNameRaw.value.replace('/', '.'));
      eeaFile.className.comment = classNameRaw.comment;

      if (!path.replace('\\', '/').endsWith(eeaFile.relativePath.toString().replace('\\', '/')))
         throw new IOException("Mismatch between file path of [" + path + "] and contained class name definition ["
               + eeaFile.className.value + "]");

      // read type signature if present
      line = lines.peekFirst();
      if (line != null && !line.isBlank() && line.startsWith(" <" /* ExternalAnnotationProvider.TYPE_PARAMETER_PREFIX */ )) {
         lines.removeFirst();
         lineNumber++;
         eeaFile.classSignatureOriginal = ValueWithComment.parse(line);

         line = lines.peekFirst();
         if (line != null && !line.isBlank() && line.startsWith(" <" /* ExternalAnnotationProvider.TYPE_PARAMETER_PREFIX */ )) {
            lines.removeFirst();
            lineNumber++;
            eeaFile.classSignatureAnnotated = ValueWithComment.parse(line);
         }
      }

      // read type members
      while ((line = lines.pollFirst()) != null) {
         lineNumber++;
         if (line.isBlank()) {
            eeaFile.addEmptyLine();
            continue;
         }

         // read and validate class member, i.e. field or method name
         if (line.startsWith(" "))
            throw new IOException("Illegal format for field or method name [" + line + "] at " + path + ":" + lineNumber);
         final var memberName = ValueWithComment.parse(line);

         // read mandatory original signature
         line = lines.pollFirst();
         lineNumber++;
         if (line == null || line.isBlank() || !line.startsWith(" "))
            throw new IOException("Illegal format for original signature at " + path + ":" + lineNumber);
         final var originalSignature = ValueWithComment.parse(line);
         if (!originalSignature.value.equals(eeaFile.removeNullAnnotations(originalSignature.value)))
            throw new IOException("Original signature contains null annotations at " + path + ":" + lineNumber);

         final var member = new ClassMember(memberName, originalSignature);
         if (eeaFile.members.contains(member))
            throw new IOException("Duplicate entry \"" + memberName.value + " " + originalSignature.value + "\" found at " + path + ":"
                  + lineNumber);

         // read optional annotated signature
         line = lines.peekFirst();
         if (line != null && !line.isBlank() && line.startsWith(" ")) {
            lines.removeFirst();
            lineNumber++;
            final var annotatedSignature = ValueWithComment.parse(line);
            if (!originalSignature.value.equals(annotatedSignature.value) //
                  && !originalSignature.value.equals(eeaFile.removeNullAnnotations(annotatedSignature.value)))
               throw new IOException("Signature mismatch at " + path + ":" + lineNumber + "\n" //
                     + "          Original: " + originalSignature + "\n" //
                     + "Annotated Stripped: " + eeaFile.removeNullAnnotations(annotatedSignature.value) + "\n" //
                     + "         Annotated: " + annotatedSignature + "\n");
            if (!originalSignature.value.equals(annotatedSignature.value) || annotatedSignature.hasComment()) {
               member.annotatedSignature = annotatedSignature;
            }
         }

         // store the parsed member entry
         eeaFile.members.add(member);
      }
      return eeaFile;
   }

   private static Path classNameToRelativePath(final String className) {
      return Path.of(className.replace('.', File.separatorChar) + ExternalAnnotationProvider.ANNOTATION_FILE_SUFFIX);
   }

   public EEAFile(final String className) {
      this(className, classNameToRelativePath(className));
   }

   private EEAFile(final String className, final Path relativePath) {
      this.className = new ValueWithComment(className);
      this.relativePath = relativePath;
   }

   public void addEmptyLine() {
      final var lastMember = MiscUtils.findLastElement(members);
      if (lastMember != null) {
         lastMember.isFollowedByEmptyLine = true;
      }
   }

   /**
    * Adds a new member declaration to the end of the file
    */
   public void addMember(final ClassMember member) {
      members.add(member);
   }

   /**
    * Adds a new member declaration to the end of the file
    */
   public ClassMember addMember(final String name, final String originalSignature) {
      final var member = new ClassMember(name, originalSignature);
      members.add(member);
      return member;
   }

   /**
    * @return a class member with the same name and the same original signature
    */
   public @Nullable ClassMember findMatchingClassMember(final ClassMember member) {
      return findMatchingClassMember(member.name.value, member.originalSignature.value);
   }

   /**
    * @return a class member with the same name and the same original signature
    */
   public @Nullable ClassMember findMatchingClassMember(final String name, final String originalSignature) {
      return getClassMembers() //
         .filter(m -> m.name.value.equals(name) //
               && m.originalSignature.value.equals(originalSignature)) //
         .findFirst() //
         .orElse(null);
   }

   /**
    * Copies annotated signatures for compatible class members from the given EEA file
    *
    * @param overrideOnConflict if true existing annotated signatures are overridden
    */
   public void applyAnnotationsAndCommentsFrom(final EEAFile src, final boolean overrideOnConflict) {
      LOG.log(Level.DEBUG, "Applying annotations from [{0}]...", src.relativePath);

      // copy class name comment
      if (overrideOnConflict || !className.hasComment()) {
         className.comment = src.className.comment;
      }

      members.forEach(ourMember -> {
         final var theirMember = src.findMatchingClassMember(ourMember);
         if (theirMember == null)
            return;

         // copy member name comment
         if (overrideOnConflict || !ourMember.name.hasComment()) {
            ourMember.name.comment = theirMember.name.comment;
         }

         // copy original signature comment
         if (overrideOnConflict || !ourMember.originalSignature.hasComment()) {
            ourMember.originalSignature.comment = theirMember.originalSignature.comment;
         }

         // copy annotated signature or it's comment
         final var theirAnnotatedSignature = theirMember.annotatedSignature;
         if (theirAnnotatedSignature != null) {
            final var ourAnnotatedSignature = ourMember.annotatedSignature;
            if (overrideOnConflict || ourAnnotatedSignature == null) {
               ourMember.annotatedSignature = new ValueWithComment(theirAnnotatedSignature.value, theirAnnotatedSignature.comment);
            } else if (!ourAnnotatedSignature.hasComment()) {
               ourAnnotatedSignature.comment = theirAnnotatedSignature.comment;
            }
         }
      });
   }

   /**
    * @return true if a corresponding EEAFile exists on the local file system
    */
   public boolean exists(final Path rootPath) {
      return Files.exists(rootPath.resolve(relativePath));
   }

   public Stream<ClassMember> getClassMembers() {
      return members.stream();
   }

   protected String renderFileContent(final boolean omitRedundantAnnotatedSignatures, final boolean omitMembersWithoutAnnotatedSignature)
         throws IOException {
      final var sb = new StringBuilder();
      writeLine(sb, ExternalAnnotationProvider.CLASS_PREFIX, new ValueWithComment(className.value.replace('.', '/'), className.comment));
      if (classSignatureOriginal != null) {
         writeLine(sb, " ", classSignatureOriginal);
         if (classSignatureAnnotated != null) {
            writeLine(sb, " ", classSignatureAnnotated);
         }
      }
      writeLine(sb);

      final ClassMember lastMember = findLastElement(members);
      for (final ClassMember member : members) {
         var annotatedSig = member.annotatedSignature;
         if (omitMembersWithoutAnnotatedSignature && annotatedSig == null) {
            continue;
         }

         writeLine(sb, member.name);
         writeLine(sb, " ", member.originalSignature);
         if (annotatedSig == null && !omitRedundantAnnotatedSignatures) {
            annotatedSig = member.originalSignature;
         }
         if (annotatedSig != null) {
            writeLine(sb, " ", annotatedSig);
         }
         if (member != lastMember && member.isFollowedByEmptyLine) {
            writeLine(sb);
         }
      }
      return sb.toString();
   }

   /**
    * see https://wiki.eclipse.org/JDT_Core/Null_Analysis/External_Annotations#Textual_encoding_of_signatures
    */
   protected String removeNullAnnotations(final String annotatedSignature) {
      var strippedSignature = annotatedSignature //
         .replace("[0", "[") //
         .replace("[1", "[") //
         .replace("-0", "-") //
         .replace("-1", "-") //
         .replace("+0", "+") //
         .replace("+1", "+") //
         .replace("*0", "*") //
         .replace("*1", "*");

      strippedSignature = replaceAll(strippedSignature, PATTERN_CAPTURE_NULL_ANNOTATION_OF_TYPENAMES, 1, match -> "");
      return strippedSignature;
   }

   /**
    * @return true if modifications where written to disk, false was already up-to-date
    */
   public boolean save(final Path rootPath, final @Nullable SaveOption... options) throws IOException {
      return save(rootPath, Arrays.stream(options).filter(Objects::nonNull).collect(Collectors.toSet()));
   }

   /**
    * @return true if modifications where written to disk, false was already up-to-date
    */
   public boolean save(final Path rootPath, final Set<SaveOption> options) throws IOException {
      final var path = rootPath.resolve(relativePath);

      final boolean replaceExisting = options.contains(SaveOption.REPLACE_EXISTING);
      final boolean deleteIfNoAnnotatableMembers = options.contains(SaveOption.DELETE_IF_NO_ANNOTATABLE_MEMBERS);
      final boolean deleteIfNoAnnotatedSignatures = options.contains(SaveOption.DELETE_IF_NO_ANNOTATED_SIGNATURES);
      final boolean omitRedundantAnnotatedSignatures = options.contains(SaveOption.OMIT_REDUNDANT_ANNOTATED_SIGNATURES);
      final boolean omitMembersWithoutAnnotatedSignature = options.contains(SaveOption.OMIT_MEMBERS_WITHOUT_ANNOTATED_SIGNATURE);
      final boolean quiet = options.contains(SaveOption.QUIET);

      final var content = renderFileContent(omitRedundantAnnotatedSignatures, omitMembersWithoutAnnotatedSignature);

      if (exists(rootPath)) {

         if (replaceExisting) {

            if (deleteIfNoAnnotatableMembers && members.isEmpty()) {
               LOG.log(Level.WARNING, "Deleting [{0}] (reason: no members)...", path.toAbsolutePath());
               Files.deleteIfExists(path);
               return true;
            }

            if (deleteIfNoAnnotatedSignatures && members.stream().noneMatch(m -> m.annotatedSignature != null)) {
               LOG.log(Level.WARNING, "Deleting [{0}] (reason: no annotated signatures)...", path.toAbsolutePath());
               Files.deleteIfExists(path);
               return true;
            }

            final boolean needsUpdate = !normalizeNewLines(content) //
               .equals(normalizeNewLines(Files.readString(rootPath.resolve(relativePath))));
            if (!needsUpdate) {
               LOG.log(Level.DEBUG, "Skipped saving [{0}] (reason: unchanged).", path.toAbsolutePath());
               return false;
            }

            if (!quiet) {
               LOG.log(Level.INFO, "Updating [{0}]...", path.toAbsolutePath());
            }

         } else { // !replaceExisting

            final boolean needsUpdate = !normalizeNewLines(content) //
               .equals(normalizeNewLines(Files.readString(rootPath.resolve(relativePath))));
            if (!needsUpdate) {
               LOG.log(Level.DEBUG, "Skipped saving [{0}] (reason: unchanged).", path.toAbsolutePath());
               return false;
            }
            throw new IOException("File [" + path + "] already exists!");
         }

      } else { // !exists(rootPath)

         if (deleteIfNoAnnotatableMembers && members.isEmpty()) {
            LOG.log(Level.DEBUG, "Skipped creating [{0}] (reason: no members).", path.toAbsolutePath());
            return false;
         }

         if (deleteIfNoAnnotatedSignatures && members.stream().noneMatch(m -> m.annotatedSignature != null)) {
            LOG.log(Level.DEBUG, "Skipped creating [{0}] (reason: no annotated signatures).", path.toAbsolutePath());
            return false;
         }

         if (!quiet) {
            LOG.log(Level.INFO, "Creating [{0}]...", path.toAbsolutePath());
         }

         final var parentDir = path.getParent();
         assert parentDir != null;
         Files.createDirectories(parentDir);
      }

      final var openOpts = replaceExisting //
            ? List.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) //
            : List.of(StandardOpenOption.CREATE_NEW);
      Files.writeString(path, content, openOpts.toArray(OpenOption[]::new));
      return true;
   }
}
