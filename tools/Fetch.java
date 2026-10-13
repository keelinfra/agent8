import java.io.*;
import java.net.*;

/** Downloads a URL to a file with Java's own trust store, for hosts whose curl/wget CA bundle is too old. */
public class Fetch {
    public static void main(String[] a) throws Exception {
        URL url = new URL(a[0]);
        for (int i = 0; i < 5; i++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "agent8-install");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(url, c.getHeaderField("Location"));
                continue;
            }
            if (code != 200) { System.err.println(a[0] + ": HTTP " + code); System.exit(1); }
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(a[1]);
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            out.close();
            return;
        }
        System.err.println(a[0] + ": too many redirects");
        System.exit(1);
    }
}
