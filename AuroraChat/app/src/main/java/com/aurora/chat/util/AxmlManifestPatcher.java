package com.aurora.chat.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 改写二进制 AndroidManifest.xml（AXML）。
 *
 * 不再依赖任何硬编码旧值：直接在 XML 节点里定位 <manifest> 的 package 属性与 <application> 的 label 属性，
 * 改写其字符串池内容；同时精确改写 <manifest>/<application> 上整型、布尔 typedValue 的 data
 * （versionCode、usesCleartextTraffic）。ResourceMap 与节点结构原样保留。
 */
public final class AxmlManifestPatcher {

    private AxmlManifestPatcher() {
    }

    /**
     * 兼容旧调用：不修改屏幕方向。
     */
    public static byte[] patchManifest(byte[] a, String newPackage, String newLabel,
                                       String versionName, Integer versionCode, Boolean cleartext) {
        return patchManifest(a, newPackage, newLabel, versionName, versionCode, cleartext, null);
    }

    /**
     * @param newPackage        新包名（null = 不改）
     * @param newLabel          新应用名（null = 不改）
     * @param versionName       新版本名（null = 不改）
     * @param versionCode       新版本号（null = 不改）
     * @param cleartext         是否允许明文流量（null = 不改）
     * @param screenOrientation 屏幕方向 enum 值（null = 不改）：
     *                          ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE=0、
     *                          PORTRAIT=1、USER=2、SENSOR=4。
     *                          写入清单后系统创Activity 即为目标方向，不再运行时旋转。
     */
    public static byte[] patchManifest(byte[] a, String newPackage, String newLabel,
                                       String versionName, Integer versionCode, Boolean cleartext,
                                       Integer screenOrientation) {
        final int poolOff = 8; // 顶层 XML chunk header 固定 8 字节
        if (u16(a, poolOff) != 0x0001) throw new IllegalStateException("AXML 偏移 8 处不是字符串池");
        int headerSize = u16(a, poolOff + 2);
        int poolSize = i32(a, poolOff + 4);
        int stringCount = i32(a, poolOff + 8);
        int styleCount = i32(a, poolOff + 12);
        int flags = i32(a, poolOff + 16);
        int stringsStart = i32(a, poolOff + 20);
        int stylesStart = i32(a, poolOff + 24);
        boolean utf8 = (flags & (1 << 8)) != 0;
        int offsetsBase = poolOff + headerSize;
        int dataBase = poolOff + stringsStart;

        String[] strings = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int off = i32(a, offsetsBase + i * 4);
            strings[i] = decode(a, dataBase + off, utf8);
        }

        int restStart = poolOff + poolSize;
        int restLen = a.length - restStart;
        byte[] rest = Arrays.copyOfRange(a, restStart, a.length);

        // 遍历节点，按属性名定位要改的项
        int pkgIdx = -1;          // package 属性值的字符串池下标
        int labelIdx = -1;        // application/label 属性值的字符串池下标
        int versionNameIdx = -1;
        int versionCodePos = -1;  // rest 内偏移
        int cleartextPos = -1;    // rest 内偏移
        int orientPos = -1;       // screenOrientation 的 typedValue.data 在 rest 内偏移
        int p = restStart;
        if (p + 8 <= a.length && u16(a, p) == 0x0180) p += i32(a, p + 4); // 跳过 ResourceMap
        while (p + 8 <= a.length) {
            int type = u16(a, p);
            int nodeHeaderSize = u16(a, p + 2);
            int size = i32(a, p + 4);
            if (size <= 0) break;
            if (type == 0x0102) { // START_ELEMENT
                int attrExt = p + nodeHeaderSize;
                int elNameIdx = i32(a, attrExt + 4);
                String el = (elNameIdx >= 0 && elNameIdx < stringCount) ? strings[elNameIdx] : "";
                int attributeStart = u16(a, attrExt + 8);
                int attributeSize = u16(a, attrExt + 10);
                int attributeCount = u16(a, attrExt + 12);
                int attrPos = attrExt + attributeStart;
                for (int i = 0; i < attributeCount; i++) {
                    int anIdx = i32(a, attrPos + 4);
                    String an = (anIdx >= 0 && anIdx < stringCount) ? strings[anIdx] : "";
                    int rawIdx = i32(a, attrPos + 8);
                    if ("manifest".equals(el)) {
                        if ("package".equals(an)) pkgIdx = rawIdx;
                        if ("versionName".equals(an)) versionNameIdx = rawIdx;
                        if ("versionCode".equals(an)) versionCodePos = attrPos + 16 - restStart;
                    }
                    if ("application".equals(el)) {
                        if ("label".equals(an)) labelIdx = rawIdx;
                        if ("usesCleartextTraffic".equals(an)) cleartextPos = attrPos + 16 - restStart;
                    }
                    // 壳模板只有一个 <activity>；首次命中即锁定，避免多 activity 时误改
                    if ("activity".equals(el) && orientPos < 0 && "screenOrientation".equals(an)) {
                        orientPos = attrPos + 16 - restStart;
                    }
                    attrPos += attributeSize;
                }
            }
            p += size;
        }

