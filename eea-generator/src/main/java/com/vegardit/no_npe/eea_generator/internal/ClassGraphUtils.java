/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import io.github.classgraph.AnnotationEnumValue;
import io.github.classgraph.AnnotationInfo;
import io.github.classgraph.AnnotationInfoList;
import io.github.classgraph.ArrayTypeSignature;
import io.github.classgraph.BaseTypeSignature;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.FieldInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList;
import io.github.classgraph.TypeSignature;

/**
 * Provides filtered ClassGraph member views and nullness-annotation classification for EEA inference.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public final class ClassGraphUtils {

   private static final String JSR305_NONNULL_ANNOTATION = "javax.annotation.Nonnull";
   private static final String JSR305_TYPE_QUALIFIER_NICKNAME_ANNOTATION = "javax.annotation.meta.TypeQualifierNickname";
   private static final String JSR305_NONNULL_DESCRIPTOR = "Ljavax/annotation/Nonnull;";
   private static final String JSR305_TYPE_QUALIFIER_NICKNAME_DESCRIPTOR = "Ljavax/annotation/meta/TypeQualifierNickname;";
   private static final Map<String, Optional<String>> JSR305_NICKNAME_WHEN_CACHE = new ConcurrentHashMap<>();

   public enum MethodReturnKind {
      ARRAY,
      PRIMITIVE,
      OBJECT,
      VOID
   }

   private static final Set<String> NULLABLE_ANNOTATIONS = Set.of( //
      "android.annotation.Nullable", //
      "android.support.annotation.Nullable", //
      "androidx.annotation.Nullable", //
      "com.mongodb.lang.Nullable", //
      "com.sun.istack.internal.Nullable", //
      "edu.umd.cs.findbugs.annotations.Nullable", //
      "io.reactivex.annotations.Nullable", //
      "io.reactivex.rxjava3.annotations.Nullable", //
      "io.smallrye.common.constraint.Nullable", //
      "io.vertx.codegen.annotations.Nullable", //
      "jakarta.annotation.CheckForNull", //
      "jakarta.annotation.Nullable", //
      "javax.annotation.CheckForNull", //
      "javax.annotation.Nullable", //
      "net.bytebuddy.utility.nullability.AlwaysNull", //
      "net.bytebuddy.utility.nullability.MaybeNull", //
      "org.checkerframework.checker.nullness.compatqual.NullableDecl", //
      "org.checkerframework.checker.nullness.compatqual.NullableType", //
      "org.checkerframework.checker.nullness.qual.Nullable", //
      "org.eclipse.jdt.annotation.Nullable", //
      "org.eclipse.sisu.Nullable", //
      "org.jetbrains.annotations.Nullable", //
      "org.jspecify.annotations.Nullable", //
      "org.jmlspecs.annotation.Nullable", //
      "org.netbeans.api.annotations.common.CheckForNull", //
      "org.netbeans.api.annotations.common.NullAllowed", //
      "org.netbeans.api.annotations.common.NullUnknown", //
      "org.springframework.lang.Nullable", //
      "org.sonatype.inject.Nullable", //
      "org.wildfly.common.annotation.Nullable", //
      "reactor.util.annotation.Nullable");

   private static final Set<String> NONNULL_ANNOTATIONS = Set.of( //
      "android.annotation.NonNull", //
      "android.support.annotation.NonNull", //
      "androidx.annotation.NonNull", //
      "com.sun.istack.internal.NotNull", //
      "com.mongodb.lang.NonNull", //
      "edu.umd.cs.findbugs.annotations.NonNull", //
      "io.reactivex.annotations.NonNull", //
      "io.reactivex.rxjava3.annotations.NonNull", //
      JSR305_NONNULL_ANNOTATION, //
      "javax.validation.constraints.NotNull", //
      "jakarta.annotation.Nonnull", //
      "jakarta.validation.constraints.NotNull", //
      "lombok.NonNull", //
      "net.bytebuddy.utility.nullability.NeverNull", //
      "org.checkerframework.checker.nullness.compatqual.NonNullDecl", //
      "org.checkerframework.checker.nullness.compatqual.NonNullType", //
      "org.checkerframework.checker.nullness.qual.NonNull", //
      "org.eclipse.jdt.annotation.NonNull", //
      "org.jetbrains.annotations.NotNull", //
      "org.jspecify.annotations.NonNull", //
      "org.jmlspecs.annotation.NonNull", //
      "org.netbeans.api.annotations.common.NonNull", //
      "org.springframework.lang.NonNull", //
      "org.wildfly.common.annotation.NotNull", //
      "reactor.util.annotation.NonNull");

   public static Set<ClassInfo> getDirectInterfaces(final ClassInfo classInfo) {
      final var directInterfaces = new HashSet<>(classInfo.getInterfaces());
      if (classInfo.isInterface()) {
         classInfo.getInterfaces().forEach(superIFace -> directInterfaces.removeAll(superIFace.getInterfaces()));
      } else {
         ClassInfo superclassInfo = classInfo.getSuperclass();
         while (superclassInfo != null) {
            directInterfaces.removeAll(superclassInfo.getInterfaces());
            superclassInfo = superclassInfo.getSuperclass();
         }
      }
      return directInterfaces;
   }

   /**
    * @param selectStatic if true static fields are returned otherwise instance fields
    * @return a sorted set of {@link FieldInfo} instances for all public or protected non-synthetic non-primitive fields
    */
   private static SortedSet<FieldInfo> getFilteredAndSortedFields(final FieldInfoList fields, final boolean selectStatic) {
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
   private static SortedSet<MethodInfo> getFilteredAndSortedMethods(final MethodInfoList methods, final boolean selectStatic) {
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

   public static SortedSet<FieldInfo> getInstanceFields(final FieldInfoList fields) {
      return getFilteredAndSortedFields(fields, false);
   }

   public static SortedSet<MethodInfo> getInstanceMethods(final MethodInfoList methods) {
      return getFilteredAndSortedMethods(methods, false);
   }

   /**
    * Determines the return kind of the given method.
    * This method distinguishes between methods that return objects, arrays, primitive types, or void.
    *
    * @param methodInfo the method whose return type is to be checked
    * @return MethodReturnKind representing if the method returns an object, array, primitive, or void
    */
   public static MethodReturnKind getMethodReturnKind(final MethodInfo methodInfo) {
      final TypeSignature returnType = methodInfo.getTypeDescriptor().getResultType();
      if (returnType == null)
         return MethodReturnKind.VOID;
      if (returnType instanceof BaseTypeSignature)
         return MethodReturnKind.PRIMITIVE;
      if (returnType instanceof ArrayTypeSignature)
         return MethodReturnKind.ARRAY;
      if (returnType instanceof ClassRefTypeSignature)
         return MethodReturnKind.OBJECT;
      throw new IllegalStateException("Unknown method return kind: " + returnType);
   }

   public static SortedSet<FieldInfo> getStaticFields(final FieldInfoList fields) {
      return getFilteredAndSortedFields(fields, true);
   }

   public static SortedSet<MethodInfo> getStaticMethods(final MethodInfoList methods) {
      return getFilteredAndSortedMethods(methods, true);
   }

   public static boolean hasNonNullAnnotation(final AnnotationInfoList annos) {
      for (final var anno : annos) {
         final String annotationName = anno.getName();
         if (JSR305_NONNULL_ANNOTATION.equals(annotationName)) {
            // Declaration annotation lists can contain an expanded meta-annotation. JSR-305 defaults to ALWAYS, but its
            // weaker values must not be collapsed into an unsound non-null EEA marker.
            if ("ALWAYS".equals(getJsr305NonnullWhen(anno)))
               return true;
            continue;
         }
         if (NONNULL_ANNOTATIONS.contains(annotationName))
            return true;

         if ("ALWAYS".equals(getJsr305NicknameWhen(anno)))
            return true;
      }
      return false;
   }

   public static boolean hasNonNullAnnotation(final AnnotationInfoList annos, final @Nullable AnnotationInfoList typeAnnos) {
      // ClassGraph returns null, rather than an empty list, when the type-use has no annotations.
      return hasNonNullAnnotation(annos) || typeAnnos != null && hasNonNullAnnotation(typeAnnos);
   }

   public static boolean hasNullableAnnotation(final AnnotationInfoList annos) {
      for (final var anno : annos) {
         if (NULLABLE_ANNOTATIONS.contains(anno.getName()))
            return true;

         final String when = getJsr305NonnullWhen(anno);
         // MAYBE permits null and NEVER means the @Nonnull predicate never holds. UNKNOWN provides no usable contract.
         if ("MAYBE".equals(when) || "NEVER".equals(when))
            return true;

         final String nicknameWhen = getJsr305NicknameWhen(anno);
         if ("MAYBE".equals(nicknameWhen) || "NEVER".equals(nicknameWhen))
            return true;
      }
      return false;
   }

   public static boolean hasNullableAnnotation(final AnnotationInfoList annos, final @Nullable AnnotationInfoList typeAnnos) {
      return hasNullableAnnotation(annos) || typeAnnos != null && hasNullableAnnotation(typeAnnos);
   }

   public static @Nullable AnnotationInfoList getTopLevelValueTypeAnnotationInfo(final @Nullable TypeSignature typeSignature) {
      if (typeSignature == null)
         return null;
      if (typeSignature instanceof ClassRefTypeSignature) {
         final var classRefType = (ClassRefTypeSignature) typeSignature;
         if (!classRefType.getSuffixes().isEmpty()) {
            /* ClassGraph stores Outer annotations on the root and Inner annotations on the final suffix. The Java value
             * has type Inner, while ECJ's EEA marker after the initial L qualifies that complete nested-class value. An
             * unannotated suffix must stay unknown; falling back to the root would promote an Outer-only annotation. */
            final var suffixAnnotations = classRefType.getSuffixTypeAnnotationInfo();
            return suffixAnnotations == null || suffixAnnotations.isEmpty() ? null : suffixAnnotations.get(suffixAnnotations.size() - 1);
         }
      }
      return typeSignature.getTypeAnnotationInfo();
   }

   private static @Nullable String getJsr305NonnullWhen(final AnnotationInfo annotation) {
      if (!JSR305_NONNULL_ANNOTATION.equals(annotation.getName()))
         return null;

      final Object when = annotation.getParameterValues().getValue("when");
      return when == null ? "ALWAYS" : when instanceof AnnotationEnumValue ? ((AnnotationEnumValue) when).getValueName() : null;
   }

   private static @Nullable String getJsr305NicknameWhen(final AnnotationInfo annotation) {
      // Only the explicit JSR-305 meta-annotation grants alias semantics; annotation names alone are not evidence.
      final ClassInfo annotationClass = annotation.getClassInfo();
      if (annotationClass != null) {
         final AnnotationInfoList metaAnnotations = annotationClass.getAnnotationInfo();
         if (metaAnnotations.stream().anyMatch(meta -> JSR305_TYPE_QUALIFIER_NICKNAME_ANNOTATION.equals(meta.getName()))) {
            for (final var metaAnnotation : metaAnnotations) {
               final String when = getJsr305NonnullWhen(metaAnnotation);
               if (when != null)
                  return when;
            }
         }
      }

      final String annotationName = Objects.requireNonNull(annotation.getName());
      // The supported CLI keeps one classpath for the JVM lifetime, so this process-wide cache cannot cross scan inputs.
      // If the generator gains multi-classpath in-process use, scope this cache to one scan. Cache absent results too,
      // because this fallback is reached for every use of an unrecognized annotation outside the accepted package scan.
      return JSR305_NICKNAME_WHEN_CACHE.computeIfAbsent(annotationName, name -> Optional.ofNullable(readJsr305NicknameWhen(name))).orElse(
         null);
   }

   @SuppressWarnings("null")
   private static @Nullable String readJsr305NicknameWhen(final String annotationClassName) {
      final String resourceName = annotationClassName.replace('.', '/') + ".class";
      final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      try (@SuppressWarnings("resource")
      InputStream classBytes = contextClassLoader == null ? ClassGraphUtils.class.getResourceAsStream("/" + resourceName)
            : contextClassLoader.getResourceAsStream(resourceName)) {
         if (classBytes == null)
            return null;
         final boolean[] isNickname = {false};
         final @Nullable String[] nonnullWhen = {null};
         new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public @Nullable AnnotationVisitor visitAnnotation(final @Nullable String descriptor, final boolean visible) {
               if (JSR305_TYPE_QUALIFIER_NICKNAME_DESCRIPTOR.equals(descriptor)) {
                  isNickname[0] = true;
               } else if (JSR305_NONNULL_DESCRIPTOR.equals(descriptor)) {
                  nonnullWhen[0] = "ALWAYS";
                  return new AnnotationVisitor(Opcodes.ASM9) {
                     @Override
                     public void visitEnum(final @Nullable String name, final @Nullable String enumDescriptor,
                           final @Nullable String value) {
                        if ("when".equals(name)) {
                           nonnullWhen[0] = value;
                        }
                     }
                  };
               }
               return null;
            }
         }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
         return isNickname[0] ? nonnullWhen[0] : null;
      } catch (final IOException | IllegalArgumentException ex) {
         /* The accepted-package filter can omit the nickname declaration from ClassGraph's model. Reading only the
          * annotation class preserves CLASS-retained metadata without loading it; unavailable metadata stays unknown. */
         return null;
      }
   }

   public static boolean hasPackageVisibility(final ClassInfo classInfo) {
      return !classInfo.isPublic() && !classInfo.isPrivate() && !classInfo.isProtected();
   }

   public static boolean hasSuperclass(final ClassInfo classInfo, final String superClassName) {
      return !classInfo.getSuperclasses().filter(c -> c.getName().equals(superClassName)).isEmpty();
   }

   public static boolean isStaticField(final ClassInfo classInfo, final String fieldName) {
      final var fieldInfo = classInfo.getDeclaredFieldInfo(fieldName);
      if (fieldInfo == null)
         return false;
      return fieldInfo.isStatic();
   }

   public static boolean isStaticMethod(final ClassInfo classInfo, final String methodName, final String methodSignature) {
      return classInfo.getDeclaredMethodInfo(methodName).stream() //
         .anyMatch(methodInfo -> methodInfo.isStatic() //
               && methodSignature.equals(methodInfo.getTypeSignatureOrTypeDescriptorStr()));
   }

   public static boolean isThrowable(final ClassInfo classInfo) {
      return hasSuperclass(classInfo, "java.lang.Throwable");
   }

   private ClassGraphUtils() {
   }
}
