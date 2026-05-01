package com.home.tvlauncher.utils;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 最小化 QR 码生成器（纯 Java，无外部依赖）
 * 支持 Version 1-6，纠错级别 L，Byte 模式
 * 足够编码局域网 URL（如 http://192.168.1.100:8899/remote）
 */
public class QRCodeGenerator {

    /**
     * 生成 QR 码 Bitmap
     * @param text 要编码的文本
     * @param size 输出图片尺寸（像素）
     * @return QR 码 Bitmap，失败返回 null
     */
    public static Bitmap generate(String text, int size) {
        try {
            boolean[][] matrix = encode(text);
            if (matrix == null) return null;

            int modules = matrix.length;
            // 每个模块的像素大小
            int scale = size / modules;
            if (scale < 1) scale = 1;
            int imgSize = scale * modules;

            Bitmap bitmap = Bitmap.createBitmap(imgSize, imgSize, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < modules; y++) {
                for (int x = 0; x < modules; x++) {
                    int color = matrix[y][x] ? Color.BLACK : Color.WHITE;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            bitmap.setPixel(x * scale + dx, y * scale + dy, color);
                        }
                    }
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    // ========== QR 编码核心 ==========

    // Version 1-6 的数据容量（纠错级别 L，Byte 模式）
    private static final int[] CAPACITY = {0, 17, 32, 53, 78, 106, 134};
    // Version 1-6 的总码字数
    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172};
    // Version 1-6 的纠错码字数（L 级别）
    private static final int[] EC_CODEWORDS = {0, 7, 10, 15, 20, 26, 36};

    private static boolean[][] encode(String text) {
        byte[] data = text.getBytes();
        int len = data.length;

        // 选择最小版本
        int version = 0;
        for (int v = 1; v <= 6; v++) {
            if (len <= CAPACITY[v]) {
                version = v;
                break;
            }
        }
        if (version == 0) return null; // 文本太长

        int modules = 17 + version * 4;
        int totalCodewords = TOTAL_CODEWORDS[version];
        int ecCodewords = EC_CODEWORDS[version];
        int dataCodewords = totalCodewords - ecCodewords;

        // 构建数据比特流
        // Mode indicator: 0100 (Byte mode)
        // Character count: 8 bits (version 1-9)
        int[] dataBits = new int[dataCodewords * 8];
        int bitPos = 0;

        // Mode: 0100
        dataBits[bitPos++] = 0; dataBits[bitPos++] = 1;
        dataBits[bitPos++] = 0; dataBits[bitPos++] = 0;

        // Character count (8 bits)
        for (int i = 7; i >= 0; i--) {
            dataBits[bitPos++] = (len >> i) & 1;
        }

        // Data
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF;
            for (int j = 7; j >= 0; j--) {
                dataBits[bitPos++] = (b >> j) & 1;
            }
        }

        // Terminator (up to 4 bits of 0)
        for (int i = 0; i < 4 && bitPos < dataBits.length; i++) {
            dataBits[bitPos++] = 0;
        }

        // Pad to byte boundary
        while (bitPos % 8 != 0 && bitPos < dataBits.length) {
            dataBits[bitPos++] = 0;
        }

        // Pad bytes: 11101100, 00010001 alternating
        int[] padBytes = {0xEC, 0x11};
        int padIdx = 0;
        while (bitPos < dataBits.length) {
            int pb = padBytes[padIdx % 2];
            for (int j = 7; j >= 0 && bitPos < dataBits.length; j--) {
                dataBits[bitPos++] = (pb >> j) & 1;
            }
            padIdx++;
        }

        // Convert bits to bytes
        int[] dataBytes = new int[dataCodewords];
        for (int i = 0; i < dataCodewords; i++) {
            int val = 0;
            for (int j = 0; j < 8; j++) {
                val = (val << 1) | dataBits[i * 8 + j];
            }
            dataBytes[i] = val;
        }

        // Generate EC codewords using Reed-Solomon
        int[] ecBytes = generateEC(dataBytes, ecCodewords);

        // Interleave (single block for these versions)
        int[] allCodewords = new int[totalCodewords];
        System.arraycopy(dataBytes, 0, allCodewords, 0, dataCodewords);
        System.arraycopy(ecBytes, 0, allCodewords, dataCodewords, ecCodewords);

        // Build matrix
        boolean[][] matrix = new boolean[modules][modules];
        boolean[][] reserved = new boolean[modules][modules];

        // Place finder patterns
        placeFinder(matrix, reserved, 0, 0, modules);
        placeFinder(matrix, reserved, modules - 7, 0, modules);
        placeFinder(matrix, reserved, 0, modules - 7, modules);

        // Place timing patterns
        for (int i = 8; i < modules - 8; i++) {
            matrix[6][i] = (i % 2 == 0);
            reserved[6][i] = true;
            matrix[i][6] = (i % 2 == 0);
            reserved[i][6] = true;
        }

        // Place alignment pattern (version >= 2)
        if (version >= 2) {
            int[] alignPos = getAlignmentPositions(version);
            for (int ay : alignPos) {
                for (int ax : alignPos) {
                    if (reserved[ay][ax]) continue;
                    placeAlignment(matrix, reserved, ay, ax, modules);
                }
            }
        }

        // Reserve format info areas
        for (int i = 0; i < 8; i++) {
            reserved[8][i] = true;
            reserved[i][8] = true;
            if (i < 7) reserved[8][modules - 1 - i] = true;
            if (i < 7) reserved[modules - 1 - i][8] = true;
        }
        reserved[8][8] = true;
        // Dark module
        matrix[modules - 8][8] = true;
        reserved[modules - 8][8] = true;

        // Place data bits
        placeData(matrix, reserved, allCodewords, modules);

        // Apply mask (mask 0: (row + col) % 2 == 0)
        applyMask(matrix, reserved, modules, 0);

        // Place format info
        placeFormatInfo(matrix, modules, 0); // EC level L, mask 0

        return matrix;
    }

