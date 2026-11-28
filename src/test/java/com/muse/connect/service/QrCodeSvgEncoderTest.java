package com.muse.connect.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 纯 JDK 17 QR Code（ISO/IEC 18004）SVG 编码器单元测试。
 * 除基础结构断言外，内置独立解码校验器：解析两份格式信息、反掩码提取码字、校验每个 RS 分块的伴随式为 0 并还原原始字节负载，
 * 确保任意版本（V1~V9）生成的二维码均可被 Telegram 客户端正常扫描识别。
 */
class QrCodeSvgEncoderTest {

    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134, 172, 196, 242, 292};
    private static final int[] EC_PER_BLOCK_M = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22};
    private static final int[] NUM_BLOCKS_M = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5};
    private static final int[][] ALIGN_POS = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}
    };

    @Test
    void encodesTelegramLoginUrlToValidDecodableSvg() {
        String tgLoginUrl = "tg://login?token=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA";
        String svg = QrCodeSvgEncoder.encodeToSvg(tgLoginUrl);

        // 60 字节载荷使用版本 4（33x33），加上左右/上下各 4 模块静区为 41x41
        assertThat(svg)
                .startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 41 41\"")
                .contains("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
                .contains("<path fill=\"#0f172a\" d=\"")
                .endsWith("\"/></svg>");

        boolean[][] matrix = QrCodeSvgEncoder.encodeMatrix(tgLoginUrl);
        assertThat(matrix.length).isEqualTo(33);
        assertThat(decodeAndVerify(matrix)).isEqualTo(tgLoginUrl);
    }

    @Test
    void decodesAcrossAllSupportedVersionsOneThroughNine() {
        String[] payloads = {
                "hello",                                           // 版本 1 (5B)
                "https://t.me/login?token=short123",               // 版本 3 (33B)
                "tg://login?token=" + "A".repeat(43),              // 版本 4 (60B)
                "tg://login?token=" + "B".repeat(65),              // 版本 5 (82B)
                "tg://login?token=" + "C".repeat(88),              // 版本 6 (105B)
                "tg://login?token=" + "D".repeat(104),             // 版本 7 (121B，含 18 位版本信息)
                "tg://login?token=" + "E".repeat(130),             // 版本 8 (147B，不等长分块)
                "tg://login?token=" + "F".repeat(163)              // 版本 9 (180B，最大支持长度)
        };

        for (String payload : payloads) {
            boolean[][] matrix = QrCodeSvgEncoder.encodeMatrix(payload);
            assertThat(decodeAndVerify(matrix))
                    .as("载荷长度=%d 解码结果应与原文完全一致", payload.length())
                    .isEqualTo(payload);
        }
    }

    @Test
    void rejectsEmptyAndOversizedInput() {
        assertThatThrownBy(() -> QrCodeSvgEncoder.encodeToSvg(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrCodeSvgEncoder.encodeToSvg(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QrCodeSvgEncoder.encodeToSvg("x".repeat(181)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 独立 ISO/IEC 18004 解码与校验器：验证定位图、时序图、固定暗模块、两份一致的格式信息（BCH 校验）、
     * 版本 7~9 的版本信息（BCH 校验）、每块 Reed-Solomon 伴随式全为 0，并还原 Byte Mode 原文。
     */
    private static String decodeAndVerify(boolean[][] modules) {
        int size = modules.length;
        int version = (size - 17) / 4;
        assertThat(version).isBetween(1, 9);

        // 校验固定暗模块与时序图案
        assertThat(modules[size - 8][8]).isTrue();
        for (int i = 8; i < size - 8; i++) {
            assertThat(modules[6][i]).isEqualTo(i % 2 == 0);
            assertThat(modules[i][6]).isEqualTo(i % 2 == 0);
        }

        // 读取并校验两份 15 位格式信息完全一致且 BCH 余数为 0
        int fmt1 = 0;
        for (int i = 0; i <= 5; i++) {
            if (modules[i][8]) fmt1 |= (1 << i);
        }
        if (modules[7][8]) fmt1 |= (1 << 6);
        if (modules[8][8]) fmt1 |= (1 << 7);
        if (modules[8][7]) fmt1 |= (1 << 8);
        for (int i = 9; i < 15; i++) {
            if (modules[8][14 - i]) fmt1 |= (1 << i);
        }

        int fmt2 = 0;
        for (int i = 0; i < 8; i++) {
            if (modules[8][size - 1 - i]) fmt2 |= (1 << i);
        }
        for (int i = 8; i < 15; i++) {
            if (modules[size - 15 + i][8]) fmt2 |= (1 << i);
        }
        assertThat(fmt1).isEqualTo(fmt2);

        int unmaskedFmt = fmt1 ^ 0x5412;
        int ecLevel = (unmaskedFmt >>> 13) & 0x3;
        int mask = (unmaskedFmt >>> 10) & 0x7;
        assertThat(ecLevel).isEqualTo(0); // 纠错等级 M = 00
        assertThat(bchRemainder(unmaskedFmt, 0x537, 10)).isZero();

        // 版本 7~9 校验两份 18 位版本信息
        if (version >= 7) {
            int ver1 = 0;
            int ver2 = 0;
            for (int i = 0; i < 18; i++) {
                int a = size - 11 + (i % 3);
                int b = i / 3;
                if (modules[b][a]) ver1 |= (1 << i);
                if (modules[a][b]) ver2 |= (1 << i);
            }
            assertThat(ver1).isEqualTo(ver2);
            assertThat(ver1 >>> 12).isEqualTo(version);
            assertThat(bchRemainder(ver1, 0x1F25, 12)).isZero();
        }

        // 标记功能区并按蛇形顺序反掩码提取全部码字
        boolean[][] isFunc = buildFunctionMask(size, version);
        int rawCodewords = TOTAL_CODEWORDS[version];
        byte[] allCodewords = new byte[rawCodewords];
        int bitIdx = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? (size - 1 - vert) : vert;
                    if (!isFunc[y][x] && bitIdx < rawCodewords * 8) {
                        boolean bit = modules[y][x] ^ maskBit(mask, x, y);
                        if (bit) {
                            allCodewords[bitIdx >>> 3] |= (byte) (1 << (7 - (bitIdx & 7)));
                        }
                        bitIdx++;
                    }
                }
            }
        }
        assertThat(bitIdx).isEqualTo(rawCodewords * 8);

        // 解交织并校验每个分块的 Reed-Solomon 伴随式均为 0
        int numBlocks = NUM_BLOCKS_M[version];
        int ecLen = EC_PER_BLOCK_M[version];
        int numShort = numBlocks - rawCodewords % numBlocks;
        int shortLen = rawCodewords / numBlocks;
        int maxDataLen = shortLen - ecLen + (numShort < numBlocks ? 1 : 0);

        byte[][] blockData = new byte[numBlocks][];
        byte[][] blockEc = new byte[numBlocks][ecLen];
        for (int b = 0; b < numBlocks; b++) {
            int dataLen = shortLen - ecLen + (b < numShort ? 0 : 1);
            blockData[b] = new byte[dataLen];
        }

        int pos = 0;
        for (int i = 0; i < maxDataLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                if (i < blockData[b].length) {
                    blockData[b][i] = allCodewords[pos++];
                }
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                blockEc[b][i] = allCodewords[pos++];
            }
        }

        int totalDataBytes = rawCodewords - numBlocks * ecLen;
        byte[] dataBytes = new byte[totalDataBytes];
        int dataPos = 0;
        for (int b = 0; b < numBlocks; b++) {
            // 校验码字多项式在 α^0, α^1, ..., α^(ecLen-1) 处的求值全部为 0
            byte[] fullBlock = new byte[blockData[b].length + ecLen];
            System.arraycopy(blockData[b], 0, fullBlock, 0, blockData[b].length);
            System.arraycopy(blockEc[b], 0, fullBlock, blockData[b].length, ecLen);
            int alpha = 1;
            for (int k = 0; k < ecLen; k++) {
                int syn = 0;
                for (byte cw : fullBlock) {
                    syn = gfMul(syn, alpha) ^ (cw & 0xFF);
                }
                assertThat(syn).as("版本 %d 第 %d 块伴随式 S_%d 必须为 0", version, b, k).isZero();
                alpha = gfMul(alpha, 2);
            }
            System.arraycopy(blockData[b], 0, dataBytes, dataPos, blockData[b].length);
            dataPos += blockData[b].length;
        }

        // 解析 Byte Mode（0100）+ 8 位长度 + 原始 UTF-8 字节
        int mode = readBits(dataBytes, 0, 4);
        assertThat(mode).isEqualTo(0b0100);
        int byteLen = readBits(dataBytes, 4, 8);
        byte[] out = new byte[byteLen];
        for (int i = 0; i < byteLen; i++) {
            out[i] = (byte) readBits(dataBytes, 12 + i * 8, 8);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static boolean[][] buildFunctionMask(int size, int version) {
        boolean[][] f = new boolean[size][size];
        for (int i = 0; i < size; i++) {
            f[6][i] = true;
            f[i][6] = true;
        }
        markSquare(f, 0, 0, 9, 9);
        markSquare(f, size - 8, 0, 8, 9);
        markSquare(f, 0, size - 8, 9, 8);
        int[] align = ALIGN_POS[version];
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                boolean corner = (i == 0 && j == 0)
                        || (i == 0 && j == align.length - 1)
                        || (i == align.length - 1 && j == 0);
                if (!corner) {
                    markSquare(f, align[i] - 2, align[j] - 2, 5, 5);
                }
            }
        }
        if (version >= 7) {
            markSquare(f, size - 11, 0, 3, 6);
            markSquare(f, 0, size - 11, 6, 3);
        }
        return f;
    }

    private static void markSquare(boolean[][] f, int x0, int y0, int w, int h) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                if (y >= 0 && y < f.length && x >= 0 && x < f.length) {
                    f[y][x] = true;
                }
            }
        }
    }

    private static boolean maskBit(int mask, int x, int y) {
        return switch (mask) {
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
    }

    private static int bchRemainder(int val, int poly, int degree) {
        int rem = val;
        for (int bit = 20; bit >= degree; bit--) {
            if (((rem >>> bit) & 1) != 0) {
                rem ^= (poly << (bit - degree));
            }
        }
        return rem;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int readBits(byte[] bytes, int bitOffset, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            int pos = bitOffset + i;
            int bit = (bytes[pos >>> 3] >>> (7 - (pos & 7))) & 1;
            v = (v << 1) | bit;
        }
        return v;
    }
}
