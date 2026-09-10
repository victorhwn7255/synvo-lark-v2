package synvo.integration.azurebilling;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import synvo.billing.BillingException;

interface AzureHttp {
    Reply send(URI uri, String method, String bearer, byte[] body);

    record Reply(int status, Map<String, String> headers, InputStream body, Runnable disconnect) implements AutoCloseable {
        @Override public void close() {
            try { body.close(); } catch (IOException exception) { /* Do not expose transport details. */ }
            disconnect.run();
        }
        @Override public String toString() { return "AzureReply[status=" + status + "]"; }
    }

    static AzureHttp production() {
        return (uri, method, bearer, body) -> {
            HttpsURLConnection connection = null;
            try {
                connection = (HttpsURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
                connection.setSSLSocketFactory(new PublicSocketFactory());
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(60_000); connection.setReadTimeout(60_000);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json, text/csv");
                if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
                if (body != null) {
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    connection.setFixedLengthStreamingMode(body.length);
                    try (var output = connection.getOutputStream()) { output.write(body); }
                }
                int status = connection.getResponseCode();
                var headers = new java.util.HashMap<String, String>();
                for (String name : new String[]{"Location", "Retry-After", "Content-Encoding"}) {
                    String value = connection.getHeaderField(name); if (value != null) headers.put(name, value);
                }
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) stream = InputStream.nullInputStream();
                var connected = connection;
                return new Reply(status, Map.copyOf(headers), stream, connected::disconnect);
            } catch (IOException | RuntimeException exception) {
                if (connection != null) connection.disconnect();
                throw new BillingException(BillingException.Reason.SOURCE_UNAVAILABLE);
            }
        };
    }

    static void requirePublic(InetAddress address) throws IOException {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IOException("Forbidden network target");
        if (bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc || bytes[0] == 0)) throw new IOException("Forbidden network target");
        if (bytes.length == 4) {
            int first = bytes[0] & 255; int second = bytes[1] & 255;
            if (first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                    || (first == 198 && (second == 18 || second == 19))) throw new IOException("Forbidden network target");
        }
    }

    /** Validate the actual connected address before TLS/HTTP, including a changed DNS answer. */
    final class PublicSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
            requirePublic(socket.getInetAddress());
            return delegate.createSocket(socket, host, port, close);
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            InetAddress address = InetAddress.getAllByName(host)[0]; requirePublic(address);
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), 60_000);
                return delegate.createSocket(socket, host, port, true);
            } catch (IOException exception) { socket.close(); throw exception; }
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { requirePublic(host); return createSocket(host.getHostAddress(), port); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { throw new IOException("Unsupported socket binding"); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { throw new IOException("Unsupported socket binding"); }
    }
}
