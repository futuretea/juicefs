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

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

/** U1: public builder validation must fail before loading any native library. */
public class ClientLifecycleTest {
    private static final String META = "redis://127.0.0.1:1/15";
    private static final List<String> GROUPS = List.of("agentfs-test");

    @Test
    public void missingIdentityIsRejectedBeforeNativeInitialization() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentFS.builder("unit-volume", META).open());
    }

    @Test
    public void invalidInitializationStringsAreRejectedBeforeNativeInitialization() {
        for (String invalid : Arrays.asList(null, "", "a\0b", "\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder(invalid, META)
                    .identity("agentfs-test", GROUPS).open());
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", invalid)
                    .identity("agentfs-test", GROUPS).open());
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                    .identity(invalid, GROUPS).open());
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                    .identity("agentfs-test", Arrays.asList(invalid)).open());
        }
        assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                .identity("agentfs-test", null).open());
        assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                .identity("agentfs-test", List.of("one,two")).open());
    }

    @Test
    public void invalidSuperuserSettingsAreRejectedBeforeNativeInitialization() {
        for (String invalid : Arrays.asList(null, "", "a\0b", "\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                    .identity("agentfs-test", GROUPS).superuser(invalid, "supergroup").open());
            assertThrows(IllegalArgumentException.class, () -> AgentFS.builder("unit-volume", META)
                    .identity("agentfs-test", GROUPS).superuser("hdfs", invalid).open());
        }
    }
}
