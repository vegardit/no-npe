/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public abstract class MiscUtils {

   public static boolean arrayContains(final Object[] searchIn, final Object searchFor) {
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
      final ConsoleHandler handler = new ConsoleHandler();
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

   public static BufferedReader getUTF8ResourceAsReader(final Class<?> clazz, final String resourceName) {
      return new BufferedReader(new InputStreamReader(clazz.getResourceAsStream(resourceName), StandardCharsets.UTF_8));
   }

   public static <E> E getLastElement(final Collection<E> c) {
      E last = null;
      for (final E e : c) {
         last = e;
      }
      return last;
   }

   public static <T extends Throwable> T sanitizeStackTraces(final T ex) {
      if (ex == null)
         return null;

      final var stacktrace = ex.getStackTrace();
      if (stacktrace.length < 3)
         return ex;

      final List<StackTraceElement> sanitized = new ArrayList<>(stacktrace.length - 2);
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

      final StackTraceElement[] arr = new StackTraceElement[sanitized.size()];
      ex.setStackTrace(sanitized.toArray(arr));
      if (ex.getCause() != null) {
         sanitizeStackTraces(ex.getCause());
      }
      return ex;
   }

   public static void writeLine(final BufferedWriter w, final Object... content) throws IOException {
      for (final var e : content) {
         w.write(Objects.toString(e));
      }
      w.newLine();
   }
}
