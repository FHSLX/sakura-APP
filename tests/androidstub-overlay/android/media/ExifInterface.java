package android.media;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * ExifInterface 替身（测试专用）：真实解析 JPEG 的 EXIF {@code Orientation}。
 *
 * <p>生产代码用 SDK 自带的 {@code android.media.ExifInterface}（不需要联网依赖）；
 * 这里实现一个最小 TIFF/APP1 解析器，让「超预算缩图必须应用 EXIF 方向」
 * 这条要求在纯 JVM 测试里可以被真正验证，而不是靠假设。
 */
public class ExifInterface {

    public static final String TAG_ORIENTATION = "Orientation";

    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_ROTATE_90 = 6;
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_ROTATE_270 = 8;

    private int orientation = ORIENTATION_NORMAL;

    public ExifInterface(String filename) throws java.io.IOException {
        orientation = readOrientation(filename);
    }

    public int getAttributeInt(String tag, int defaultValue) {
        if (TAG_ORIENTATION.equals(tag)) {
            return orientation;
        }
        return defaultValue;
    }

    private static int readOrientation(String filename) {
        if (filename == null) {
            return ORIENTATION_NORMAL;
        }
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(filename), 8192);
            int b0 = in.read();
            int b1 = in.read();
            if (b0 != 0xFF || b1 != 0xD8) {
                return ORIENTATION_NORMAL;
            }
            while (true) {
                int prefix = in.read();
                if (prefix < 0) {
                    return ORIENTATION_NORMAL;
                }
                if (prefix != 0xFF) {
                    continue;
                }
                int marker = in.read();
                if (marker < 0) {
                    return ORIENTATION_NORMAL;
                }
                if (marker == 0xFF || marker == 0xD8 || marker == 0x01
                        || (marker >= 0xD0 && marker <= 0xD7)) {
                    continue;
                }
                if (marker == 0xDA || marker == 0xD9) {
                    return ORIENTATION_NORMAL;   // 到了图像数据，没有 EXIF
                }
                int lengthHigh = in.read();
                int lengthLow = in.read();
                if (lengthHigh < 0 || lengthLow < 0) {
                    return ORIENTATION_NORMAL;
                }
                int length = (lengthHigh << 8) | lengthLow;
                if (length < 2) {
                    return ORIENTATION_NORMAL;
                }
                byte[] payload = new byte[length - 2];
                int filled = 0;
                while (filled < payload.length) {
                    int read = in.read(payload, filled, payload.length - filled);
                    if (read < 0) {
                        break;
                    }
                    filled += read;
                }
                if (marker == 0xE1 && filled >= 8
                        && payload[0] == 'E' && payload[1] == 'x'
                        && payload[2] == 'i' && payload[3] == 'f') {
                    return parseTiff(payload, 6);
                }
            }
        } catch (Throwable error) {
            return ORIENTATION_NORMAL;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    private static int parseTiff(byte[] data, int base) {
        if (data.length < base + 8) {
            return ORIENTATION_NORMAL;
        }
        boolean littleEndian = data[base] == 'I' && data[base + 1] == 'I';
        int ifdOffset = readInt(data, base + 4, littleEndian);
        int directory = base + ifdOffset;
        if (directory < 0 || directory + 2 > data.length) {
            return ORIENTATION_NORMAL;
        }
        int count = readShort(data, directory, littleEndian);
        for (int index = 0; index < count; index += 1) {
            int entry = directory + 2 + index * 12;
            if (entry + 12 > data.length) {
                return ORIENTATION_NORMAL;
            }
            int tag = readShort(data, entry, littleEndian);
            if (tag != 0x0112) {
                continue;
            }
            int value = readShort(data, entry + 8, littleEndian);
            if (value >= ORIENTATION_NORMAL && value <= ORIENTATION_ROTATE_270) {
                return value;
            }
            return ORIENTATION_NORMAL;
        }
        return ORIENTATION_NORMAL;
    }

    private static int readShort(byte[] data, int offset, boolean littleEndian) {
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return littleEndian ? (second << 8) | first : (first << 8) | second;
    }

    private static int readInt(byte[] data, int offset, boolean littleEndian) {
        int a = data[offset] & 0xFF;
        int b = data[offset + 1] & 0xFF;
        int c = data[offset + 2] & 0xFF;
        int d = data[offset + 3] & 0xFF;
        if (littleEndian) {
            return (d << 24) | (c << 16) | (b << 8) | a;
        }
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
}
