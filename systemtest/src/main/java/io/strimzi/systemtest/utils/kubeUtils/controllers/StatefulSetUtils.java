/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kubeUtils.controllers;

import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.strimzi.systemtest.Constants;
import io.strimzi.systemtest.utils.kubeUtils.objects.PodUtils;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.TreeMap;

import static io.strimzi.test.k8s.KubeClusterResource.cmdKubeClient;
import static io.strimzi.test.k8s.KubeClusterResource.kubeClient;

public class StatefulSetUtils {

    private static final Logger LOGGER = LogManager.getLogger(StatefulSetUtils.class);

    private StatefulSetUtils() { }

    /**
     * Returns a map of pod name to resource version for the pods currently in the given statefulset.
     * @param name  The StatefulSet name
     * @return A map of pod name to resource version for pods in the given StatefulSet.
     */
    public static Map<String, String> ssSnapshot(String name) {
        StatefulSet statefulSet = kubeClient().getStatefulSet(name);
        LabelSelector selector = statefulSet.getSpec().getSelector();
        return PodUtils.podSnapshot(selector);
    }

    /**
     * Method to check that all pods for expected StatefulSet were rolled
     * @param name StatefulSet name
     * @param snapshot Snapshot of pods for StatefulSet before the rolling update
     * @return true when the pods for StatefulSet are recreated
     */
    public static boolean ssHasRolled(String name, Map<String, String> snapshot) {
        boolean log = true;
        if (log) {
            LOGGER.debug("Existing snapshot: {}", new TreeMap<>(snapshot));
        }
        LabelSelector selector = null;
        int times = 60;
        do {
            selector = kubeClient().getStatefulSetSelectors(name);
            if (selector == null) {
                if (times-- == 0) {
                    throw new RuntimeException("Retry failed");
                }
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } while (selector == null);

        Map<String, String> map = PodUtils.podSnapshot(selector);
        if (log) {
            LOGGER.debug("Current snapshot: {}", new TreeMap<>(map));
        }
        // rolled when all the pods in snapshot have a different version in map
        map.keySet().retainAll(snapshot.keySet());
        if (log) {
            LOGGER.debug("Pods in common: {}", new TreeMap<>(map));
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            String currentResourceVersion = e.getValue();
            String resourceName = e.getKey();
            String oldResourceVersion = snapshot.get(resourceName);
            if (oldResourceVersion.equals(currentResourceVersion)) {
                if (log) {
                    LOGGER.debug("At least {} hasn't rolled", resourceName);
                }
                return false;
            }
        }
        if (log) {
            LOGGER.debug("All pods seem to have rolled");
        }
        return true;
    }

    /**
     *  Method to wait when StatefulSet will be recreated after rolling update
     * @param name StatefulSet name
     * @param snapshot Snapshot of pods for StatefulSet before the rolling update
     * @return The snapshot of the StatefulSet after rolling update with Uid for every pod
     */
    public static Map<String, String> waitTillSsHasRolled(String name, Map<String, String> snapshot) {
        LOGGER.info("Waiting for StatefulSet {} rolling update", name);
        TestUtils.waitFor("StatefulSet " + name + " rolling update",
            Constants.WAIT_FOR_ROLLING_UPDATE_INTERVAL, Constants.WAIT_FOR_ROLLING_UPDATE_TIMEOUT * 2, () -> {
                try {
                    return ssHasRolled(name, snapshot);
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            });
        LOGGER.info("StatefulSet {} rolling update finished", name);
        return ssSnapshot(name);
    }

    /**
     *  Method to wait when StatefulSet will be recreated after rolling update with wait for all pods ready
     * @param name StatefulSet name
     * @param expectedPods Expected number of pods
     * @param snapshot Snapshot of pods for StatefulSet before the rolling update
     * @return The snapshot of the StatefulSet after rolling update with Uid for every pod
     */
    public static Map<String, String> waitTillSsHasRolled(String name, int expectedPods, Map<String, String> snapshot) {
        waitTillSsHasRolled(name, snapshot);
        waitForAllStatefulSetPodsReady(name, expectedPods);
        LOGGER.info("StatefulSet {} rolling update finished", name);
        return ssSnapshot(name);
    }

    /**
     *
     * Wait until the STS is ready and all of its Pods are also ready.
     * @param name The name of the StatefulSet
     * @param expectPods The number of pods expected.
     */
    public static void waitForAllStatefulSetPodsReady(String name, int expectPods) {
        LOGGER.debug("Waiting for StatefulSet {} to be ready", name);
        TestUtils.waitFor("statefulset " + name + " to be ready", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> kubeClient().getStatefulSetStatus(name));
        LOGGER.debug("StatefulSet {} is ready", name);
        LOGGER.debug("Waiting for Pods of StatefulSet {} to be ready", name);
        PodUtils.waitForPodsReady(kubeClient().getStatefulSetSelectors(name), expectPods, true);
    }

    /**
     * Wait until the given StatefulSet has been deleted.
     * @param name The name of the StatefulSet.
     */
    public static void waitForStatefulSetDeletion(String name) {
        LOGGER.debug("Waiting for StatefulSet {} deletion", name);
        TestUtils.waitFor("StatefulSet " + name + " to be deleted", Constants.POLL_INTERVAL_FOR_RESOURCE_DELETION, Constants.TIMEOUT_FOR_RESOURCE_DELETION,
            () -> {
                if (kubeClient().getStatefulSet(name) == null) {
                    return true;
                } else {
                    LOGGER.warn("StatefulSet {} is not deleted yet! Triggering force delete by cmd client!", name);
                    cmdKubeClient().deleteByName("statefulset", name);
                    return false;
                }
            });
        LOGGER.debug("StatefulSet {} was deleted", name);
    }

    /**
     * Wait until the given StatefulSet has been recovered.
     * @param name The name of the StatefulSet.
     */
    public static void waitForStatefulSetRecovery(String name, String statefulSetUid) {
        LOGGER.info("Waiting for StatefulSet {}-{} recovery in namespace {}", name, statefulSetUid, kubeClient().getNamespace());
        TestUtils.waitFor("StatefulSet " + name + " to be recovered", Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS, Constants.TIMEOUT_FOR_RESOURCE_READINESS,
            () -> !kubeClient().getStatefulSetUid(name).equals(statefulSetUid));
        LOGGER.info("StatefulSet {} was recovered", name);
    }

    public static void waitForKafkaStatefulSetLabelsChange(String statefulSetName, Map<String, String> labels) {
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            boolean isK8sTag = entry.getKey().equals("controller-revision-hash") || entry.getKey().equals("statefulset.kubernetes.io/pod-name");
            boolean isStrimziTag = entry.getKey().startsWith("strimzi.io/");
            // ignoring strimzi.io and k8s labels
            if (!(isStrimziTag || isK8sTag)) {
                LOGGER.info("Waiting for Kafka stateful set label change {} -> {}", entry.getKey(), entry.getValue());
                TestUtils.waitFor("Waits for Kafka stateful set label change " + entry.getKey() + " -> " + entry.getValue(), Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS,
                    Constants.TIMEOUT_FOR_RESOURCE_READINESS, () ->
                        kubeClient().getStatefulSet(statefulSetName).getMetadata().getLabels().get(entry.getKey()).equals(entry.getValue())
                );
            }
        }
    }

    public static void waitForKafkaStatefulSetLabelsDeletion(String statefulSet, String... labelKeys) {
        for (final String labelKey : labelKeys) {
            LOGGER.info("Waiting for Kafka statefulSet label {} change to {}", labelKey, null);
            TestUtils.waitFor("Waiting for Kafka statefulSet label" + labelKey + " change to " + null, Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS,
                Constants.TIMEOUT_FOR_RESOURCE_READINESS, () ->
                    kubeClient().getStatefulSet(statefulSet).getMetadata().getLabels().get(labelKey) == null
            );
            LOGGER.info("Kafka statefulSet label {} change to {}", labelKey, null);
        }
    }
}
