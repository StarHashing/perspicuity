package com.starhashing.perspicuity;

import java.util.Locale;

/**
 * 字体文件判定的单一真源。
 *
 * 「什么算一个字体文件」这个判断同时发生在三处：SAF 递归扫描、单文件多选导入、
 * 以及列出已导入字体时对磁盘文件的复核。三处若各写一份扩展名表，早晚会分叉
 * （比如某处忘了加 .otc），所以集中到这里。
 *
 * MIME 判定同样有意义：Android 11+ 的 DocumentsUI 在某些 provider（尤其云盘、
 * 压缩包内文件）上会把 .ttf 报成 application/octet-stream。所以扩展名优先，
 * MIME 只当辅助证据，而不是门槛 —— 门槛太严会让用户「明明看到字体却导不进来」。
 */
final class FontFileTypes {

    /** 允许导入的字体扩展名。otc 是 OpenType 集合，与 ttc 同类。 */
    static final String[] EXTENSIONS = { ".ttf", ".otf", ".ttc", ".otc", ".woff", ".woff2" };

    private FontFileTypes() {}

    /**
     * 判断一个文档项是否可能是字体文件。
     *
     * @param displayName SAF 提供的显示名（可能为 null）
     * @param mimeType    SAF 提供的 MIME（可能为 null，provider 不保证提供）
     */
    static boolean isFontCandidate(String displayName, String mimeType) {
        String extension = extensionOf(displayName);
        if (extension != null && isFontExtension(extension)) {
            return true;
        }
        // 名称拿不到或没有可识别扩展名时，再看 MIME 是否明确声明是字体。
        return isFontMimeType(mimeType);
    }

    /** 判断扩展名（含前导点，大小写不敏感）是否属于字体格式。 */
    static boolean isFontExtension(String extension) {
        if (extension == null || extension.length() == 0) {
            return false;
        }
        String normalized = extension.toLowerCase(Locale.US);
        for (String allowed : EXTENSIONS) {
            if (allowed.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 判断 MIME 是否是明确的字体类型（application/octet-stream 不算，它太泛）。 */
    static boolean isFontMimeType(String mimeType) {
        if (mimeType == null || mimeType.length() == 0) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.US);
        return normalized.startsWith("font/")
            || normalized.equals("application/x-font-ttf")
            || normalized.equals("application/x-font-otf")
            || normalized.equals("application/x-font-ttc")
            || normalized.equals("application/font-woff")
            || normalized.equals("application/x-font-woff")
            || normalized.equals("application/font-sfnt")
            || normalized.equals("application/vnd.ms-opentype");
    }

    /**
     * 返回文件名的小写扩展名（含点）；没有扩展名时返回 null。
     * 注意从最后一段开始找：字体名里带点的很常见，如 "FZHei-B01S.ttf" 没问题，
     * 但 "some.font.file.otf" 必须识别为 .otf 而不是 .font.file.otf。
     */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return null;
        }
        return base.substring(dot).toLowerCase(Locale.US);
    }

    /**
     * 去掉扩展名，返回可作字体族名的部分。
     *
     * 用于把落盘文件名（已 sanitize 成 ASCII）变成一个稳定的族名。
     * 例如 "a1b2c3d4_FZHei-B01S.ttf" -> "a1b2c3d4_FZHei-B01S"。
     */
    static String stripExtensionOf(String fileName) {
        if (fileName == null || fileName.length() == 0) {
            return "";
        }
        String extension = extensionOf(fileName);
        if (extension == null) {
            return fileName;
        }
        return fileName.substring(0, fileName.length() - extension.length());
    }
}
