/*
 * JuiceFS, Copyright 2026 Juicedata, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.juicefs.agentfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import jnr.ffi.LibraryLoader;
import jnr.ffi.annotations.Encoding;
import jnr.ffi.Pointer;

/** Internal native binding; not an application extension point. */
final class NativeLibrary {
    private static Api loaded;

    private NativeLibrary() {}

    @Encoding("UTF-8")
    public interface Api {
        long jfs_init(Pointer credentials, int count, String name, String config,
                      String user, String groups, String superuser, String supergroup);
        int jfs_term(long pid, long handle, int terminate);
        int jfs_open(long pid, long handle, String path, Pointer length, int flags);
        int jfs_create(long pid, long handle, String path, short mode, short umask);
        int jfs_mkdirAll(long pid, long handle, String path, short mode, short umask, boolean existOK);
        int jfs_unlink(long pid, long handle, String path);
        int jfs_pread(long pid, int fd, Pointer buffer, int count, long offset);
        int jfs_write(long pid, int fd, Pointer buffer, int count);
        int jfs_close(long pid, int fd);
        int jfs_stat1(long pid, long handle, String path, Pointer buffer);
        int jfs_listdir(long pid, long handle, String path, long offset, Pointer buffer, int size);
        int jfs_lstat(long pid, long handle, String path, Pointer buffer);
        int jfs_lstat1(long pid, long handle, String path, Pointer buffer);
        int jfs_listXattr(long pid, long handle, String path, Pointer buffer, int size);
        int jfs_access(long pid, long handle, String path, long flags);
        int jfs_is_superuser(long handle, String user, String groups);
        int jfs_setOwner(long pid, long handle, String path, String owner, String group);
        int jfs_chmod(long pid, long handle, String path, int mode);
        int jfs_rename0(long pid, long handle, String source, String target, int flags);
    }

    static synchronized Api load() throws IOException {
        if (loaded != null) {
            return loaded;
        }
        String arch = System.getProperty("os.arch");
        String resource = "/native/linux-" + arch + "/libjfs.so.gz";
        if (!System.getProperty("os.name").equals("Linux")) {
            throw new IOException("No bundled native library for this operating system");
        }
        try (InputStream source = NativeLibrary.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IOException("Missing bundled native library: " + resource);
            }
            Path library = Files.createTempFile("agentfs-libjfs-", ".so");
            library.toFile().deleteOnExit();
            try (InputStream expanded = new GZIPInputStream(source)) {
                Files.copy(expanded, library, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                loaded = LibraryLoader.create(Api.class).failImmediately().load(library.toString());
            } catch (UnsatisfiedLinkError error) {
                throw new IOException("Cannot load bundled JuiceFS native library", error);
            }
        }
        return loaded;
    }
}
