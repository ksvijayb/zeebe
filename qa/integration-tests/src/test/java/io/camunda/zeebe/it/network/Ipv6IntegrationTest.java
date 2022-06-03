/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.network;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network.Ipam;
import com.github.dockerjava.api.model.Network.Ipam.Config;
import io.camunda.zeebe.client.api.response.Topology;
import io.camunda.zeebe.test.util.asserts.TopologyAssert;
import io.camunda.zeebe.test.util.testcontainers.ZeebeTestContainerDefaults;
import io.zeebe.containers.ZeebeBrokerNode;
import io.zeebe.containers.ZeebeGatewayNode;
import io.zeebe.containers.ZeebePort;
import io.zeebe.containers.cluster.ZeebeCluster;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agrona.CloseHelper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Network;

@EnabledOnOs(
    value = OS.LINUX,
    disabledReason =
        "The Docker documentation says that IPv6 networking is only supported on Docker daemons running on Linux hosts. See: https://docs.docker.com/config/daemon/ipv6/")
final class Ipv6IntegrationTest {

  private static final String BASE_PART_OF_SUBNET = "2081::aede:4844:fe00:";
  private static final String SUBNET = BASE_PART_OF_SUBNET + "0/123";
  private static final String INADDR6_ANY = "[::]";
  private final Network network =
      Network.builder()
          .createNetworkCmdModifier(
              createNetworkCmd ->
                  createNetworkCmd
                      .withIpam(new Ipam().withConfig(new Config().withSubnet(SUBNET)))
                      .withName(UUID.randomUUID().toString()))
          .enableIpv6(true)
          .build();
  private final List<String> initialContactPoints = new ArrayList<>();

  // offset the index by 2 as indexes start at 0, and :2 is the gateway, so the first address
  // should start at :3
  private final AtomicInteger brokerIpAllocator = new AtomicInteger(3);

  private final ZeebeCluster cluster =
      ZeebeCluster.builder()
          .withPartitionsCount(1)
          .withReplicationFactor(1)
          .withNetwork(network)
          .withImage(ZeebeTestContainerDefaults.defaultTestImage())
          .withBrokerConfig(this::configureBroker)
          .withBrokersCount(1)
          .withEmbeddedGateway(false)
          .withGatewaysCount(1)
          .withGatewayConfig(this::configureGateway)
          .build();

  @SuppressWarnings({"resource", "Convert2MethodRef", "ResultOfMethodCallIgnored"})
  @BeforeEach
  void beforeEach() {
    final var networkInfo =
        DockerClientFactory.lazyClient().inspectNetworkCmd().withNetworkId(network.getId()).exec();

    Assertions.assertThat(networkInfo)
        .as("IPv6 network was properly created")
        .isNotNull()
        .extracting(n -> n.getEnableIPv6());
  }

  @AfterEach
  void tearDown() {
    CloseHelper.closeAll(cluster, network);
  }

  @Test
  void shouldCommunicateOverIpv6() {
    // given
    cluster.start();

    // when
    try (final var client = cluster.newClientBuilder().build()) {
      final Topology topology = client.newTopologyRequest().send().join(5, TimeUnit.SECONDS);
      // then - can find each other
      TopologyAssert.assertThat(topology).isComplete(1, 1);
    }
  }

  private void configureBroker(final ZeebeBrokerNode<?> broker) {
    final var hostNameWithoutBraces = getIPv6Address(brokerIpAllocator.getAndIncrement());
    final var hostName = String.format("[%s]", hostNameWithoutBraces);

    initialContactPoints.add(hostName + ":" + ZeebePort.INTERNAL.getPort());

    broker
        .withEnv("ZEEBE_LOG_LEVEL", "DEBUG")
        .withEnv("ATOMIX_LOG_LEVEL", "INFO")
        .withEnv(
            "ZEEBE_BROKER_CLUSTER_INITIALCONTACTPOINTS", String.join(",", initialContactPoints))
        .withEnv("ZEEBE_BROKER_NETWORK_ADVERTISEDHOST", hostName)
        .withEnv("ZEEBE_BROKER_NETWORK_HOST", INADDR6_ANY)
        .withCreateContainerCmdModifier(cmd -> configureHostForIPv6(cmd, hostNameWithoutBraces));
  }

  private void configureHostForIPv6(
      final CreateContainerCmd cmd, final String hostNameWithoutBraces) {
    final var hostConfig = Optional.ofNullable(cmd.getHostConfig()).orElse(new HostConfig());
    cmd.withHostConfig(hostConfig.withNetworkMode(network.getId()));
    cmd.withIpv6Address(hostNameWithoutBraces).withHostName(hostNameWithoutBraces);
  }

  private void configureGateway(final ZeebeGatewayNode<?> gateway) {
    final String contactPoint = getIPv6Address(3);
    final String hostNameWithoutBraces = getIPv6Address(2);
    final var hostName = String.format("[%s]", hostNameWithoutBraces);

    gateway
        .withEnv("ZEEBE_LOG_LEVEL", "DEBUG")
        .withEnv("ATOMIX_LOG_LEVEL", "INFO")
        .withEnv(
            "ZEEBE_GATEWAY_CLUSTER_CONTACTPOINT",
            String.format("[%s]:%d", contactPoint, ZeebePort.INTERNAL.getPort()))
        .withEnv("ZEEBE_GATEWAY_NETWORK_HOST", INADDR6_ANY)
        .withEnv("ZEEBE_GATEWAY_NETWORK_ADVERTISEDHOST", hostName)
        .withEnv("ZEEBE_GATEWAY_CLUSTER_HOST", hostName)
        .withCreateContainerCmdModifier(cmd -> configureHostForIPv6(cmd, hostNameWithoutBraces));
  }

  private String getIPv6Address(final int ip) {
    return String.format("%s%d", BASE_PART_OF_SUBNET, ip);
  }
}
