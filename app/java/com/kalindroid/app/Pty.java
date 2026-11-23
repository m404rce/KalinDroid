package com.kalindroid.app;

final class Pty {
    static {
        System.loadLibrary("pty");
    }

    private Pty() {}

    static native int nativeOpen(String[] argv, String[] envp, String cwd, int cols, int rows);
    static native int nativeRead(int fd, byte[] buffer, int offset, int length);
    static native int nativeWrite(int fd, byte[] buffer, int offset, int length);
    static native void nativeResize(int fd, int cols, int rows);
    static native void nativeClose(int fd);

    static int read(int fd, byte[] buffer) {
        return nativeRead(fd, buffer, 0, buffer.length);
    }

    static int write(int fd, byte[] data) {
        return nativeWrite(fd, data, 0, data.length);
    }
}
