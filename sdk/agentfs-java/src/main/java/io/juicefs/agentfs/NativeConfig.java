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

import org.json.JSONObject;

final class NativeConfig {
    private NativeConfig() {}

    // Match the old Java SDK's effective defaults, without its Hadoop configuration layer.
    static String json(String metadataUrl) {
        return new JSONObject()
                .put("meta", metadataUrl).put("caller", 0).put("superFs", false)
                .put("cacheDir", "memory").put("cacheSize", "100")
                .put("openCache", "0.0").put("backupMeta", "3600").put("heartbeat", "12")
                .put("attrTimeout", "0.0").put("entryTimeout", "0.0")
                .put("dirEntryTimeout", "0.0").put("cacheFullBlock", true)
                .put("cacheChecksum", "extend").put("cacheEviction", "2-random")
                .put("cacheScanInterval", "300").put("cacheExpire", "0")
                .put("autoCreate", true).put("maxUploads", 20).put("maxDownloads", 200)
                .put("maxDeletes", 10).put("skipDirNlink", 20).put("skipDirMtime", "100ms")
                .put("uploadLimit", "0").put("downloadLimit", "0").put("ioRetries", 10)
                .put("getTimeout", "5").put("putTimeout", "60").put("memorySize", "300")
                .put("prefetch", 1).put("readahead", "0").put("pushInterval", "10")
                .put("fastResolve", true).put("freeSpace", "0.1")
                // Local standalone acceptance does not send background usage reports.
                .put("noUsageReport", true).toString();
    }
}
