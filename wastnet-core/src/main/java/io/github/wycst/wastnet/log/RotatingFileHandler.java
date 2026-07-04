package io.github.wycst.wastnet.log;

import io.github.wycst.wastnet.util.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Rotating file output handler.
 * <p>
 * Naming convention (using access.log with maxFiles=2):
 * <ul>
 *   <li>Current file: access.log</li>
 *   <li>Backup file: access_0.log</li>
 * </ul>
 * Rotation is triggered when the current file size exceeds maxSize:
 * <ol>
 *   <li>Delete the oldest backup access_0.log (if exists)</li>
 *   <li>Rename access.log to access_0.log</li>
 *   <li>Create a new access.log and continue writing</li>
 * </ol>
 * At most maxFiles files are retained on disk.
 *
 * @Date 2025/6/15
 */
public class RotatingFileHandler extends Handler {

    private static final Charset UTF_8 = Utils.UTF_8;

    private final String baseFilePath;
    private final long maxSize;
    private final int maxFiles;
    private FileOutputStream outputStream;
    private long writtenBytes;

    /**
     * @param baseFilePath file path, e.g. {@code logs/access.out}
     * @param maxSize      max bytes per file
     * @param maxFiles     max number of files to retain (including the current one)
     */
    public RotatingFileHandler(String baseFilePath, long maxSize, int maxFiles) {
        this.baseFilePath = baseFilePath;
        this.maxSize = maxSize;
        this.maxFiles = maxFiles;
    }

    private void ensureOpen() throws IOException {
        if (outputStream != null) {
            return;
        }
        openFile(true);
    }

    private void openFile(boolean append) throws IOException {
        File file = new File(baseFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        writtenBytes = append && file.exists() ? file.length() : 0;
        outputStream = new FileOutputStream(file, append);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }

        // lazy init: open file on first write
        if (outputStream == null) {
            try {
                ensureOpen();
            } catch (IOException e) {
                reportError("Failed to open log file", e, ErrorManager.OPEN_FAILURE);
                return;
            }
        }

        byte[] bytes = getFormatter().format(record).getBytes(UTF_8);

        // check if rotation is needed
        if (maxSize > 0 && writtenBytes + bytes.length > maxSize) {
            try {
                rotate();
            } catch (IOException e) {
                reportError("Rotation failed", e, ErrorManager.GENERIC_FAILURE);
                return;
            }
        }

        try {
            outputStream.write(bytes);
            writtenBytes += bytes.length;
            flush();
        } catch (IOException e) {
            reportError("Write failed", e, ErrorManager.WRITE_FAILURE);
        }
    }

    private String backupFileName(int index) {
        int dot = baseFilePath.lastIndexOf('.');
        if (dot > baseFilePath.lastIndexOf(File.separatorChar)) {
            return baseFilePath.substring(0, dot) + "_" + index + baseFilePath.substring(dot);
        }
        return baseFilePath + "_" + index;
    }

    /**
     * Perform file rotation:
     * <ol>
     *   <li>Delete the oldest backup {@code basePath_<i>n</i>.log}</li>
     *   <li>Shift {@code _i.log} to {@code _i+1.log} sequentially</li>
     *   <li>Rename the current file to {@code _0.log}</li>
     *   <li>Create a new current file</li>
     * </ol>
     */
    private void rotate() throws IOException {
        close();

        File currentFile = new File(baseFilePath);
        if (!currentFile.exists()) {
            openFile(false);
            return;
        }

        // delete the oldest backup
        File oldest = new File(backupFileName(maxFiles - 2));
        if (oldest.exists()) {
            oldest.delete();
        }

        // shift remaining backups
        for (int i = maxFiles - 2; i > 0; i--) {
            File src = new File(backupFileName(i - 1));
            if (src.exists()) {
                src.renameTo(new File(backupFileName(i)));
            }
        }

        // rename current file to _0
        currentFile.renameTo(new File(backupFileName(0)));

        openFile(false);
    }

    @Override
    public synchronized void flush() {
        try {
            if (outputStream != null) {
                outputStream.flush();
            }
        } catch (IOException e) {
            reportError("Flush failed", e, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public synchronized void close() throws SecurityException {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
        } catch (IOException e) {
            reportError("Close failed", e, ErrorManager.CLOSE_FAILURE);
        }
    }
}
