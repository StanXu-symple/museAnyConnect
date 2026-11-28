package com.muse.connect.service;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.TelegramRelayConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 纯 JDK 17 轻量级 QR Code（ISO/IEC 18004）SVG 编码器，零外部依赖。
 * 支持字节模式（Byte Mode）、纠错等级 M、版本 1~9（最大 180 字节，覆盖 Telegram {@code tg://login?token=...} 扫码链接）。
 */
public final class QrCodeSvgEncoder {

    /** 纠错等级 M 对应的 2 位格式标识（00） */
    private static final int EC_FORMAT_BITS_M = 0;

    /** 版本 1~9 的总码字数（数据码字 + 纠错码字） */
    private static final int[] TOTAL_CODEWORDS = {
            0, 26, 44, 70, 100, 134, 172, 196, 242, 292
    };

    /** 版本 1~9 在纠错等级 M 下每块的纠错码字数 */
    private static final int[] EC_CODEWORDS_PER_BLOCK_M = {
            0, 10, 16, 26, 18, 24, 16, 18, 22, 22
    };

    /** 版本 1~9 在纠错等级 M 下的纠错分块数 */
    private static final int[] NUM_EC_BLOCKS_M = {
            0, 1, 1, 1, 2, 2, 4, 4, 4, 5
    };

    /** 版本 1~9 的对齐图案中心坐标表 */
    private static final int[][] ALIGNMENT_POSITIONS = {
            {},
            {},
            {6, 18},
            {6, 22},
            {6, 26},
            {6, 30},
            {6, 34},
            {6, 22, 38},
            {6, 24, 42},
            {6, 26, 46}
    };

    private QrCodeSvgEncoder() {
        throw new UnsupportedOperationException();
    }

    /**
     * 将输入文本编码为包含静区（4 模块白边）的紧凑 SVG 字符串。
     */
    public static String encodeToSvg(String text) {
        boolean[][] modules = encodeMatrix(text);
        int size = modules.length;
        int quiet = 4;
        int total = size + quiet * 2;

        StringBuilder sb = new StringBuilder(total * total / 2 + 256);
        sb.append(TelegramRelayConstants.QR_SVG_PREFIX)
                .append(total).append(' ').append(total)
                .append(TelegramRelayConstants.QR_SVG_MIDDLE);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) {
                    sb.append('M').append(x + quiet).append(',').append(y + quiet)
                            .append(TelegramRelayConstants.QR_SVG_PATH_UNIT);
                }
            }
        }
        sb.append(TelegramRelayConstants.QR_SVG_SUFFIX);
        return sb.toString();
    }

    /**
     * 生成原始二维布尔矩阵（{@code true} 表示深色模块）。
     */
    public static boolean[][] encodeMatrix(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(ExceptionMessageConstants.QR_TEXT_EMPTY);
        }
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int version = selectVersion(utf8.length);
        byte[] dataCodewords = encodeByteModeData(utf8, version);
        byte[] allCodewords = addErrorCorrectionAndInterleave(dataCodewords, version);

        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        drawFunctionPatterns(modules, isFunction, version);
        drawCodewords(modules, isFunction, allCodewords);

        // 依次评估 8 种掩码并挑选惩罚分最低的掩码图案
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(modules, isFunction, mask);
            drawFormatBits(modules, isFunction, mask);
            int penalty = computePenaltyScore(modules);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
            applyMask(modules, isFunction, mask);
        }
        applyMask(modules, isFunction, bestMask);
        drawFormatBits(modules, isFunction, bestMask);
        return modules;
    }

    private static int selectVersion(int byteLen) {
        for (int v = 1; v <= 9; v++) {
            int dataCapBytes = TOTAL_CODEWORDS[v] - EC_CODEWORDS_PER_BLOCK_M[v] * NUM_EC_BLOCKS_M[v];
            // 字节模式头部占用 4 位模式标识 + 8 位长度 = 12 位，加上 4 位终止符共 2 字节开销
            if (byteLen + 2 <= dataCapBytes) {
                return v;
            }
        }
        throw new IllegalArgumentException(ExceptionMessageConstants.QR_TEXT_TOO_LONG_PREFIX + byteLen);
    }

    private static byte[] encodeByteModeData(byte[] data, int version) {
        int dataCapBytes = TOTAL_CODEWORDS[version] - EC_CODEWORDS_PER_BLOCK_M[version] * NUM_EC_BLOCKS_M[version];
        int capBits = dataCapBytes * 8;

        BitBuffer bb = new BitBuffer(capBits);
        // 模式指示符：0100（Byte Mode）
        bb.appendBits(0b0100, 4);
        // 字符计数指示符（版本 1~9 固定 8 位）
        bb.appendBits(data.length, 8);
        for (byte b : data) {
            bb.appendBits(b & 0xFF, 8);
        }
        // 终止符（最多 4 个 0 位）
        bb.appendBits(0, Math.min(4, capBits - bb.length()));
        // 补零至字节边界
        bb.appendBits(0, (8 - (bb.length() % 8)) % 8);
        // 交替填充标准补齐字节 0xEC 与 0x11
        for (int pad = 0xEC; bb.length() < capBits; pad ^= (0xEC ^ 0x11)) {
            bb.appendBits(pad, 8);
        }
        return bb.toByteArray();
    }

    private static byte[] addErrorCorrectionAndInterleave(byte[] data, int version) {
        int numBlocks = NUM_EC_BLOCKS_M[version];
        int blockEcLen = EC_CODEWORDS_PER_BLOCK_M[version];
        int rawCodewords = TOTAL_CODEWORDS[version];
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[] rsDiv = reedSolomonComputeDivisor(blockEcLen);
        byte[][] blocks = new byte[numBlocks][];

        int offset = 0;
        for (int i = 0; i < numBlocks; i++) {
            int dataLen = shortBlockLen - blockEcLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = Arrays.copyOfRange(data, offset, offset + dataLen);
            offset += dataLen;
            byte[] ecc = reedSolomonComputeRemainder(dat, rsDiv);
            byte[] combined = new byte[dataLen + blockEcLen];
            System.arraycopy(dat, 0, combined, 0, dataLen);
            System.arraycopy(ecc, 0, combined, dataLen, blockEcLen);
            blocks[i] = combined;
        }

        byte[] result = new byte[rawCodewords];
        int pos = 0;
        int maxDataLen = shortBlockLen - blockEcLen + (numShortBlocks < numBlocks ? 1 : 0);
        for (int i = 0; i < maxDataLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                int dataLen = blocks[b].length - blockEcLen;
                if (i < dataLen) {
                    result[pos++] = blocks[b][i];
                }
            }
        }
        for (int i = 0; i < blockEcLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                int dataLen = blocks[b].length - blockEcLen;
                result[pos++] = blocks[b][dataLen + i];
            }
        }
        return result;
    }

    private static byte[] reedSolomonComputeDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < result.length; j++) {
                result[j] = (byte) reedSolomonMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) {
                    result[j] ^= result[j + 1];
                }
            }
            root = reedSolomonMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonComputeRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) reedSolomonMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    private static int reedSolomonMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static void drawFunctionPatterns(boolean[][] modules, boolean[][] isFunction, int version) {
        int size = modules.length;

        // 时序图案（第 6 行与第 6 列交替黑白模块）
        for (int i = 0; i < size; i++) {
            setFunctionModule(modules, isFunction, 6, i, i % 2 == 0);
            setFunctionModule(modules, isFunction, i, 6, i % 2 == 0);
        }

        // 三个定位探测图形（左上、右上、左下）
        drawFinderPattern(modules, isFunction, 3, 3);
        drawFinderPattern(modules, isFunction, size - 4, 3);
        drawFinderPattern(modules, isFunction, 3, size - 4);

        // 对齐图案
        int[] alignPos = ALIGNMENT_POSITIONS[version];
        int numAlign = alignPos.length;
        for (int i = 0; i < numAlign; i++) {
            for (int j = 0; j < numAlign; j++) {
                boolean isCornerFinder = (i == 0 && j == 0)
                        || (i == 0 && j == numAlign - 1)
                        || (i == numAlign - 1 && j == 0);
                if (!isCornerFinder) {
                    drawAlignmentPattern(modules, isFunction, alignPos[i], alignPos[j]);
                }
            }
        }

        // 预占位格式信息与固定暗模块
        drawFormatBits(modules, isFunction, 0);
        // 版本 7 及以上绘制 18 位版本信息
        if (version >= 7) {
            drawVersionBits(modules, isFunction, version);
        }
    }

    private static void drawFinderPattern(boolean[][] modules, boolean[][] isFunction, int cx, int cy) {
        int size = modules.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = cx + dx;
                int yy = cy + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    setFunctionModule(modules, isFunction, xx, yy, dist != 2 && dist != 4);
                }
            }
        }
    }

    private static void drawAlignmentPattern(boolean[][] modules, boolean[][] isFunction, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunctionModule(modules, isFunction, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private static void drawFormatBits(boolean[][] modules, boolean[][] isFunction, int mask) {
        int size = modules.length;
        int data = (EC_FORMAT_BITS_M << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        // 第一份格式信息（左上定位图形外缘）
        for (int i = 0; i <= 5; i++) {
            setFunctionModule(modules, isFunction, 8, i, getBit(bits, i));
        }
        setFunctionModule(modules, isFunction, 8, 7, getBit(bits, 6));
        setFunctionModule(modules, isFunction, 8, 8, getBit(bits, 7));
        setFunctionModule(modules, isFunction, 7, 8, getBit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunctionModule(modules, isFunction, 14 - i, 8, getBit(bits, i));
        }

        // 第二份格式信息（右上与左下定位图形外缘）
        for (int i = 0; i < 8; i++) {
            setFunctionModule(modules, isFunction, size - 1 - i, 8, getBit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunctionModule(modules, isFunction, 8, size - 15 + i, getBit(bits, i));
        }
        // 固定暗模块
        setFunctionModule(modules, isFunction, 8, size - 8, true);
    }

    private static void drawVersionBits(boolean[][] modules, boolean[][] isFunction, int version) {
        int size = modules.length;
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean bit = getBit(bits, i);
            int a = size - 11 + (i % 3);
            int b = i / 3;
            setFunctionModule(modules, isFunction, a, b, bit);
            setFunctionModule(modules, isFunction, b, a, bit);
        }
    }

    private static void drawCodewords(boolean[][] modules, boolean[][] isFunction, byte[] data) {
        int size = modules.length;
        int bitIdx = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? (size - 1 - vert) : vert;
                    if (!isFunction[y][x] && bitIdx < data.length * 8) {
                        modules[y][x] = getBit(data[bitIdx >>> 3] & 0xFF, 7 - (bitIdx & 7));
                        bitIdx++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] modules, boolean[][] isFunction, int mask) {
        int size = modules.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) {
                    continue;
                }
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> (x * y) % 2 + (x * y) % 3 == 0;
                    case 6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0;
                    case 7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0;
                    default -> false;
                };
                if (invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    private static int computePenaltyScore(boolean[][] modules) {
        int size = modules.length;
        int penalty = 0;

        // 规则 1：同行/同列连续 5 个及以上同色模块惩罚
        for (int y = 0; y < size; y++) {
            boolean runColor = false;
            int runLen = 0;
            for (int x = 0; x < size; x++) {
                if (modules[y][x] == runColor) {
                    runLen++;
                    if (runLen == 5) {
                        penalty += 3;
                    } else if (runLen > 5) {
                        penalty++;
                    }
                } else {
                    runColor = modules[y][x];
                    runLen = 1;
                }
            }
        }
        for (int x = 0; x < size; x++) {
            boolean runColor = false;
            int runLen = 0;
            for (int y = 0; y < size; y++) {
                if (modules[y][x] == runColor) {
                    runLen++;
                    if (runLen == 5) {
                        penalty += 3;
                    } else if (runLen > 5) {
                        penalty++;
                    }
                } else {
                    runColor = modules[y][x];
                    runLen = 1;
                }
            }
        }

        // 规则 2：2x2 同色块惩罚
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    penalty += 3;
                }
            }
        }

        // 规则 4：深浅模块比例失衡惩罚
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean cell : row) {
                if (cell) {
                    dark++;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        if (k > 0) {
            penalty += k * 10;
        }
        return penalty;
    }

    private static void setFunctionModule(boolean[][] modules, boolean[][] isFunction, int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static boolean getBit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        private final byte[] bytes;
        private int bitLength;

        BitBuffer(int capacityBits) {
            this.bytes = new byte[(capacityBits + 7) / 8];
        }

        void appendBits(int val, int len) {
            for (int i = len - 1; i >= 0; i--) {
                if (((val >>> i) & 1) != 0) {
                    bytes[bitLength >>> 3] |= (byte) (1 << (7 - (bitLength & 7)));
                }
                bitLength++;
            }
        }

        int length() {
            return bitLength;
        }

        byte[] toByteArray() {
            return bytes;
        }
    }
}
