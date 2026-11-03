import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks every android/app-only member that plugins reference (refs.json from scan_refs.py)
 * resolves against the shim on the classpath. Exit code 1 if anything is missing.
 * usage: java -cp shim.jar;libs/* tools/Verify.java refs.json
 */
public class Verify {
    static Class<?> typeOf(String d, int[] pos) throws Exception {
        char c = d.charAt(pos[0]++);
        switch (c) {
            case 'V': return void.class;
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'S': return short.class;
            case 'C': return char.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case '[': return java.lang.reflect.Array.newInstance(typeOf(d, pos), 0).getClass();
            case 'L': {
                int e = d.indexOf(';', pos[0]);
                String n = d.substring(pos[0], e).replace('/', '.');
                pos[0] = e + 1;
                return Class.forName(n, false, Verify.class.getClassLoader());
            }
            default: throw new IllegalStateException(d);
        }
    }

    static List<Class<?>> params(String desc) throws Exception {
        List<Class<?>> r = new ArrayList<>();
        int[] pos = {1};
        while (desc.charAt(pos[0]) != ')') r.add(typeOf(desc, pos));
        return r;
    }

    static boolean findMethod(Class<?> c, String name, List<Class<?>> ps, boolean isStatic) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && java.util.Arrays.equals(m.getParameterTypes(), ps.toArray(new Class<?>[0]))
                        && Modifier.isStatic(m.getModifiers()) == isStatic) return true;
            }
            for (Class<?> i : k.getInterfaces()) if (findMethod(i, name, ps, isStatic)) return true;
        }
        if (c.isInterface()) {
            for (Class<?> i : c.getInterfaces()) if (findMethod(i, name, ps, isStatic)) return true;
        }
        return false;
    }

    static boolean findField(Class<?> c, String name, boolean isStatic) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getName().equals(name) && Modifier.isStatic(f.getModifiers()) == isStatic) return true;
            }
            for (Class<?> i : k.getInterfaces()) if (findField(i, name, isStatic)) return true;
        }
        return false;
    }

    public static void main(String[] a) throws Exception {
        JsonNode root = new ObjectMapper().readTree(new File(a[0]));
        int missing = 0, total = 0;
        for (JsonNode n : root.get("valid")) {
            total++;
            String name = n.asText().replace('/', '.');
            try {
                Class.forName(name, false, Verify.class.getClassLoader());
            } catch (Throwable t) {
                System.out.println("MISSING CLASS " + name);
                missing++;
            }
        }
        for (JsonNode m : root.get("members")) {
            total++;
            String kind = m.get(0).asText(), owner = m.get(1).asText(), name = m.get(2).asText(), desc = m.get(3).asText();
            boolean st = m.get(4).asBoolean();
            String what = owner + "." + name + desc + (st ? " [static]" : "");
            try {
                Class<?> c = Class.forName(owner.replace('/', '.'), false, Verify.class.getClassLoader());
                boolean ok;
                if (kind.equals("Field")) ok = findField(c, name, st);
                else if (name.equals("<init>")) {
                    ok = false;
                    List<Class<?>> ps = params(desc);
                    for (Constructor<?> k : c.getDeclaredConstructors())
                        if (java.util.Arrays.equals(k.getParameterTypes(), ps.toArray(new Class<?>[0]))) ok = true;
                } else ok = findMethod(c, name, params(desc), st);
                if (!ok) { System.out.println("MISSING MEMBER " + what); missing++; }
            } catch (Throwable t) {
                System.out.println("MISSING (" + t + ") " + what);
                missing++;
            }
        }
        System.out.println("checked " + total + " references, missing " + missing);
        System.exit(missing == 0 ? 0 : 1);
    }
}
