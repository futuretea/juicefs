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
	"reflect"
	"testing"

	"github.com/juicedata/juicefs/pkg/fs"
)

func isolateIdentityGlobals(t *testing.T) {
	t.Helper()
	previousActive, previousCache := activefs, userGroupCache
	activefs = make(map[fsKey][]*wrapper)
	userGroupCache = make(map[string]map[string][]string)
	t.Cleanup(func() { activefs, userGroupCache = previousActive, previousCache })
}

func identityWrapper(user string, groups ...string) *wrapper {
	w := &wrapper{
		FileSystem: &fs.FileSystem{}, user: user, superuser: "hdfs", supergroup: "supergroup",
		groups: append([]string(nil), groups...),
		m: &mapping{
			usernames: map[string]uint32{"alice": 1001, "bob": 1002},
			userIDs:   map[uint32]string{1001: "alice", 1002: "bob"},
			groups:    map[string]uint32{"alice-group": 2001, "bob-group": 2002, "updated": 2003},
			groupIDs:  map[uint32]string{2001: "alice-group", 2002: "bob-group", 2003: "updated"},
		},
	}
	updateCtx(w, groups)
	return w
}

func assertIdentity(t *testing.T, w *wrapper, uid uint32, gids ...uint32) {
	t.Helper()
	if w.ctx.Uid() != uid || !reflect.DeepEqual(w.ctx.Gids(), gids) {
		t.Fatalf("user %s identity = %d/%v, want %d/%v", w.user, w.ctx.Uid(), w.ctx.Gids(), uid, gids)
	}
}

func TestUpdateAllCtxMixedIdentityCreationOrder(t *testing.T) {
	for _, privilegedLast := range []bool{false, true} {
		t.Run(map[bool]string{false: "restricted-last", true: "privileged-last"}[privilegedLast], func(t *testing.T) {
			isolateIdentityGlobals(t)
			restricted := identityWrapper("alice", "alice-group")
			privileged := identityWrapper("hdfs", "supergroup")
			activefs[fsKey{name: "volume"}] = []*wrapper{restricted, privileged}
			if privilegedLast {
				updateAllCtx("volume", "hdfs", "supergroup")
			} else {
				updateAllCtx("volume", "alice", "alice-group")
			}
			assertIdentity(t, restricted, 1001, 2001)
			assertIdentity(t, privileged, 0, 0)
		})
	}
}

func TestUpdateAllCtxSameUserUpdatesWithoutChangingOtherUsersOrVolumes(t *testing.T) {
	isolateIdentityGlobals(t)
	first := identityWrapper("alice", "alice-group")
	second := identityWrapper("alice", "alice-group")
	otherUser := identityWrapper("bob", "bob-group")
	otherVolume := identityWrapper("alice", "alice-group")
	activefs[fsKey{name: "volume"}] = []*wrapper{first, second, otherUser}
	activefs[fsKey{name: "unrelated"}] = []*wrapper{otherVolume}
	updateAllCtx("volume", "alice", "updated,bob-group")
	assertIdentity(t, first, 1001, 2003, 2002)
	assertIdentity(t, second, 1001, 2003, 2002)
	assertIdentity(t, otherUser, 1002, 2002)
	assertIdentity(t, otherVolume, 1001, 2001)
}

func TestUpdateAllCtxUsesOnlyMatchingUsersCachedGroups(t *testing.T) {
	isolateIdentityGlobals(t)
	alice := identityWrapper("alice", "alice-group")
	bob := identityWrapper("bob", "bob-group")
	activefs[fsKey{name: "volume"}] = []*wrapper{alice, bob}
	userGroupCache["volume"] = map[string][]string{
		"alice": {"updated"}, "bob": {"bob-group"},
	}
	updateAllCtx("volume", "alice", "supergroup")
	assertIdentity(t, alice, 1001, 2003)
	assertIdentity(t, bob, 1002, 2002)
	updateAllCtx("volume", "bob", "supergroup")
	assertIdentity(t, alice, 1001, 2003)
	assertIdentity(t, bob, 1002, 2002)
}

func TestUpdateAllCtxReloadReevaluatesEachUsersOwnGroups(t *testing.T) {
	isolateIdentityGlobals(t)
	alice := identityWrapper("alice", "supergroup")
	bob := identityWrapper("bob", "bob-group")
	activefs[fsKey{name: "volume"}] = []*wrapper{alice, bob}
	assertIdentity(t, alice, 0, 0)
	// Reload changes the privileged group; the callback may carry another user's identity.
	alice.FileSystem.Supergroup = "replacement-supergroup"
	bob.FileSystem.Supergroup = "replacement-supergroup"
	updateAllCtx("volume", "bob", "bob-group")
	assertIdentity(t, alice, 1001, 0)
	assertIdentity(t, bob, 1002, 2002)
}
