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

package main

import (
	"bytes"
	"fmt"
	"strings"
	"testing"

	"github.com/juicedata/juicefs/pkg/fs"
	"github.com/juicedata/juicefs/pkg/meta"
	"github.com/juicedata/juicefs/pkg/utils"
)

func TestFillStatIdentityRecordBoundary(t *testing.T) {
	cases := []struct {
		name, user, group string
		want              int32
	}{
		{"short", "alice", "dev", 38},
		{"exact-100", strings.Repeat("u", 50), strings.Repeat("g", 50), 130},
		{"owner-overflow", strings.Repeat("u", 51), strings.Repeat("g", 50), -75},
		{"group-overflow", strings.Repeat("u", 50), strings.Repeat("g", 51), -75},
		{"utf8-exact-100", strings.Repeat("猫", 33), "g", 130},
		{"utf8-overflow", strings.Repeat("猫", 34), "g", -75},
	}
	for _, tc := range cases {
		for _, capacity := range []int{130, 4096} {
			t.Run(fmt.Sprintf("%s/buffer-%d", tc.name, capacity), func(t *testing.T) {
				w := &wrapper{superuser: tc.user, supergroup: tc.group}
				st := fs.AttrToFileInfo(1, &meta.Attr{
					Typ: meta.TypeFile, Mode: 0644, Length: 1234,
					Mtime: 7, Mtimensec: 123000000, Atime: 8, Atimensec: 456000000,
				})
				data := bytes.Repeat([]byte{0xa5}, capacity)
				before := append([]byte(nil), data...)
				buffer := utils.NewNativeBuffer(data)
				var got int32
				func() {
					defer func() {
						if failure := recover(); failure != nil {
							t.Errorf("fill_stat panicked instead of returning a result: %v", failure)
						}
					}()
					got = fill_stat(w, buffer, st)
				}()
				if got != tc.want {
					t.Errorf("record result = %d, want %d", got, tc.want)
				}
				if w.superuser != tc.user || w.supergroup != tc.group ||
					w.uid2name(0) != tc.user || w.gid2name(0) != tc.group {
					t.Error("identity names were modified or truncated")
				}
				if tc.want < 0 {
					if buffer.Offset() != 0 || !bytes.Equal(data, before) {
						t.Error("overflow changed the output buffer or its offset")
					}
					return
				}
				if buffer.Offset() != int(tc.want) || !bytes.Equal(data[tc.want:], before[tc.want:]) {
					t.Error("successful record wrote outside its returned length")
				}
				decoded := utils.NewNativeBuffer(data)
				if decoded.Get32() != 0644 || decoded.Get64() != 1234 ||
					decoded.Get64() != 7123 || decoded.Get64() != 8456 {
					t.Error("record header changed")
				}
				if !bytes.Equal(data[28:tc.want], []byte(tc.user+"\x00"+tc.group+"\x00")) {
					t.Error("record did not preserve complete UTF-8 identity names")
				}
			})
		}
	}
}
