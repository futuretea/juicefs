# Copyright 2026 Juicedata, Inc.
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import unittest


class PublicAPITest(unittest.TestCase):
    def test_s1_public_imports_and_edit_error_fields(self):
        from agentfs import (AgentFS, Client, RawSearchProvider,
                             RipgrepSearchProvider, SearchProvider,
                             SearchProviderError)
        from agentfs.edit import EditError

        self.assertTrue(callable(Client))
        self.assertTrue(callable(AgentFS))
        self.assertTrue(issubclass(RawSearchProvider, SearchProvider))
        self.assertTrue(issubclass(RipgrepSearchProvider, SearchProvider))
        self.assertTrue(issubclass(SearchProviderError, RuntimeError))
        error = EditError("unsupported", stage="validate", outcome="unchanged")
        self.assertEqual((error.code, error.stage, error.outcome),
                         ("unsupported", "validate", "unchanged"))


if __name__ == "__main__":
    unittest.main()
