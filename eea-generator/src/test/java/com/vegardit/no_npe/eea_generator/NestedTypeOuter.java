/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Test fixture exposing a non-static inner type with independently annotated enclosing and leaf segments for verifying
 * top-level EEA nullness marker placement.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
@NonNullByDefault({})
class NestedTypeOuter<T> {
   class Inner {
   }
}
