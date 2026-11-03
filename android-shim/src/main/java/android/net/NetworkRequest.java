package android.net;

public class NetworkRequest {
    public static class Builder {
        public Builder() { }
        public Builder addCapability(int c) { return this; }
        public Builder addTransportType(int t) { return this; }
        public NetworkRequest build() { return new NetworkRequest(); }
    }
}
