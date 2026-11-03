package android.net;

/** Desktop is assumed online; plugins only probe connectivity before network calls. */
public class ConnectivityManager {
    public ConnectivityManager() { }
    public boolean isActiveNetworkMetered() { return false; }
    public NetworkInfo getActiveNetworkInfo() { return new NetworkInfo(); }
    public Network getActiveNetwork() { return new Network(); }
    public NetworkCapabilities getNetworkCapabilities(Network n) { return new NetworkCapabilities(); }
    public void registerNetworkCallback(NetworkRequest r, NetworkCallback cb) { }
    public void registerDefaultNetworkCallback(NetworkCallback cb) { }
    public void unregisterNetworkCallback(NetworkCallback cb) { }

    public static class NetworkCallback {
        public NetworkCallback() { }
        public void onAvailable(Network n) { }
        public void onLost(Network n) { }
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { }
    }
}
