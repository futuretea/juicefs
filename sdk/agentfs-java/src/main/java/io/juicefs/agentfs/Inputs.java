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

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;

final class Inputs {
    private Inputs() {}

    static void text(String value, String field) {
        if (value == null || value.isEmpty() || value.indexOf(0) >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException(field + " must be nonempty UTF-8 without NUL");
        }
    }

    static String path(String value) {
        text(value, "path");
        if (!value.startsWith("/") || value.startsWith("//")) {
            throw new IllegalArgumentException("path must be an absolute volume path, not a URI");
        }
        try {
            String normalized = new URI(null, null, value, null).normalize().getPath();
            if (normalized.length() > 1 && normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid volume path", error);
        }
    }
}
