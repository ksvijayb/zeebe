/*
 * Copyright 2015-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.log.entry;

import io.atomix.raft.storage.log.RaftLog;

/** Stores a state change in a {@link RaftLog}. */
public record RaftLogEntry(long term, RaftEntry entry) {
  public boolean isApplicationEntry() {
    return entry instanceof ApplicationEntry;
  }

  public ApplicationEntry getApplicationEntry() {
    return (ApplicationEntry) entry;
  }

  public boolean isConfigurationEntry() {
    return entry instanceof ConfigurationEntry;
  }

  public ConfigurationEntry getConfigurationEntry() {
    return (ConfigurationEntry) entry;
  }

  public boolean isInitialEntry() {
    return entry instanceof InitialEntry;
  }

  public InitialEntry getInitialEntry() {
    return (InitialEntry) entry;
  }
}
