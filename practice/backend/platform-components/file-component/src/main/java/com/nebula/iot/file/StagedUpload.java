package com.nebula.iot.file;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Bounded disk staging; computes the digest from actual bytes without buffering the file in heap. */
public final class StagedUpload implements AutoCloseable {
    private final Path path;
    private final long size;
    private final String sha256;
    private boolean closed;
    private StagedUpload(Path path,long size,String sha256) { this.path=path; this.size=size; this.sha256=sha256; }
    public static StagedUpload read(InputStream source,long maximumBytes) throws IOException {
        if (maximumBytes<1) throw new IllegalArgumentException("Upload limit must be positive");
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Path path=Files.createTempFile("nebula-upload-", ".tmp");
        try {
            long size=0;
            try (OutputStream out=Files.newOutputStream(path)) {
                byte[] buffer=new byte[64*1024]; int n;
                while ((n=source.read(buffer))!=-1) {
                    if (n>maximumBytes-size) throw new TooLargeException();
                    out.write(buffer,0,n); digest.update(buffer,0,n); size+=n;
                }
            }
            if (size==0) throw new IllegalArgumentException("Empty uploads are not supported");
            return new StagedUpload(path,size,HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(path); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
    public long size() { return size; }
    public String sha256() { return sha256; }
    public InputStream open() throws IOException { checkOpen(); return Files.newInputStream(path); }
    // Package-private: providers can stream the staged path with retry support.
    Path path() { checkOpen(); return path; }
    private void checkOpen() { if (closed) throw new IllegalStateException("Upload already closed"); }
    @Override public void close() throws IOException { Files.deleteIfExists(path); closed=true; }
    public static final class TooLargeException extends IOException {
        public TooLargeException() { super("Upload exceeds configured maximum size"); }
    }
}
