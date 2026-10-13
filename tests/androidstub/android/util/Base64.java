package android.util;

/** 最小 Base64 替身：用 JDK 实现编码。 */
public final class Base64 {

    public static final int NO_WRAP = 2;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