        if (newPackage != null && pkgIdx >= 0 && pkgIdx < stringCount) strings[pkgIdx] = newPackage;
        if (newLabel != null && labelIdx >= 0 && labelIdx < stringCount) strings[labelIdx] = newLabel;
        if (versionName != null && versionNameIdx >= 0 && versionNameIdx < stringCount) {
            strings[versionNameIdx] = versionName;
        }
        if (versionCode != null && versionCodePos >= 0 && versionCodePos + 4 <= rest.length) {
            putI32(rest, versionCodePos, versionCode);
        }
        if (cleartext != null && cleartextPos >= 0 && cleartextPos + 4 <= rest.length) {
            putI32(rest, cleartextPos, cleartext ? -1 : 0);
        }
        if (screenOrientation != null && orientPos >= 0 && orientPos + 4 <= rest.length) {
            putI32(rest, orientPos, screenOrientation);
        }

        // 重新编码字符串池（每个字符串 4 字节对齐）
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] newOffsets = new int[stringCount];
        for (int i = 0; i < stringCount; i++) {
            newOffsets[i] = data.size();
            byte[] enc = encode(strings[i], utf8);
            data.write(enc, 0, enc.length);
            while ((data.size() & 3) != 0) data.write(0);
        }
        byte[] stringData = data.toByteArray();

        int newStringsStart = headerSize + 4 * stringCount + 4 * styleCount;
        int stylesLen = 0;
        byte[] styleData = null;
        if (styleCount > 0) {
            stylesLen = poolSize - stylesStart;
            styleData = Arrays.copyOfRange(a, poolOff + stylesStart, poolOff + stylesStart + stylesLen);
        }
        int newStylesStart = styleCount > 0 ? newStringsStart + stringData.length : 0;
        int newPoolSize = newStringsStart + stringData.length + stylesLen;

        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + 256);
        writeU16(out, 0x0003);
        writeU16(out, 8);
        writeI32(out, 8 + newPoolSize + restLen);
        writeU16(out, 0x0001);
        writeU16(out, headerSize);
        writeI32(out, newPoolSize);
        writeI32(out, stringCount);
        writeI32(out, styleCount);
        writeI32(out, flags & ~1); // 清除 SORTED 标志
        writeI32(out, newStringsStart);
        writeI32(out, newStylesStart);
        for (int i = 0; i < stringCount; i++) writeI32(out, newOffsets[i]);
        if (styleCount > 0) {
            int styleOffBase = offsetsBase + stringCount * 4;
            for (int i = 0; i < styleCount; i++) writeI32(out, i32(a, styleOffBase + i * 4));
        }
        out.write(stringData, 0, stringData.length);
        if (styleCount > 0 && styleData != null) out.write(styleData, 0, styleData.length);
        out.write(rest, 0, rest.length);
        return out.toByteArray();
    }

    /**
     * 直读二进制 AndroidManifest.xml 的 &lt;manifest package&gt; 值。
     * 不经过 PackageManager，因此不受其“按路径缓存旧解析结果”的影响，用作产物的权威校验。
     * 解析失败返回 null。
     */
    public static String readManifestPackage(byte[] a) {
        try {
            final int poolOff = 8;
            if (a == null || a.length < 32 || u16(a, poolOff) != 0x0001) return null;
            int headerSize = u16(a, poolOff + 2);
            int poolSize = i32(a, poolOff + 4);
            int stringCount = i32(a, poolOff + 8);
            int flags = i32(a, poolOff + 16);
            int stringsStart = i32(a, poolOff + 20);
            boolean utf8 = (flags & (1 << 8)) != 0;
            int offsetsBase = poolOff + headerSize;
            int dataBase = poolOff + stringsStart;

            String[] strings = new String[stringCount];
            for (int i = 0; i < stringCount; i++) {
                int off = i32(a, offsetsBase + i * 4);
                if (off < 0 || dataBase + off >= a.length) return null;
                strings[i] = decode(a, dataBase + off, utf8);
            }

            int p = poolOff + poolSize;
            if (p + 8 <= a.length && u16(a, p) == 0x0180) p += i32(a, p + 4); // 跳过 ResourceMap
            while (p + 8 <= a.length) {
                int type = u16(a, p);
                int nodeHeaderSize = u16(a, p + 2);
                int size = i32(a, p + 4);
                if (size <= 0) break;
                if (type == 0x0102) { // START_ELEMENT
                    int attrExt = p + nodeHeaderSize;
                    int elNameIdx = i32(a, attrExt + 4);
                    String el = (elNameIdx >= 0 && elNameIdx < stringCount) ? strings[elNameIdx] : "";
                    if ("manifest".equals(el)) {
                        int attributeStart = u16(a, attrExt + 8);
                        int attributeSize = u16(a, attrExt + 10);
                        int attributeCount = u16(a, attrExt + 12);
                        int attrPos = attrExt + attributeStart;
                        for (int i = 0; i < attributeCount; i++) {
                            int anIdx = i32(a, attrPos + 4);
                            String an = (anIdx >= 0 && anIdx < stringCount) ? strings[anIdx] : "";
                            if ("package".equals(an)) {
                                int rawIdx = i32(a, attrPos + 8);
                                return (rawIdx >= 0 && rawIdx < stringCount) ? strings[rawIdx] : null;
                            }
                            attrPos += attributeSize;
                        }
                        return null;
                    }
                }
                p += size;
            }
        } catch (Throwable ignored) {
            // 结构异常时按“读取失败”处理，不影响打包流程
        }
        return null;
    }

    /**
     * 改写 resources.arsc 里 ResTable_package 的包名字段（固定 256 字节、UTF-16LE、零填充）。
     * 找不到时原样返回。就地修改传入数组。
     */
    public static byte[] patchArscPackageName(byte[] a, String pkg) {
        int off = 12; // ResTable_header 固定 12 字节
        while (off + 8 <= a.length) {
            int type = u16(a, off);
            int size = i32(a, off + 4);
            if (size <= 0 || off + size > a.length) break;
            if (type == 0x0200) { // RES_TABLE_PACKAGE_TYPE
                byte[] nameBytes = pkg.getBytes(StandardCharsets.UTF_16LE);
                int nameLen = Math.min(nameBytes.length, 254);
                System.arraycopy(nameBytes, 0, a, off + 12, nameLen);
                for (int i = off + 12 + nameLen; i < off + 12 + 256; i++) a[i] = 0;
                return a;
            }
            off += size;
        }
        return a;
    }

    private static String decode(byte[] a, int p, boolean utf8) {
        if (utf8) {
            int[] r1 = readLen(a, p);
            p = r1[1];
            int[] r2 = readLen(a, p);
            int u8len = r2[0];
            p = r2[1];
            return new String(a, p, u8len, StandardCharsets.UTF_8);
        }
        int len = u16(a, p);
        p += 2;
        return new String(a, p, len * 2, StandardCharsets.UTF_16LE);
    }

    private static byte[] encode(String s, boolean utf8) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeLen(o, s.length());
            writeLen(o, bytes.length);
            o.write(bytes, 0, bytes.length);
            o.write(0);
        } else {
            byte[] b = s.getBytes(StandardCharsets.UTF_16LE);
            writeU16(o, s.length());
            o.write(b, 0, b.length);
            writeU16(o, 0);
        }
        return o.toByteArray();
    }

    private static int[] readLen(byte[] a, int p) {
        int b0 = a[p] & 0xFF;
        if ((b0 & 0x80) != 0) {
            return new int[]{((b0 & 0x7F) << 8) | (a[p + 1] & 0xFF), p + 2};
        }
        return new int[]{b0, p + 1};
    }

    private static void writeLen(ByteArrayOutputStream o, int v) {
        if (v < 0x80) {
            o.write(v);
        } else {
            o.write(((v >> 8) & 0x7F) | 0x80);
            o.write(v & 0xFF);
        }
    }

    private static int u16(byte[] a, int p) {
        return (a[p] & 0xFF) | ((a[p + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] a, int p) {
        return (a[p] & 0xFF) | ((a[p + 1] & 0xFF) << 8)
                | ((a[p + 2] & 0xFF) << 16) | ((a[p + 3] & 0xFF) << 24);
    }

    private static void putI32(byte[] a, int p, int v) {
        a[p] = (byte) (v & 0xFF);
        a[p + 1] = (byte) ((v >> 8) & 0xFF);
        a[p + 2] = (byte) ((v >> 16) & 0xFF);
        a[p + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeI32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }
}
