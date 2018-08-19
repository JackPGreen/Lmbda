/*
 * This file is part of Lmbda, licensed under the MIT License (MIT).
 *
 * Copyright (c) LanternPowered <https://www.lanternpowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.lanternpowered.lmbda;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ReflectPermission;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * A utility class full of magic related to {@link MethodHandles}.
 */
public final class UnsafeMethodHandles {

    static final MethodHandles.Lookup trustedLookup = loadTrustedLookup();
    private static final MethodHandlesLookupAccess methodHandlesLookupAccess = loadMethodHandlesLookupAccess();

    /**
     * Gets a trusted {@link MethodHandles.Lookup} instance.
     *
     * @return The trusted method handles lookup
     * @throws SecurityException If the caller isn't allowed to access to trusted method handles lookup
     */
    public static MethodHandles.Lookup trustedLookup() {
        final SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null) {
            securityManager.checkPermission(new ReflectPermission("trustedMethodHandlesLookup"));
        }
        return trustedLookup;
    }

    /**
     * Defines a "nest mate" class on the {@link MethodHandles.Lookup} target.
     *
     * <p>Nestmate classes have private access for fields/methods/constructors
     * on the target class. This allows you to access them without the need
     * of reflection.</p>
     *
     * <p>The provided {@link MethodHandles.Lookup} must have private access
     * in order to be able to define a nestmate class.
     * See {@link MethodHandles.Lookup#PRIVATE}</p>
     *
     * @param lookup The lookup of which the target class will be used to define the class in
     * @param byteCode The byte code of the class to define
     * @return The defined class
     * @throws IllegalAccessException If the lookup doesn't have private access in it's target class
     */
    public static Class<?> defineNestmateClass(MethodHandles.Lookup lookup, byte[] byteCode) throws IllegalAccessException {
        if ((lookup.lookupModes() & MethodHandles.Lookup.PRIVATE) == 0) {
            throw new IllegalAccessException("The lookup doesn't have private access, which is required to define nestmate classes.");
        }
        return methodHandlesLookupAccess.defineNestmate(lookup, byteCode);
    }

    /**
     * Loads the trusted {@link MethodHandles.Lookup} instance.
     *
     * @return The trusted method handles lookup
     */
    private static MethodHandles.Lookup loadTrustedLookup() {
        return AccessController.doPrivileged((PrivilegedAction<MethodHandles.Lookup>) () -> {
            try {
                // See if we can find the trusted lookup field directly
                final Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                field.setAccessible(true);
                return (MethodHandles.Lookup) field.get(null);
            } catch (NoSuchFieldException e) {
                // Not so much luck, let's try to hack something together another way
                // Get a public lookup and create a new instance
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup().in(Object.class);

                try {
                    final Field field = MethodHandles.Lookup.class.getDeclaredField("allowedModes");
                    // Make the field accessible
                    FieldAccess.makeAccessible(field);

                    // The field that holds the trusted access mode
                    final Field trustedAccessModeField = MethodHandles.Lookup.class.getDeclaredField("TRUSTED");
                    trustedAccessModeField.setAccessible(true);

                    // Apply the modifier to the lookup instance
                    field.set(lookup, trustedAccessModeField.get(null));
                } catch (Exception e1) {
                    throw new IllegalStateException("Unable to create a trusted method handles lookup", e1);
                }

                return lookup;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to create a trusted method handles lookup", e);
            }
        });
    }

    /**
     * A internal access for defining "nest mate" classes.
     */
    private interface MethodHandlesLookupAccess {

        Class<?> defineNestmate(MethodHandles.Lookup lookup, byte[] byteCode);
    }

    /**
     * Loads the {@link MethodHandlesLookupAccess} instance.
     *
     * @return The method handles lookup access
     */
    private static MethodHandlesLookupAccess loadMethodHandlesLookupAccess() {
        return AccessController.doPrivileged((PrivilegedAction<MethodHandlesLookupAccess>) () -> {
            // Currently, only Unsafe provides access to defining nestmate classes,
            // this will most likely change in the future:
            // JDK-8171335
            // JDK-8172672
            // TODO: Keep this up to date
            Class<?> unsafeClass;
            try {
                unsafeClass = Class.forName("sun.misc.Unsafe");
            } catch (ClassNotFoundException e) {
                try {
                    unsafeClass = Class.forName("jdk.unsupported.misc.Unsafe");
                } catch (ClassNotFoundException e1) {
                    throw new IllegalStateException("Unable to find the Unsafe class.", e);
                }
            }
            try {
                final Field field = unsafeClass.getDeclaredField("theUnsafe");
                field.setAccessible(true);

                // Get the defineAnonymousClass method
                final Method method = unsafeClass.getDeclaredMethod("defineAnonymousClass",
                        Class.class, byte[].class, Object[].class);
                method.setAccessible(true);
                // Get the method handle for the method
                final MethodHandle methodHandle = trustedLookup.in(unsafeClass).unreflect(method);

                // Get the Unsafe instance
                final Object unsafe = field.get(null);

                // Create the lookup access
                return (MethodHandlesLookupAccess) (lookup, bytes) -> {
                    try {
                        return (Class<?>) methodHandle.invoke(unsafe, lookup.lookupClass(), bytes, null);
                    } catch (Throwable throwable) {
                        throw new IllegalStateException(throwable);
                    }
                };
            } catch (Exception e) {
                throw new IllegalStateException("Unable to access the Unsafe instance.", e);
            }
        });
    }

    private UnsafeMethodHandles() {
    }
}
