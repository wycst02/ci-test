package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link MultipartFieldFile}.
 *
 * @author wangyc
 */
public class MultipartFieldFileTest {

    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    @Test
    public void testFileFieldProperties() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "file data".getBytes(UTF_8));
            byte[] fname = "upload.txt".getBytes(UTF_8);
            byte[] ctype = "text/plain".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(fname, 0, fname.length);
            HttpBuf contentType = HttpBuf.wrap(ctype, 0, ctype.length);

            MultipartFieldFile field = new MultipartFieldFile("upload", fileName, contentType, tempFile, UTF_8);
            Assertions.assertEquals("upload", field.getName());
            Assertions.assertEquals("upload.txt", field.getFileName());
            Assertions.assertEquals("text/plain", field.getContentType());
            Assertions.assertTrue(field.isFile());
            Assertions.assertTrue(field.isTempFile());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGetDataThrowsUnsupportedOperation() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            MultipartFieldFile field = new MultipartFieldFile("upload", null, null, tempFile, UTF_8);
            Assertions.assertThrows(UnsupportedOperationException.class, () -> field.getData());
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testGetInputStream() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "stream content".getBytes(UTF_8));
            MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
            InputStream is = field.getInputStream();
            Assertions.assertNotNull(is);
            byte[] data = new byte["stream content".length()];
            is.read(data);
            Assertions.assertEquals("stream content", new String(data, UTF_8));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    public void testTransferTo() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        File destFile = File.createTempFile("dest-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "transfer content".getBytes(UTF_8));
            byte[] srcName = "src.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(srcName, 0, srcName.length);
            MultipartFieldFile field = new MultipartFieldFile("file", fileName, null, tempFile, UTF_8);
            field.transferTo(destFile);
            Assertions.assertEquals("transfer content", new String(java.nio.file.Files.readAllBytes(destFile.toPath()), UTF_8));
        } finally {
            tempFile.delete();
            destFile.delete();
        }
    }

    @Test
    public void testTransferToWithAppend() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        File destFile = File.createTempFile("dest-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), " appended".getBytes(UTF_8));
            java.nio.file.Files.write(destFile.toPath(), "initial".getBytes(UTF_8));
            byte[] srcName2 = "src.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(srcName2, 0, srcName2.length);
            MultipartFieldFile field = new MultipartFieldFile("file", fileName, null, tempFile, UTF_8);
            field.transferTo(destFile, true);
            Assertions.assertEquals("initial appended", new String(java.nio.file.Files.readAllBytes(destFile.toPath()), UTF_8));
        } finally {
            tempFile.delete();
            destFile.delete();
        }
    }

    @Test
    public void testReleaseDeletesTempFile() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        java.nio.file.Files.write(tempFile.toPath(), "data".getBytes(UTF_8));
        MultipartFieldFile field = new MultipartFieldFile("file", null, null, tempFile, UTF_8);
        Assertions.assertTrue(tempFile.exists());
        field.release();
        Assertions.assertFalse(tempFile.exists());
    }

    @Test
    public void testGetDataAsString() throws Exception {
        File tempFile = File.createTempFile("upload-", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), "text data".getBytes(UTF_8));
            byte[] textName = "text.txt".getBytes(UTF_8);
            HttpBuf fileName = HttpBuf.wrap(textName, 0, textName.length);
            MultipartFieldFile field = new MultipartFieldFile("field", fileName, null, tempFile, UTF_8);
            // getDataAsString should throw for temp file-backed fields
            try {
                field.getDataAsString();
                Assertions.fail("Expected UnsupportedOperationException");
            } catch (UnsupportedOperationException e) {
                // expected
            }
        } finally {
            tempFile.delete();
        }
    }
}
