package android.graphics;

/** 最小 BitmapFactory 替身。 */
public final class BitmapFactory {

    private BitmapFactory() {
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return new Bitmap();
    }
}
