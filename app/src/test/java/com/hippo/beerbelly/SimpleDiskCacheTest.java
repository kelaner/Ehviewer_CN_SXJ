package com.hippo.beerbelly;

import android.app.Application;

import com.hippo.streampipe.InputStreamPipe;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** A committed cache entry owns bytes on disk, not a permanently open output stream. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class SimpleDiskCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void manyPutsPreserveEveryEntryByteForByte() throws Exception {
        SimpleDiskCache cache = new SimpleDiskCache(temporary.newFolder(), 16 * 1024 * 1024);
        assertTrue(cache.isValid());
        try {
            for (int i = 0; i < 1201; i++) put(cache, i);
            cache.flush();
            for (int i = 1200; i >= 0; i--) {
                InputStreamPipe pipe = cache.getInputStreamPipe("entry-" + i);
                assertNotNull("Missing committed entry " + i, pipe);
                pipe.obtain();
                try {
                    InputStream input = pipe.open();
                    assertArrayEquals("Corrupt committed entry " + i, payload(i), input.readAllBytes());
                } finally {
                    pipe.close();
                    pipe.release();
                }
            }
        } finally {
            cache.clear();
        }
    }

    @Test public void manyPutsDoNotKeepOneFileDescriptorPerEntry() throws Exception {
        assumeTrue("Host does not expose open file descriptor counts", openFileDescriptors() >= 0);
        SimpleDiskCache cache = new SimpleDiskCache(temporary.newFolder(), 16 * 1024 * 1024);
        assertTrue(cache.isValid());
        try {
            // Warm up class loading and the journal before measuring; never force GC to hide a leak.
            for (int i = 0; i < 32; i++) put(cache, i);
            cache.flush();
            long baseline = openFileDescriptors();
            for (int batch = 0; batch < 3; batch++) {
                for (int i = 32 + batch * 400; i < 32 + (batch + 1) * 400; i++) put(cache, i);
                cache.flush();
                long current = openFileDescriptors();
                assumeTrue("Host stopped exposing file descriptor counts", current >= 0);
                assertTrue("FDs grew with committed entries: baseline=" + baseline + ", current=" + current,
                        current <= baseline + 32);
            }
        } finally {
            cache.clear();
        }
    }

    private static void put(SimpleDiskCache cache, int index) throws Exception {
        if ((index & 1) == 0) {
            assertTrue("Failed to commit entry " + index,
                    cache.put("entry-" + index, new ByteArrayInputStream(payload(index))));
        } else {
            com.hippo.streampipe.OutputStreamPipe pipe = cache.getOutputStreamPipe("entry-" + index);
            pipe.obtain();
            try {
                pipe.open().write(payload(index));
            } finally {
                // This API owns the returned stream and must close it before commit.
                pipe.close();
                pipe.release();
            }
        }
    }

    private static byte[] payload(int index) {
        byte[] bytes = new byte[4096];
        Arrays.fill(bytes, (byte) (index * 31));
        ByteBuffer.wrap(bytes).putInt(index).putInt(~index);
        return bytes;
    }

    private static long openFileDescriptors() {
        Path proc = Path.of("/proc/self/fd");
        if (Files.isDirectory(proc)) {
            try (Stream<Path> entries = Files.list(proc)) {
                return entries.count();
            } catch (Exception ignored) {
                // Fall through to the supported JVM management interface.
            }
        }
        try {
            Class<?> unix = Class.forName("com.sun.management.UnixOperatingSystemMXBean");
            Object bean = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getOperatingSystemMXBean").invoke(null);
            if (unix.isInstance(bean)) {
                return ((Number) unix.getMethod("getOpenFileDescriptorCount").invoke(bean)).longValue();
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Windows JVMs normally do not provide Unix FD metrics. Byte round-trips still run.
        }
        return -1;
    }
}
