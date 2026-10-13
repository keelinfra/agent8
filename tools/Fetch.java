import java.io.*;
import java.net.*;

/**
 * Downloads a URL to a file with Java's own trust store, for hosts whose curl/wget CA bundle is too old.
 * {@code Fetch --sha256 FILE} prints the file's SHA-256, for hosts without sha256sum, shasum, digest or openssl.
 */
public class Fetch {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("--sha256")) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(a[1]);
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n);
            in.close();
            StringBuilder hex = new StringBuilder();
            for (byte x : md.digest()) hex.append(String.format("%02x", x));
            System.out.println(hex);
            return;
        }
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
