package keeltest;

/** Loaded on first use, after the Java agents have started. */
public final class TestDigest extends java.security.MessageDigestSpi {
    private int n;

    @Override
    protected void engineUpdate(byte b) {
        n += b;
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            n += b[i];
        }
    }

    @Override
    protected byte[] engineDigest() {
        byte[] r = {(byte) n};
        n = 0;
        return r;
    }

    @Override
    protected void engineReset() {
        n = 0;
    }
}