    private static void placeFinder(boolean[][] m, boolean[][] r, int row, int col, int size) {
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int y = row + dy;
                int x = col + dx;
                if (y < 0 || y >= size || x < 0 || x >= size) continue;
                boolean dark;
                if (dy == -1 || dy == 7 || dx == -1 || dx == 7) {
                    dark = false; // separator
                } else if (dy == 0 || dy == 6 || dx == 0 || dx == 6) {
                    dark = true;
                } else if (dy >= 2 && dy <= 4 && dx >= 2 && dx <= 4) {
                    dark = true;
                } else {
                    dark = false;
                }
                m[y][x] = dark;
                r[y][x] = true;
            }
        }
    }

    private static void placeAlignment(boolean[][] m, boolean[][] r, int cy, int cx, int size) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int y = cy + dy;
                int x = cx + dx;
                if (y < 0 || y >= size || x < 0 || x >= size) continue;
                boolean dark = (Math.abs(dy) == 2 || Math.abs(dx) == 2 || (dy == 0 && dx == 0));
                m[y][x] = dark;
                r[y][x] = true;
            }
        }
    }

    private static int[] getAlignmentPositions(int version) {
        switch (version) {
            case 2: return new int[]{6, 18};
            case 3: return new int[]{6, 22};
            case 4: return new int[]{6, 26};
            case 5: return new int[]{6, 30};
            case 6: return new int[]{6, 34};
            default: return new int[0];
        }
    }

    private static void placeData(boolean[][] m, boolean[][] r, int[] codewords, int size) {
        int bitIdx = 0;
        int totalBits = codewords.length * 8;
        boolean upward = true;

        for (int col = size - 1; col >= 1; col -= 2) {
            if (col == 6) col = 5; // skip timing column

            int startRow = upward ? size - 1 : 0;
            int endRow = upward ? -1 : size;
            int step = upward ? -1 : 1;

            for (int row = startRow; row != endRow; row += step) {
                for (int c = 0; c < 2; c++) {
                    int x = col - c;
                    if (x < 0 || x >= size) continue;
                    if (r[row][x]) continue;

                    if (bitIdx < totalBits) {
                        int byteIdx = bitIdx / 8;
                        int bitInByte = 7 - (bitIdx % 8);
                        m[row][x] = ((codewords[byteIdx] >> bitInByte) & 1) == 1;
                        bitIdx++;
                    }
                }
            }
            upward = !upward;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] r, int size, int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (r[y][x]) continue;
                boolean flip = false;
                switch (mask) {
                    case 0: flip = (y + x) % 2 == 0; break;
                    case 1: flip = y % 2 == 0; break;
                    case 2: flip = x % 3 == 0; break;
                    case 3: flip = (y + x) % 3 == 0; break;
                }
                if (flip) m[y][x] = !m[y][x];
            }
        }
    }

    private static void placeFormatInfo(boolean[][] m, int size, int mask) {
        // EC level L = 01, mask pattern
        int formatData = (0x01 << 3) | mask;
        int formatBits = calculateFormatBits(formatData);

        // Place format bits
        int[] formatPositions = {
            // Around top-left finder
            0, 1, 2, 3, 4, 5, 7, 8, // row 8, columns
            // Then row positions at column 8
        };

        // Horizontal: row 8
        int bit = 0;
        for (int i = 0; i <= 7; i++) {
            int col = i;
            if (i == 6) col = 7;
            if (i == 7) col = 8;
            m[8][col] = ((formatBits >> (14 - bit)) & 1) == 1;
            bit++;
        }
        for (int i = 0; i < 7; i++) {
            m[8][size - 7 + i] = ((formatBits >> (14 - bit)) & 1) == 1;
            bit++;
        }

        // Vertical: column 8
        bit = 0;
        for (int i = 0; i <= 7; i++) {
            int row = i < 6 ? size - 1 - i : size - 2 - i;
            m[row][8] = ((formatBits >> (14 - bit)) & 1) == 1;
            bit++;
        }
        for (int i = 0; i < 7; i++) {
            int row = i == 0 ? 7 : 5 - (i - 1);
            m[row][8] = ((formatBits >> (14 - bit)) & 1) == 1;
            bit++;
        }
    }

    private static int calculateFormatBits(int data) {
        // BCH(15,5) encoding with generator polynomial 10100110111
        int gen = 0x537; // 10100110111
        int encoded = data << 10;
        for (int i = 4; i >= 0; i--) {
            if (((encoded >> (i + 10)) & 1) == 1) {
                encoded ^= gen << i;
            }
        }
        encoded = (data << 10) | encoded;
        // XOR with mask pattern 101010000010010
        encoded ^= 0x5412;
        return encoded;
    }

    // ========== Reed-Solomon ==========

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if (x >= 256) x ^= 0x11D; // primitive polynomial
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static int[] generateEC(int[] data, int ecLen) {
        // Generate generator polynomial
        int[] gen = new int[ecLen + 1];
        gen[0] = 1;
        for (int i = 0; i < ecLen; i++) {
            int[] newGen = new int[ecLen + 1];
            for (int j = 0; j <= i + 1; j++) {
                if (j > 0) newGen[j] ^= gen[j - 1];
                newGen[j] ^= gfMul(gen[j], GF_EXP[i]);
            }
            gen = newGen;
        }

        // Polynomial division
        int[] result = new int[ecLen];
        int[] msg = new int[data.length + ecLen];
        System.arraycopy(data, 0, msg, 0, data.length);

        for (int i = 0; i < data.length; i++) {
            int coef = msg[i];
            if (coef != 0) {
                for (int j = 1; j < gen.length; j++) {
                    msg[i + j] ^= gfMul(gen[j], coef);
                }
            }
        }

        System.arraycopy(msg, data.length, result, 0, ecLen);
        return result;
    }
}
