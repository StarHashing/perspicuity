package com.starhashing.perspicuity;

/**
 * Checked failure with a stable machine-readable code that the Capacitor
 * bridge forwards to the WebView error mapping.
 */
class DocumentReadException extends Exception {

    final String code;

    DocumentReadException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 需要保留底层异常（如 ZipException）用于日志归类时用这个。 */
    DocumentReadException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
