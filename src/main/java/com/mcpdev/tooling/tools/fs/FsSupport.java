package com.mcpdev.tooling.tools.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mcpdev.tooling.registry.ToolExecutionException;

/** Shared FS mechanics: binary sniffing, decode, capped reads. */
final class FsSupport {

    private static final int SNIFF_BYTES = 8192;

    private FsSupport() {
    }

    /**
     * NUL-byte or UTF-16 BOM in the first 8 KiB -> treat as binary. Cheap and
     * conservative; a text file with a stray NUL later in the stream is still read
     * (decode replaces bad sequences), but a NUL near the start reliably means binary.
     */
    static boolean looksBinary(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(SNIFF_BYTES);
            if (buf.length >= 2
                    && ((buf[0] == (byte) 0xFE && buf[1] == (byte) 0xFF)
                        || (buf[0] == (byte) 0xFF && buf[1] == (byte) 0xFE))) {
                return true;
            }
            for (byte b : buf) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Reads at most {@code cap} bytes; {@code truncated} reports whether more existed.
     * Decoding is lenient (UTF-8, malformed -> U+FFFD) so odd encodings still render.
     */
    record Content(String text, boolean truncated, long totalSize) {
    }

    static Content readCapped(Path file, long cap) throws IOException {
        long totalSize = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) Math.min(cap, Integer.MAX_VALUE - 1));
            boolean truncated = totalSize > bytes.length;
            return new Content(new String(bytes, StandardCharsets.UTF_8), truncated, totalSize);
        }
    }
}
