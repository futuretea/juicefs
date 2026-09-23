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

/** Numeric metadata used by the private editing boundary. */
final class EditFileInfo {
  final long inode, nlink, uid, gid, size;
  final int mode;
  final String owner, group;

  EditFileInfo(long inode, long nlink, long uid, long gid, int mode, long size,
               String owner, String group) {
    this.inode = inode;
    this.nlink = nlink;
    this.uid = uid;
    this.gid = gid;
    this.mode = mode;
    this.size = size;
    this.owner = owner;
    this.group = group;
  }

  /** Mirrors C fileInfo with platform alignment, including padding before length. */
  static final class Native extends jnr.ffi.Struct {
    final Unsigned64 inode = new Unsigned64();
    final Unsigned32 mode = new Unsigned32();
    final Unsigned32 uid = new Unsigned32();
    final Unsigned32 gid = new Unsigned32();
    final Unsigned32 atime = new Unsigned32();
    final Unsigned32 mtime = new Unsigned32();
    final Unsigned32 ctime = new Unsigned32();
    final Unsigned32 nlink = new Unsigned32();
    final Unsigned64 length = new Unsigned64();

    Native(jnr.ffi.Runtime runtime) { super(runtime); }
  }
}
