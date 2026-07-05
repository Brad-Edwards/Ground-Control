package com.keplerops.groundcontrol.infrastructure.temporal;

import com.keplerops.groundcontrol.infrastructure.temporal.smoke.TemporalSmokeWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TemporalWorkerProperties.class)
@ConditionalOnProperty(prefix = "groundcontrol.temporal.worker", name = "enabled", havingValue = "true")
class TemporalWorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporalWorkflowServiceStubs(TemporalWorkerProperties properties) {
        return WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.target())
                .build());
    }

    @Bean
    WorkflowClient temporalWorkflowClient(WorkflowServiceStubs serviceStubs, TemporalWorkerProperties properties) {
        return WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(properties.namespace())
                        .build());
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory temporalWorkerFactory(WorkflowClient workflowClient, TemporalWorkerProperties properties) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);
        var worker = factory.newWorker(properties.taskQueue());
        worker.registerWorkflowImplementationTypes(TemporalSmokeWorkflowImpl.class);
        factory.start();
        log.info(
                "Temporal worker started namespace={} taskQueue={} target={}",
                properties.namespace(),
                properties.taskQueue(),
                properties.target());
        return factory;
    }
}
