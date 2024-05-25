/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public final class MiscUtils {

   public static boolean arrayContains(final Object @Nullable [] searchIn, final Object searchFor) {
      if (searchIn == null || searchIn.length == 0)
         return false;
      for (final var e : searchIn) {
         if (e.equals(searchFor))
            return true;
      }
      return false;
   }

   public static void configureJUL() {
      final var mainLogger = Logger.getLogger("com.vegardit.no_npe");
      mainLogger.setUseParentHandlers(false);
      final var handler = new ConsoleHandler();
      handler.setFormatter(new SimpleFormatter() {
         @Override
         public synchronized String format(final LogRecord lr) {
            return String.format( //
               "[%1$s] %2$s | %3$s %n", //
               lr.getLevel().getLocalizedName(), //
               lr.getSourceClassName().substring(lr.getSourceClassName().lastIndexOf('.') + 1), //
               MessageFormat.format(lr.getMessage(), lr.getParameters()));
         }
      });
      mainLogger.addHandler(handler);
   }

   public static String normalizeNewLines(final String str) {
      return str //
         .replace("\r\n", "\n") //
         .replace('\r', '\n');
   }

   public static BufferedReader getUTF8ResourceAsReader(final Class<?> clazz, final String resourceName) {
      return new BufferedReader(new InputStreamReader(clazz.getResourceAsStream(resourceName), StandardCharsets.UTF_8));
   }

   public static <E> @Nullable E findLastElement(final List<E> list) {
      if (list.isEmpty())
         return null;
      return list.get(list.size() - 1);
   }

   /**
    * Replaces the given capturing group of all matches.
    */
   public static String replaceAll(final String searchIn, final Pattern searchFor, final int groupToReplace,
      final UnaryOperator<String> replaceWith) {
      if (searchIn.isEmpty())
         return searchIn;
      final var matcher = searchFor.matcher(searchIn);
      int lastPos = 0;
      final var sb = new StringBuilder();
      while (matcher.find()) {
         final var start = matcher.start(groupToReplace);
         sb.append(searchIn.substring(lastPos, start));
         final var textToReplace = matcher.group(groupToReplace);
         assert textToReplace != null;
         sb.append(replaceWith.apply(textToReplace));
         lastPos = matcher.end(groupToReplace);
      }
      if (lastPos == 0)
         return searchIn;

      sb.append(searchIn.substring(lastPos));
      return sb.toString();
   }

   public static @Nullable String getSubstringBetweenBalanced(@Nullable final String searchIn, final char startDelimiter,
      final char endDelimiter) {
      if (searchIn == null)
         return null;
      int depth = 0;
      int lastStartDelimiter = -1;
      for (int i = 0, l = searchIn.length(); i < l; i++) {
         final char c = searchIn.charAt(i);
         if (c == startDelimiter) {
            depth++;
            if (depth == 1) {
               lastStartDelimiter = i + 1;
            }
         } else if (c == endDelimiter) {
            if (depth == 1)
               return searchIn.substring(lastStartDelimiter, i);
            if (depth > 0) {
               depth--;
            }
         }
      }
      return null;
   }

   public static <T extends Throwable> @Nullable T sanitizeStackTraces(@Nullable final T ex) {
      if (ex == null)
         return null;

      final var stacktrace = ex.getStackTrace();
      if (stacktrace.length < 3)
         return ex;

      final var sanitized = new ArrayList<StackTraceElement>(stacktrace.length - 2);
      // we leave the first two elements untouched to keep the context
      sanitized.add(stacktrace[0]);
      sanitized.add(stacktrace[1]);
      for (int i = 2, l = stacktrace.length; i < l; i++) {
         final StackTraceElement ste = stacktrace[i];
         final String className = ste.getClassName();
         if ("java.lang.reflect.Method".equals(className) //
            || className.startsWith("java.util.stream.") //
            || "java.util.Iterator".equals(className) && "forEachRemaining".equals(ste.getMethodName())//
            || "java.util.Spliterators$IteratorSpliterator".equals(className) && "forEachRemaining".equals(ste.getMethodName())//
            || className.startsWith("sun.reflect.") //
            || className.startsWith("sun.proxy.$Proxy", 4) //
            || className.startsWith("org.codehaus.groovy.runtime.") //
            || className.startsWith("org.codehaus.groovy.reflection.") //
            || className.startsWith("groovy.lang.Meta") //
            || className.startsWith("groovy.lang.Closure") //
         ) {
            continue;
         }
         sanitized.add(ste);
      }

      @SuppressWarnings("null")
      final @NonNull StackTraceElement[] arr = sanitized.toArray(StackTraceElement[]::new);
      ex.setStackTrace(arr);
      if (ex.getCause() != null) {
         sanitizeStackTraces(ex.getCause());
      }
      return ex;
   }

   public static String insert(final String str, final int pos, final String insertion) {
      return str.substring(0, pos) + insertion + str.substring(pos);
   }

   public static void writeLine(final Appendable w, final Object... content) throws IOException {
      for (final var e : content) {
         w.append(Objects.toString(e));
      }
      w.append("\n");
   }

   public static void writeLine(final Writer w, final Object... content) throws IOException {
      for (final var e : content) {
         w.write(Objects.toString(e));
      }
      w.write(System.lineSeparator());
   }

   private MiscUtils() {
   }
}
