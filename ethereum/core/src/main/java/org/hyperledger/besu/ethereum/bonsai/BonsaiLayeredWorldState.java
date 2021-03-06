/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.hyperledger.besu.ethereum.bonsai;

import static com.google.common.base.Preconditions.checkArgument;

import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.WorldState;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/** A World State backed first by trie log layer and then by another world state. */
public class BonsaiLayeredWorldState implements BonsaiWorldState, WorldState {

  private final BonsaiWorldState parent;
  private final TrieLogLayer trieLog;
  private final Map<Address, Account> cachedAccounts = new HashMap<>();

  public BonsaiLayeredWorldState(final BonsaiWorldState parent, final TrieLogLayer trieLog) {
    checkArgument(trieLog.isFrozen(), "TrieLogs must be frozen to be used as world state.");
    this.parent = parent;
    this.trieLog = trieLog;
  }

  @Override
  public Bytes getCode(final Address address) {
    return trieLog.getCode(address).orElseGet(() -> parent.getCode(address));
  }

  @Override
  public UInt256 getStorageValue(final Address address, final UInt256 key) {
    return getStorageValueBySlotHash(address, Hash.hash(key.toBytes())).orElse(UInt256.ZERO);
  }

  @Override
  public Optional<UInt256> getStorageValueBySlotHash(final Address address, final Hash slotHash) {
    return trieLog
        .getStorageBySlotHash(address, slotHash)
        .or(() -> parent.getStorageValueBySlotHash(address, slotHash));
  }

  @Override
  public UInt256 getOriginalStorageValue(final Address address, final UInt256 key) {
    // This is the base layer for a block, all values are original.
    return getStorageValue(address, key);
  }

  @Override
  public Map<Bytes32, Bytes> getAllAccountStorage(final Address address, final Hash rootHash) {
    final Map<Bytes32, Bytes> results = parent.getAllAccountStorage(address, rootHash);
    trieLog
        .streamStorageChanges(address)
        .forEach(entry -> results.put(entry.getKey(), entry.getValue().getUpdated().toBytes()));
    return results;
  }

  @Override
  public Account get(final Address address) {
    return cachedAccounts.computeIfAbsent(
        address,
        addr ->
            trieLog
                .getAccount(addr)
                .map(
                    stateTrieAccountValue ->
                        (Account)
                            new BonsaiAccount(
                                BonsaiLayeredWorldState.this, addr, stateTrieAccountValue, false))
                .orElseGet(() -> parent.get(address)));
  }

  @Override
  public Hash rootHash() {
    return trieLog.getBlockHash();
  }

  @Override
  public Stream<StreamableAccount> streamAccounts(final Bytes32 startKeyHash, final int limit) {
    throw new UnsupportedOperationException("Bonsai does not support pruning and debug RPCs");
  }
}
