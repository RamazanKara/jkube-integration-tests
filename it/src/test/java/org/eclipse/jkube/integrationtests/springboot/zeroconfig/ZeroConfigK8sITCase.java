/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.integrationtests.springboot.zeroconfig;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.PrintStreamHandler;
import org.eclipse.jkube.integrationtests.docker.RegistryExtension;
import org.eclipse.jkube.integrationtests.maven.MavenUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.eclipse.jkube.integrationtests.Locks.CLUSTER_RESOURCE_INTENSIVE;
import static org.eclipse.jkube.integrationtests.Tags.KUBERNETES;
import static org.eclipse.jkube.integrationtests.assertions.DeploymentAssertion.assertDeploymentExists;
import static org.eclipse.jkube.integrationtests.assertions.DeploymentAssertion.awaitDeployment;
import static org.eclipse.jkube.integrationtests.assertions.DockerAssertion.assertImageWasRecentlyBuilt;
import static org.eclipse.jkube.integrationtests.assertions.YamlAssertion.yaml;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

@Tag(KUBERNETES)
@ExtendWith(RegistryExtension.class)
@TestMethodOrder(OrderAnnotation.class)
class ZeroConfigK8sITCase extends ZeroConfig {

  private KubernetesClient k;

  @BeforeEach
  void setUp() {
    k = new DefaultKubernetesClient();
  }

  @AfterEach
  void tearDown() {
    k.close();
    k = null;
  }

  @Override
  public KubernetesClient getKubernetesClient() {
    return k;
  }

  @Test
  @Order(1)
  @DisplayName("k8s:build, should create image")
  void k8sBuild() throws Exception {
    // When
    final InvocationResult invocationResult = maven("k8s:build");
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    assertImageWasRecentlyBuilt("integration-tests", "spring-boot-zero-config");
  }

  @Test
  @Order(2)
  @DisplayName("k8s:push, should push image to remote registry")
  void k8sPush() throws Exception {
    // Given
    final Properties properties = new Properties();
    properties.setProperty("docker.push.registry", "localhost:5000");
    // When
    final InvocationResult invocationResult = maven("k8s:push", properties);
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    final Response response = new OkHttpClient.Builder().build().newCall(new Request.Builder()
      .get().url("http://localhost:5000/v2/integration-tests/spring-boot-zero-config/tags/list").build())
      .execute();
    assertThat(response.body().string(),
      containsString("{\"name\":\"integration-tests/spring-boot-zero-config\",\"tags\":[\"latest\"]}"));
  }

  @Test
  @Order(3)
  @DisplayName("k8s:resource, should create manifests")
  void k8sResource() throws Exception {
    // When
    final InvocationResult invocationResult = maven("k8s:resource");
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    final File metaInfDirectory = new File(
        String.format("../%s/target/classes/META-INF", getProject()));
    assertThat(metaInfDirectory.exists(), equalTo(true));
    assertListResource(new File(metaInfDirectory, "jkube/kubernetes.yml"));
    assertThat(new File(metaInfDirectory, "jkube/kubernetes/spring-boot-zero-config-deployment.yml"), yaml(not(anEmptyMap())));
    assertThat(new File(metaInfDirectory, "jkube/kubernetes/spring-boot-zero-config-service.yml"), yaml(not(anEmptyMap())));
  }

  @Test
  @Order(4)
  @DisplayName("k8s:helm, should create Helm charts")
  void k8sHelm() throws Exception {
    // When
    final InvocationResult invocationResult = maven("k8s:helm");
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    assertThat( new File(String.format("../%s/target/%s-0.0.0-SNAPSHOT-helm.tar.gz", getProject(), getApplication()))
      .exists(), equalTo(true));
    final File helmDirectory = new File(
      String.format("../%s/target/jkube/helm/%s/kubernetes", getProject(), getApplication()));
    assertHelm(helmDirectory);
    assertThat(new File(helmDirectory, "templates/spring-boot-zero-config-deployment.yaml"), yaml(not(anEmptyMap())));
  }

  @Test
  @Order(5)
  @ResourceLock(value = CLUSTER_RESOURCE_INTENSIVE, mode = READ_WRITE)
  @DisplayName("k8s:apply, should deploy pod and service")
  @SuppressWarnings("unchecked")
  void k8sApply() throws Exception {
    // When
    final InvocationResult invocationResult = maven("k8s:apply");
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    final Pod pod = assertThatShouldApplyResources();
    awaitDeployment(this, pod.getMetadata().getNamespace())
      .assertReplicas(equalTo(1))
      .assertContainers(hasSize(1))
      .assertContainers(hasItems(allOf(
        hasProperty("image", equalTo("integration-tests/spring-boot-zero-config:latest")),
        hasProperty("name", equalTo("spring-boot")),
        hasProperty("ports", hasSize(3)),
        hasProperty("ports", hasItems(allOf(
          hasProperty("name", equalTo("http")),
          hasProperty("containerPort", equalTo(8080))
        )))
      )));
  }

  @Test
  @Order(6)
  @DisplayName("k8s:log, should retrieve log")
  void k8sLog() throws Exception {
    // Given
    final Properties properties = new Properties();
    properties.setProperty("jkube.log.follow", "false");
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final MavenUtils.InvocationRequestCustomizer irc = invocationRequest -> {
      invocationRequest.setOutputHandler(new PrintStreamHandler(new PrintStream(baos), true));
    };
    // When
    final InvocationResult invocationResult = maven("k8s:log", properties, irc);
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    assertThat(baos.toString(StandardCharsets.UTF_8),
      stringContainsInOrder("Tomcat started on port(s): 8080", "Started ZeroConfigApplication in", "seconds"));
  }

  @Test
  @Order(7)
  @DisplayName("k8s:undeploy, should delete all applied resources")
  void k8sUndeploy() throws Exception {
    // When
    final InvocationResult invocationResult = maven("k8s:undeploy");
    // Then
    assertThat(invocationResult.getExitCode(), Matchers.equalTo(0));
    assertThatShouldDeleteAllAppliedResources(this);
    assertDeploymentExists(this, equalTo(false));
  }
}
