package android.net;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Lightweight Uri backed by the original string (lenient like Android's parser). */
public class Uri {
    public static final Uri EMPTY = new Uri("");
    private final String s;
    private URI parsed;

    protected Uri(String s) { this.s = s == null ? "" : s; }

    public static Uri parse(String s) { return new Uri(s); }

    public static Uri fromFile(java.io.File f) { return new Uri(f.toURI().toString()); }

    public static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private URI uri() {
        if (parsed == null) {
            try { parsed = new URI(s); } catch (Exception e) {
                try { parsed = new URI(s.replace(" ", "%20").replace("|", "%7C").replace("{", "%7B").replace("}", "%7D")); }
                catch (Exception e2) { parsed = URI.create("invalid:"); }
            }
        }
        return parsed;
    }

    public String getScheme() { return uri().getScheme(); }
    public String getHost() { return uri().getHost(); }
    public int getPort() { return uri().getPort(); }
    public String getAuthority() { return uri().getAuthority(); }
    public String getPath() { return uri().getPath(); }
    public String getEncodedPath() { return uri().getRawPath(); }
    public String getQuery() { return uri().getQuery(); }
    public String getEncodedQuery() { return uri().getRawQuery(); }
    public String getFragment() { return uri().getFragment(); }
    public String getUserInfo() { return uri().getUserInfo(); }

    public String getLastPathSegment() {
        String p = getPath();
        if (p == null || p.isEmpty()) return null;
        String t = p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
        int i = t.lastIndexOf('/');
        return i < 0 ? t : t.substring(i + 1);
    }

    public String getQueryParameter(String key) {
        String q = uri().getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String k = decode(i < 0 ? part : part.substring(0, i));
            if (k.equals(key)) return i < 0 ? "" : decode(part.substring(i + 1));
        }
        return null;
    }

    public Set<String> getQueryParameterNames() {
        Set<String> r = new LinkedHashSet<>();
        String q = uri().getRawQuery();
        if (q != null) for (String part : q.split("&")) {
            int i = part.indexOf('=');
            r.add(decode(i < 0 ? part : part.substring(0, i)));
        }
        return r;
    }

    public boolean isAbsolute() { return uri().isAbsolute(); }
    public boolean isRelative() { return !uri().isAbsolute(); }
    public boolean isHierarchical() { return !uri().isOpaque(); }

    public Builder buildUpon() { return new Builder().encodedUri(s); }

    @Override public String toString() { return s; }
    @Override public boolean equals(Object o) { return o instanceof Uri && ((Uri) o).s.equals(s); }
    @Override public int hashCode() { return s.hashCode(); }

    public static final class Builder {
        private String scheme, authority, path, fragment;
        private final StringBuilder query = new StringBuilder();

        public Builder encodedUri(String uri) {
            URI u = URI.create(uri.replace(" ", "%20"));
            scheme = u.getScheme(); authority = u.getRawAuthority(); path = u.getRawPath(); fragment = u.getRawFragment();
            if (u.getRawQuery() != null) query.append(u.getRawQuery());
            return this;
        }
        public Builder scheme(String v) { scheme = v; return this; }
        public Builder authority(String v) { authority = v; return this; }
        public Builder path(String v) { path = v; return this; }
        public Builder appendPath(String seg) {
            String p = path == null ? "" : path;
            path = (p.endsWith("/") ? p : p + "/") + encode(seg);
            return this;
        }
        public Builder fragment(String v) { fragment = v; return this; }
        public Builder appendQueryParameter(String k, String v) {
            if (query.length() > 0) query.append('&');
            query.append(encode(k)).append('=').append(encode(v));
            return this;
        }
        public Builder clearQuery() { query.setLength(0); return this; }
        public Uri build() {
            StringBuilder b = new StringBuilder();
            if (scheme != null) b.append(scheme).append(':');
            if (authority != null) b.append("//").append(authority);
            if (path != null) b.append(path);
            if (query.length() > 0) b.append('?').append(query);
            if (fragment != null) b.append('#').append(fragment);
            return new Uri(b.toString());
        }
    }
}
