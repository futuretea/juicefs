/*
 * JuiceFS, Copyright 2026 Juicedata, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.juicefs.agentfs;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.juicefs.agentfs.NativeEditPermissionIT.*;

/** Failures occur after real native effects, not instead of those effects. */
public class NativeEditFaultIT {
    @Test
    public void s12s13NativePrecommitFailuresAndLostCommitResponse() throws Exception {
        String root = required("AGENTFS_EDIT_ROOT") + "/faults";
        try (AgentFS plain = client("hdfs", "supergroup");
             NativeClient nativeOwner = nativeClient("hdfs", "supergroup")) {
            for (String operation : List.of("edit", "patch")) {
                for (String fault : List.of("write", "close", "preserve", "commit")) {
                    String path = root + "/" + operation + "-" + fault;
                    plain.writeFile(path, "cat\n".getBytes(StandardCharsets.UTF_8));
                    EditFileInfo before = nativeOwner.editStorage().stat(path);
                    NativeFaultStorage wrapped = new NativeFaultStorage(nativeOwner.editStorage(), fault);
                    AgentFileEdit editor = new AgentFileEdit(wrapped);
                    EditException error = assertThrows(EditException.class, () -> {
                        if (operation.equals("edit")) editor.editFile(path, "cat", "dog");
                        else editor.applyPatch(path, patch(path, "cat", "dog"));
                    });
                    boolean committed = fault.equals("commit");
                    assertEquals(fault, committed ? "commit_unknown" : "storage_error", error.code);
                    assertEquals(fault, committed ? "unknown" : "unchanged", error.outcome);
                    assertEquals(fault, error.stage);
                    assertEquals(committed ? 1 : 0, wrapped.commits);
                    assertEquals(committed ? 0 : 1, wrapped.deletes);
                    EditFileInfo after = nativeOwner.editStorage().stat(path);
                    assertAttributes(before, after);
                    if (committed) assertTrue(before.inode != after.inode);
                    else assertEquals(before.inode, after.inode);
                    assertContent(plain, path, committed ? "dog\n" : "cat\n");
                }
            }
        }
    }

    private static final class NativeFaultStorage implements AgentFileEdit.Storage {
        private final AgentFileEdit.Storage delegate;
        private final String fault;
        int commits;
        int deletes;

        NativeFaultStorage(AgentFileEdit.Storage delegate, String fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        public EditFileInfo stat(String path) throws IOException { return delegate.stat(path); }
        public List<String> xattrs(String path) throws IOException { return delegate.xattrs(path); }
        public void access(String path) throws IOException { delegate.access(path); }
        public InputStream open(String path) throws IOException { return delegate.open(path); }
        public OutputStream create(String path) throws IOException {
            return new FilterOutputStream(delegate.create(path)) {
                public void write(byte[] data, int offset, int length) throws IOException {
                    out.write(data, offset, length);
                    trip("write");
                }
                public void close() throws IOException {
                    super.close();
                    trip("close");
                }
            };
        }
        public void preserve(String path, EditFileInfo original) throws IOException {
            delegate.preserve(path, original);
            trip("preserve");
        }
        public void commit(String source, String target) throws IOException {
            commits++;
            delegate.commit(source, target);
            trip("commit");
        }
        public void delete(String path) throws IOException {
            deletes++;
            delegate.delete(path);
        }
        private void trip(String stage) throws IOException {
            if (fault.equals(stage)) throw new IOException("synthetic native " + stage + " response failure");
        }
    }
}
