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
        if (DEBUG) {
            System.out.println("For classes:");
            for (Class javaClass : classes) {
                System.out.println("\t" + javaClass.getName());
            }
        }

        Map<Method, Set<Method>> overrideTable = new HashMap();
        Method[] methods = getMethods(classes, overrideTable);

        if (DEBUG) {
            System.out.println("\nReduced set of methods:");
            for (Method method : methods) {
                System.out.println("\t" + method);
            }

            System.out.println("\nOverrides:");
            for (Map.Entry<Method, Set<Method>> entry : overrideTable.entrySet()) {
                System.out.println("\tfor: " + entry.getKey());
                for (Method override : entry.getValue()) {
                    System.out.println("\t\t" + override);
                }
            }
        }

        // For all methods, build a mapping from arity to invocation logic, with
        // casts in appropriate places. Store with increasing indices.
        Map<Integer, List<String>> table = new HashMap();
        Map<Class, List<Integer[]>> classJumps = new HashMap();
        for (Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            int arity = params.length;

            List<String> jumps = table.get(arity);
            if (jumps == null) table.put(arity, jumps = new ArrayList());

            // add invocation string to jump table
            String paramString = "";
            if (arity > 0) {
                StringBuilder paramBuilder = new StringBuilder();
                boolean first = true;
                int index = 0;
                for (Class<?> paramClass : params) {
                    if (!first) paramBuilder.append(',');
                    first = false;
                    paramBuilder.append("(").append(paramClass.getName()).append(")arg").append(index++);
                }
                paramString = paramBuilder.toString();
            }
            if (method.getReturnType() == void.class) {
                jumps.add("((" + method.getDeclaringClass().getName() + ")recv)." + method.getName() + "(" + paramString + "); return null;");
            } else {
                jumps.add("return ((" + method.getDeclaringClass().getName() + ")recv)." + method.getName() + "(" + paramString + ");");
            }

            // add jump offset and arity to classJumps table
            List<Integer[]> offsetsArities = classJumps.get(method.getDeclaringClass());
            if (offsetsArities == null) classJumps.put(method.getDeclaringClass(), offsetsArities = new ArrayList());
            offsetsArities.add(new Integer[] {arity, jumps.size() - 1});

            // add same offset and arity for all overrides of this method
            Set<Method> overrides = overrideTable.get(method);
            if (overrides != null) for (Method override : overrides) {
                offsetsArities = classJumps.get(override.getDeclaringClass());
                if (offsetsArities == null) classJumps.put(override.getDeclaringClass(), offsetsArities = new ArrayList());
                offsetsArities.add(new Integer[] {arity, jumps.size() - 1});
            }
        }

        if (DEBUG) {
            System.out.println("\nInvocation table:");
            for (Map.Entry<Integer, List<String>> entry : table.entrySet()) {
                System.out.println("\t" + entry.getKey() + ":");
                for (String invocation : entry.getValue()) {
                    System.out.println("\t\t" + invocation);
                }
            }
            System.out.println("\nClass table:");
            for (Map.Entry<Class, List<Integer[]>> entry : classJumps.entrySet()) {
                System.out.println("\t" + entry.getKey().getName() + ":");
                for (Integer[] ints : entry.getValue()) {
                    System.out.println("\t\t" + Arrays.toString(ints));
                }
            }
        }

        return;
    }

    private static Method[] getMethods(Class<?>[] javaClasses, Map<Method, Set<Method>> overrides) {
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

    private static void addNewMethods(HashMap<String, List<Method>> nameMethods, Map<Method, Set<Method>> overrideTable, Method[] methods, boolean includeStatic, boolean removeDuplicate) {
        Methods: for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) && !includeStatic) {
                // Skip static methods if we're not suppose to include them.
                // Generally for superclasses; we only bind statics from the actual
                // class.
                continue;
            }
            List<Method> parentMethods = nameMethods.get(m.getName());
            if (parentMethods == null) {
                // first method of this name, add a collection for it
                parentMethods = new ArrayList<Method>();
                parentMethods.add(m);
                nameMethods.put(m.getName(), parentMethods);
            } else {
                // we have seen other methods; check if we already have
                // an equivalent one
                for (Method m2 : parentMethods) {
                    if (methodsAreEquivalent(m, m2)) {
                        // just skip the new method, since we don't need it (already found one)
                        // used for interface methods, which we want to add unconditionally
                        // but only if we need them

                        if (!m.toString().equals(m2.toString())) {
                            // add to override map, so we don't lose track of it entirely
                            Set<Method> overrides = overrideTable.get(m2);
                            if (overrides == null) overrideTable.put(m2, overrides = new HashSet());
                            overrides.add(m);
                        }
                        continue Methods;
                    }
                }
                // no equivalent; add it
                parentMethods.add(m);
            }
        }
    }
}
