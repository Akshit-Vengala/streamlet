import cloudstream.desktop.androidshim.ShimRuntime;
import cloudstream.desktop.androidshim.Stub;
import com.lagradost.cloudstream3.APIHolder;
import com.lagradost.cloudstream3.plugins.BasePlugin;
import com.lagradost.cloudstream3.plugins.Plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Smoke test: instantiate each plugin and call load(Context) with the shim Context.
 * usage: java -cp shim.jar;libs/* tools/LoadSmoke.java <cs3 dir> <converted jars dir>
 */
public class LoadSmoke {
    public static void main(String[] a) throws Exception {
        Path cs3 = Paths.get(a[0]), jars = Paths.get(a[1]);
        try (var s = Files.list(cs3)) {
            for (Path f : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".cs3")).sorted()::iterator) {
                String name = f.getFileName().toString().replace(".cs3", "");
                String cls;
                try (ZipFile z = new ZipFile(f.toFile())) {
                    String m = new String(z.getInputStream(z.getEntry("manifest.json")).readAllBytes());
                    Matcher mm = Pattern.compile("\"pluginClassName\"\\s*:\\s*\"([^\"]+)\"").matcher(m);
                    mm.find();
                    cls = mm.group(1);
                }
                int before = APIHolder.INSTANCE.getAllProviders().size();
                try (URLClassLoader cl = new URLClassLoader(new URL[]{jars.resolve(name + ".jar").toUri().toURL()}, LoadSmoke.class.getClassLoader())) {
                    BasePlugin p = (BasePlugin) Class.forName(cls, true, cl).getDeclaredConstructor().newInstance();
                    p.setFilename(f.toString());
                    if (p instanceof Plugin) ((Plugin) p).load(ShimRuntime.context()); else p.load();
                    int added = APIHolder.INSTANCE.getAllProviders().size() - before;
                    System.out.println(name + ": load OK, providers added=" + added
                            + ", settingsUi=" + ShimRuntime.hasUnavailableSettingsUi(p));
                } catch (Throwable t) {
                    Throwable r = t;
                    while (r.getCause() != null) r = r.getCause();
                    System.out.println(name + ": load FAILED " + r);
                }
            }
        }
        System.out.println("unimplemented android members touched: " + Stub.hits());
    }
}
