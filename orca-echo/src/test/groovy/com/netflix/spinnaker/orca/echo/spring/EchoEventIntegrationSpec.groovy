/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.echo.spring

import groovy.transform.CompileStatic
import com.netflix.spinnaker.orca.DefaultTaskResult
import com.netflix.spinnaker.orca.batch.SpringBatchExecutionRunner
import com.netflix.spinnaker.orca.batch.StageBuilderProvider
import com.netflix.spinnaker.orca.batch.TaskTaskletAdapterImpl
import com.netflix.spinnaker.orca.batch.exceptions.ExceptionHandler
import com.netflix.spinnaker.orca.batch.listeners.SpringBatchExecutionListenerProvider
import com.netflix.spinnaker.orca.echo.EchoService
import com.netflix.spinnaker.orca.pipeline.ExecutionRunner
import com.netflix.spinnaker.orca.pipeline.ExecutionRunnerSpec.TestTask
import com.netflix.spinnaker.orca.pipeline.RestrictExecutionDuringTimeWindow
import com.netflix.spinnaker.orca.pipeline.StageDefinitionBuilder
import com.netflix.spinnaker.orca.pipeline.TaskNode.TaskDefinition
import com.netflix.spinnaker.orca.pipeline.TaskNode.TaskGraph
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.parallel.WaitForRequisiteCompletionStage
import com.netflix.spinnaker.orca.pipeline.parallel.WaitForRequisiteCompletionTask
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.tasks.NoOpTask
import com.netflix.spinnaker.orca.pipeline.util.StageNavigator
import com.netflix.spinnaker.orca.test.batch.BatchTestConfiguration
import org.spockframework.spring.xml.SpockMockFactoryBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.support.GenericApplicationContext
import org.springframework.retry.backoff.Sleeper
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import retrofit.client.Response
import spock.lang.Specification
import spock.lang.Unroll
import spock.mock.DetachedMockFactory
import static com.netflix.spinnaker.orca.ExecutionStatus.*
import static com.netflix.spinnaker.orca.pipeline.TaskNode.GraphType.FULL
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD

@ContextConfiguration(classes = [
  StageNavigator, WaitForRequisiteCompletionTask, Config,
  WaitForRequisiteCompletionStage, RestrictExecutionDuringTimeWindow
])
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
abstract class EchoEventIntegrationSpec<R extends ExecutionRunner> extends Specification {

  abstract R create(StageDefinitionBuilder... stageDefBuilders)

  @Autowired TestTask task
  @Autowired EchoService echoService
  @Autowired ExecutionRepository executionRepository

  @Unroll
  def "raises events correctly for a stage whose status is #taskStatus"() {
    given:
    executionRepository.retrievePipeline(execution.id) >> execution
    executionRepository.updateStatus(execution.id, _) >> { id, newStatus ->
      execution.status = newStatus
    }

    and:
    def stageDefBuilder = Stub(StageDefinitionBuilder) {
      getType() >> stageType
      buildTaskGraph(_) >> new TaskGraph(FULL, [new TaskDefinition("test", TestTask)])
    }
    def runner = create(stageDefBuilder)

    and:
    task.execute(_) >> new DefaultTaskResult(taskStatus)

    and:
    def events = []
    echoService.recordEvent(_) >> { Map it ->
      events << it.details.type
      new Response("echo", 200, "OK", [], null)
    }

    when:
    runner.start(execution)

    then:
    events[0] == "orca:pipeline:starting"
    events[1] == "orca:stage:starting"
    events[2] == "orca:task:starting"
    events[3] == "orca:task:$taskState"
    events[4] == "orca:stage:$stageState"
    events[5] == "orca:pipeline:$pipelineState"

    and:
    execution.status == pipelineStatus

    where:
    taskStatus      || taskState  | stageState | pipelineState | pipelineStatus
    SUCCEEDED       || "complete" | "complete" | "complete"    | SUCCEEDED
    FAILED_CONTINUE || "complete" | "failed"   | "complete"    | SUCCEEDED
    TERMINAL        || "failed"   | "failed"   | "failed"      | TERMINAL
    CANCELED        || "failed"   | "failed"   | "failed"      | CANCELED
    STOPPED         || "complete" | "complete" | "complete"    | SUCCEEDED
    SKIPPED         || "complete" | "complete" | "complete"    | SUCCEEDED

    stageType = "foo"
    execution = Pipeline.builder().withId("1").withStage(stageType).build()
  }

  @CompileStatic
  static class Config {
    DetachedMockFactory mockFactory = new DetachedMockFactory()

    @Bean
    ExecutionRepository executionRepository() {
      mockFactory.Mock(ExecutionRepository)
    }

    @Bean
    EchoService echoService() {
      mockFactory.Mock(EchoService)
    }

    @Bean
    EchoNotifyingStageListener echoNotifyingStageListener(EchoService echoService) {
      new EchoNotifyingStageListener(echoService)
    }

    @Bean
    EchoNotifyingExecutionListener echoNotifyingExecutionListener(EchoService echoService) {
      new EchoNotifyingExecutionListener(echoService)
    }

    @Bean
    TestTask testTask() { mockFactory.Stub(TestTask) }
  }
}

@ContextConfiguration(
  classes = [
    BatchTestConfiguration, TaskTaskletAdapterImpl,
    SpringBatchExecutionListenerProvider, Config, NoOpTask
  ]
)
class SpringBatchEchoEventIntegrationSpec extends EchoEventIntegrationSpec<SpringBatchExecutionRunner> {

  @Autowired GenericApplicationContext applicationContext
  @Autowired ExecutionRepository executionRepository

  @Override
  SpringBatchExecutionRunner create(StageDefinitionBuilder... stageDefBuilders) {
    applicationContext.with {
      stageDefBuilders.each {
        beanFactory.registerSingleton(it.type, it)
      }
      autowireCapableBeanFactory.createBean(SpringBatchExecutionRunner)
    }
  }

  @CompileStatic
  static class Config {
    @Bean
    FactoryBean<ExceptionHandler> exceptionHandler() {
      new SpockMockFactoryBean(ExceptionHandler)
    }

    @Bean
    FactoryBean<StageBuilderProvider> builderProvider() {
      new SpockMockFactoryBean(StageBuilderProvider)
    }

    @Bean
    FactoryBean<Sleeper> sleeper() { new SpockMockFactoryBean(Sleeper) }
  }
}
