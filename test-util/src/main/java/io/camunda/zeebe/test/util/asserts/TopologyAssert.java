/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.test.util.asserts;

import io.camunda.zeebe.client.api.response.BrokerInfo;
import io.camunda.zeebe.client.api.response.PartitionBrokerHealth;
import io.camunda.zeebe.client.api.response.PartitionInfo;
import io.camunda.zeebe.client.api.response.Topology;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.assertj.core.api.AbstractObjectAssert;

public final class TopologyAssert extends AbstractObjectAssert<TopologyAssert, Topology> {

  public TopologyAssert(final Topology topology) {
    super(topology, TopologyAssert.class);
  }

  public static TopologyAssert assertThat(final Topology actual) {
    return new TopologyAssert(actual);
  }

  public TopologyAssert isComplete(final int clusterSize, final int partitionCount) {
    isNotNull();

    final List<BrokerInfo> brokers = actual.getBrokers();

    if (brokers.size() != clusterSize) {
      throw failure("Expected broker count to be <%s> but was <%s>", clusterSize, brokers.size());
    }

    final List<BrokerInfo> brokersWithUnexpectedPartitionCount =
        brokers.stream()
            .filter(b -> b.getPartitions().size() != partitionCount)
            .collect(Collectors.toList());

    if (!brokersWithUnexpectedPartitionCount.isEmpty()) {
      throw failure(
          "Expected <%s> partitions at each broker, but found brokers with different partition count <%s>",
          partitionCount, brokersWithUnexpectedPartitionCount);
    }

    final Set<Integer> partitions =
        brokers.stream()
            .flatMap(b -> b.getPartitions().stream())
            .map(PartitionInfo::getPartitionId)
            .collect(Collectors.toSet());
    final Set<Integer> partitionsWithLeader =
        brokers.stream()
            .flatMap(b -> b.getPartitions().stream())
            .filter(PartitionInfo::isLeader)
            .map(PartitionInfo::getPartitionId)
            .collect(Collectors.toUnmodifiableSet());

    partitions.removeAll(partitionsWithLeader);
    if (!partitions.isEmpty()) {
      throw failure(
          "Expected every partition to have a leader, but found the following have none: <%s>",
          partitions);
    }

    return myself;
  }

  public TopologyAssert doesNotContainBroker(final int nodeId) {
    isNotNull();

    final List<Integer> brokers =
        actual.getBrokers().stream().map(BrokerInfo::getNodeId).collect(Collectors.toList());
    if (brokers.contains(nodeId)) {
      throw failure(
          "Expected topology not to contain broker with ID %d, but found the following: [%s]",
          nodeId, brokers);
    }

    return myself;
  }

  public TopologyAssert hasBrokerSatisfying(final Consumer<BrokerInfo> condition) {
    isNotNull();

    final List<BrokerInfo> brokers = actual.getBrokers();
    newListAssertInstance(brokers).anySatisfy(condition);

    return myself;
  }

  public TopologyAssert hasBrokersCount(final int count) {
    isNotNull();

    if (actual.getBrokers().size() != count) {
      throw failure(
          "Expected topology to contain %d brokers, but it contains %s",
          count, actual.getBrokers());
    }

    return myself;
  }

  public TopologyAssert isHealthy() {
    isNotNull();
    final var partitions =
        actual.getBrokers().stream()
            .flatMap(brokerInfo -> brokerInfo.getPartitions().stream())
            .collect(Collectors.toList());
    newListAssertInstance(partitions)
        .as("all partitions are healthy")
        .allMatch(partition -> partition.getHealth() == PartitionBrokerHealth.HEALTHY);
    return myself;
  }
}
