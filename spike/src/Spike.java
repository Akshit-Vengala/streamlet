import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public class Spike {
    public static void main(String[] args) throws Exception {
        Path libs = Paths.get(args[0]), cs3 = Paths.get(args[1]), jars = Paths.get(args[2]);
        List<URL> parent = new ArrayList<>();
        try (var s = Files.list(libs)) { for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".jar"))::iterator) parent.add(p.toUri().toURL()); }
        URLClassLoader parentCl = new URLClassLoader(parent.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        try (var s = Files.list(cs3)) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".cs3")).sorted()::iterator) {
                String name = f.getFileName().toString().replace(".cs3", "");
                String cls = null;
                try (ZipFile z = new ZipFile(f.toFile())) {
                    String m = new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes());
                    Matcher mm = Pattern.compile("\"pluginClassName\"\s*:\s*\"([^\"]+)\"").matcher(m);
                    if (mm.find()) cls = mm.group(1);
                }
                System.out.println("== " + name + "  class=" + cls);
                Path jar = jars.resolve(name + ".jar");
                try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, parentCl)) {
                    Class<?> c = Class.forName(cls, true, cl);
                    System.out.println("  loaded class; super=" + c.getSuperclass().getName());
                    Object inst = c.getDeclaredConstructor().newInstance();
                    System.out.println("  INSTANTIATED OK: " + inst.getClass().getName());
                    // Try eager-verify every class in the jar, collect missing symbols
                    Map<String,Integer> missing = new TreeMap<>();
                    int ok = 0, bad = 0;
                    try (ZipFile z = new ZipFile(jar.toFile())) {
                        for (var e = z.entries(); e.hasMoreElements();) {
                            String en = e.nextElement().getName();
                            if (!en.endsWith(".class")) continue;
                            String cn = en.substring(0, en.length() - 6).replace('/', '.');
                            try { Class.forName(cn, false, cl).getDeclaredMethods(); ok++; }
                            catch (Throwable t) {
                                bad++;
                                String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                                missing.merge(msg, 1, Integer::sum);
                            }
                        }
                    }
                    System.out.println("  classes resolved=" + ok + " failed=" + bad);
                    missing.entrySet().stream().sorted((a,b)->b.getValue()-a.getValue()).limit(25)
                        .forEach(en -> System.out.println("    x" + en.getValue() + " " + en.getKey()));
                } catch (Throwable t) {
                    System.out.println("  FAIL " + t);
                    Throwable r = t; while (r.getCause() != null) r = r.getCause();
                    System.out.println("  root: " + r);
                }
            }
        }
    }
}
