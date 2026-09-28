package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class RelocatorStreamTest {

    /** A path is moved wherever it falls, including across the chunk boundary. */
    @Test
    public void relocatesPathsSplitAcrossChunks() throws Exception {
        String path = "/data/data/com.termux/files/usr/bin/sh";
        String want = "/data/data/" + Relocator.OWN + "/files/usr/bin/sh";
        byte[] buf = new byte[64];
        for (int offset = 0; offset < 3 * buf.length; offset++) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < offset; i++) in.append('x');
            in.append(path).append(" com.termux.files ").append(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            TermuxInstaller.copyRelocated(new ByteArrayInputStream(in.toString().getBytes(StandardCharsets.US_ASCII)), out, buf);
            String expected = in.toString().replace(path, want).replace("com.termux.files", Relocator.OWN + ".files");
            Assert.assertEquals("offset " + offset, expected, out.toString("US-ASCII"));
        }
    }
}
