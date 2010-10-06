package com.headius.refractor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Refractor {
    private static final boolean DEBUG = false;
    private StringBuilder sb;
    public Refractor() {
        sb = new StringBuilder();
    }

    public void build(Class<?>[] classes, boolean nonPublic) {
        Set<Class<?>> allClasses = new HashSet<Class<?>>();

        for (Class javaClass : classes) {
            for (Class c = javaClass; c != null; c = c.getSuperclass()) {
                allClasses.add(c);
                for (Class i : c.getInterfaces()) {
                    allClasses.add(i);
                }
            }
        }
        classes = allClasses.toArray(new Class[allClasses.size()]);

        // sort classes in breadth-first hierarchy-walked order
        Arrays.sort(classes, new Comparator<Class>() {
            private static final boolean DEBUG = false;
            public int compare(Class t, Class t1) {
                if (DEBUG) System.out.println("comparing " + t + " and " + t1);
                if (t == t1) {
                    if (DEBUG) System.out.println("\t" + 0);
                    return 0;
                } else if (t.isAssignableFrom(t1)) {
                    if (DEBUG) System.out.println("\t" + -1);
                    return -1;
                } else if (t1.isAssignableFrom(t)) {
                    if (DEBUG) System.out.println("\t" + 1);
                    return 1;
                } else {
                    // disjoint classes, order is not important
                    if (DEBUG) System.out.println("\t" + 0);
                    return 0;
                }
            }
        });
        if (DEBUG) System.out.println(Arrays.toString(classes));

        Map<Method, Method> overrides = new HashMap<Method, Method>();
        Method[] methods = getMethods(classes, overrides);

        if (DEBUG) {
            for (Method method : methods) {
                System.out.println(method);
            }

            for (Map.Entry<Method, Method> entry : overrides.entrySet()) {
                System.out.println("replaced " + entry.getKey() + "\n\twith " + entry.getValue());
            }
        }

        return;
    }

    private static Method[] getMethods(Class<?>[] javaClasses, Map<Method, Method> overrides) {
        HashMap<String, List<Method>> nameMethods = new HashMap<String, List<Method>>();

        for (Class javaClass : javaClasses) {
            // we scan all superclasses, but avoid adding superclass methods with
            // same name+signature as subclass methods (see JRUBY-3130)

            // only add class's methods if it's public
            if (Modifier.isPublic(javaClass.getModifiers())) {
                // for each class, scan declared methods for new signatures
                try {
                    // add public methods, including static if this is the actual class,
                    // and replacing child methods with equivalent parent methods

                    if (javaClass.isInterface()) {
                        // if interface, always add and ignore overrides
                        addNewMethods(nameMethods, overrides, javaClass.getMethods(), false, false);
                    } else {
                        // otherwise, only add if no equivalent superclass method has been seen
                        addNewMethods(nameMethods, overrides, javaClass.getMethods(), true, true);
                    }
                } catch (SecurityException e) {
                }
            }
        }

        // now only bind the ones that remain
        ArrayList<Method> finalList = new ArrayList<Method>();

        for (Map.Entry<String, List<Method>> entry : nameMethods.entrySet()) {
            finalList.addAll(entry.getValue());
        }

        return finalList.toArray(new Method[finalList.size()]);
    }

    private static boolean methodsAreEquivalent(Method child, Method parent) {
        return parent.getDeclaringClass().isAssignableFrom(child.getDeclaringClass())
                && Arrays.equals(child.getParameterTypes(), parent.getParameterTypes())
                && child.getReturnType() == parent.getReturnType()
                && child.isVarArgs() == parent.isVarArgs()
                && Modifier.isPublic(child.getModifiers()) == Modifier.isPublic(parent.getModifiers())
                && Modifier.isProtected(child.getModifiers()) == Modifier.isProtected(parent.getModifiers())
                && Modifier.isStatic(child.getModifiers()) == Modifier.isStatic(parent.getModifiers());
    }

    private static void addNewMethods(HashMap<String, List<Method>> nameMethods, Map<Method, Method> overrides, Method[] methods, boolean includeStatic, boolean removeDuplicate) {
        Methods: for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) && !includeStatic) {
                // Skip static methods if we're not suppose to include them.
                // Generally for superclasses; we only bind statics from the actual
                // class.
                continue;
            }
            List<Method> childMethods = nameMethods.get(m.getName());
            if (childMethods == null) {
                // first method of this name, add a collection for it
                childMethods = new ArrayList<Method>();
                childMethods.add(m);
                nameMethods.put(m.getName(), childMethods);
            } else {
                // we have seen other methods; check if we already have
                // an equivalent one
                for (Method m2 : childMethods) {
                    if (methodsAreEquivalent(m, m2)) {
                        // just skip the new method, since we don't need it (already found one)
                        // used for interface methods, which we want to add unconditionally
                        // but only if we need them

                        // add to override map, so we don't lose track of it entirely
                        if (m.getDeclaringClass() != m2.getDeclaringClass()) overrides.put(m, m2);
                        continue Methods;
                    }
                }
                // no equivalent; add it
                childMethods.add(m);
            }
        }
    }
}
