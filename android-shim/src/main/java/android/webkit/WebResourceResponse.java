package android.webkit;

public class WebResourceResponse {
    private String mimeType;
    private String encoding;
    private java.io.InputStream data;

    public WebResourceResponse() { }

    public WebResourceResponse(String mimeType, String encoding, java.io.InputStream data) {
        this.mimeType = mimeType; this.encoding = encoding; this.data = data;
    }

    public String getMimeType() { return mimeType; }
    public String getEncoding() { return encoding; }
    public java.io.InputStream getData() { return data; }
}
