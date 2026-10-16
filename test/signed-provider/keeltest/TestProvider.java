package keeltest;

/** A security provider in a signed jar, put first in the provider list, as SunPKCS11 or OracleUcrypto are on Solaris. */
public final class TestProvider extends java.security.Provider {
    public TestProvider() {
        super("KeelTest", 1.0, "signed test provider");
        put("MessageDigest.KEELTEST", "keeltest.TestDigest");
    }
}
