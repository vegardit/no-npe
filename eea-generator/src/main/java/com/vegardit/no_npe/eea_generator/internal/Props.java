/*
 * SPDX-FileCopyrightText: © 2022 Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class Props {

   private static final Logger LOG = System.getLogger(Props.class.getName());

   public static class Prop<T> {
      public static final String SOURCE_JVM_SYSTEM_ROPERTY = "JVM system property";
      public static final String SOURCE_DEFAULT_VALUE = "default value";

      /**
       * Either a reference to {@link Props#propertiesFile} or {@link Prop#SOURCE_JVM_SYSTEM_ROPERTY} or {@link Prop#SOURCE_DEFAULT_VALUE}
       */
      public final Object source;
      public final String name;
      public final T value;

      public Prop(final Object source, final String name, final T value) {
         this.source = source;
         this.name = name;
         this.value = value;
      }

      @Override
      public String toString() {
         return "Prop [" //
            + "name=" + name + ", " //
            + "value=" + value + ", " //
            + "source=" + source //
            + "]";
      }
   }

   public final String jvmPropertyPrefix;
   public final Path propertiesFile;
   private final Properties properties;

   public Props(final String jvmPropertyPrefix, final Path propertiesFilePath) throws IOException {
      this.jvmPropertyPrefix = jvmPropertyPrefix;
      propertiesFile = propertiesFilePath;

      if (propertiesFilePath == null) {
         properties = null;
      } else {
         properties = new Properties();
         try (var r = Files.newBufferedReader(propertiesFilePath)) {
            properties.load(r);
         }
      }
   }

   public Prop<String> get(final String propName, final String defaultValue) {
      var propValue = System.getProperty(jvmPropertyPrefix + propName);
      Prop<String> prop = null;
      if (propValue != null) {
         prop = new Prop<>(Prop.SOURCE_JVM_SYSTEM_ROPERTY, propName, propValue);
      }

      if (prop == null && properties != null) {
         propValue = properties.getProperty(propName);
         if (propValue != null) {

            prop = new Prop<>(propertiesFile, propName, propValue);
         }
      }

      if (prop == null && defaultValue != null) {
         prop = new Prop<>(Prop.SOURCE_DEFAULT_VALUE, propName, defaultValue);
      }

      if (prop != null) {
         LOG.log(Level.INFO, "Found property [{0}] \"{1}\" ({2})", prop.name, prop.value, prop.source);
         return prop;
      }
      if (propertiesFile != null)
         throw new IllegalArgumentException("Required property [" + propName + "] not found in [" + propertiesFile + "]!");

      throw new IllegalArgumentException("Required " + Prop.SOURCE_JVM_SYSTEM_ROPERTY + " [" + propName + "] not found!");
   }
}
