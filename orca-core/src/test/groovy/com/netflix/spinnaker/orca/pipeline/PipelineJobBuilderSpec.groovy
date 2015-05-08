/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.pipeline

import com.netflix.spinnaker.orca.batch.TaskTaskletAdapter
import com.netflix.spinnaker.orca.batch.exceptions.DefaultExceptionHandler
import com.netflix.spinnaker.orca.jackson.OrcaObjectMapper
import com.netflix.spinnaker.orca.pipeline.model.Pipeline
import com.netflix.spinnaker.orca.pipeline.model.PipelineStage
import com.netflix.spinnaker.orca.pipeline.parallel.PipelineInitializationStage
import com.netflix.spinnaker.orca.pipeline.parallel.PipelineInitializationTask
import com.netflix.spinnaker.orca.pipeline.parallel.WaitForRequisiteCompletionStage
import com.netflix.spinnaker.orca.pipeline.parallel.WaitForRequisiteCompletionTask
import com.netflix.spinnaker.orca.pipeline.persistence.DefaultExecutionRepository
import com.netflix.spinnaker.orca.pipeline.persistence.memory.InMemoryOrchestrationStore
import com.netflix.spinnaker.orca.pipeline.persistence.memory.InMemoryPipelineStore
import com.netflix.spinnaker.orca.test.batch.BatchTestConfiguration
import org.springframework.batch.core.job.builder.FlowJobBuilder
import org.springframework.batch.core.job.builder.JobBuilderHelper
import org.springframework.batch.core.job.builder.JobFlowBuilder
import org.springframework.batch.core.job.builder.SimpleJobBuilder
import org.springframework.batch.core.job.flow.FlowJob
import org.springframework.batch.core.job.flow.support.SimpleFlow
import org.springframework.batch.core.listener.StepExecutionListenerSupport
import org.springframework.batch.core.repository.support.SimpleJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.support.AbstractApplicationContext
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import spock.lang.Shared
import spock.lang.Specification

import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD

@ContextConfiguration(classes = [BatchTestConfiguration])
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
class PipelineJobBuilderSpec extends Specification {
  @Autowired AbstractApplicationContext applicationContext

  def mapper = new OrcaObjectMapper()
  def pipelineStore = new InMemoryPipelineStore(mapper)
  def orchestrationStore = new InMemoryOrchestrationStore(mapper)
  def executionRepository = new DefaultExecutionRepository(orchestrationStore, pipelineStore)

  def pipelineInitializationStage = new PipelineInitializationStage()
  def waitForRequisiteCompletionStage = new WaitForRequisiteCompletionStage()
  def taskTaskletAdapter = new TaskTaskletAdapter(executionRepository, [])

  @Shared
  def jobBuilder

  def setup() {
    applicationContext.beanFactory.with {
      registerSingleton PipelineInitializationStage.MAYO_CONFIG_TYPE, pipelineInitializationStage
      registerSingleton WaitForRequisiteCompletionStage.MAYO_CONFIG_TYPE, waitForRequisiteCompletionStage
      registerSingleton "waitForRequisiteCompletionTask", new WaitForRequisiteCompletionTask()
      registerSingleton "pipelineInitializationTask", new PipelineInitializationTask()
      registerSingleton("stepExecutionListener", new StepExecutionListenerSupport())
      registerSingleton("defaultExceptionHandler", new DefaultExceptionHandler())
      registerSingleton "taskTaskletAdapter", taskTaskletAdapter

      autowireBean waitForRequisiteCompletionStage
      autowireBean pipelineInitializationStage
    }

    waitForRequisiteCompletionStage.applicationContext = applicationContext
    pipelineInitializationStage.applicationContext = applicationContext

    def helper = new SimpleJobBuilderHelper("")
    helper.repository(new SimpleJobRepository())

    jobBuilder = new JobFlowBuilder(new FlowJobBuilder(new SimpleJobBuilder(helper)))
  }

  def "should inject 'Initialization' stage for parallel executions"() {
    given:
    def pipeline = new Pipeline()
    pipeline.id = "PIPELINE"
    pipeline.parallel = true
    pipeline.stages << new PipelineStage(pipeline, WaitForRequisiteCompletionStage.MAYO_CONFIG_TYPE, [refId: "B"])

    and:
    def pipelineBuilder = Spy(PipelineJobBuilder, constructorArgs: []) {
      1 * buildStart(pipeline) >> { jobBuilder }
    }
    pipelineBuilder.applicationContext = applicationContext
    pipelineBuilder.initialize()

    when:
    FlowJob job = pipelineBuilder.build(pipeline) as FlowJob
    SimpleFlow flow = job.flow as SimpleFlow

    def startState = flow.startState
    def nextTransition = flow.transitionMap[flow.startState.name][0].next

    then:
    pipeline.stages*.refId == ["*", "B"]
    pipeline.stages*.initializationStage == [true, false]
    pipeline.stages*.requisiteStageRefIds == [null, ["*"]] as List

    startState.name.startsWith("Initialization.${pipeline.id}.${pipeline.stages[0].id}")
    nextTransition == "Initialization.${pipeline.id}.ChildExecution.${pipeline.stages[1].refId}.${pipeline.stages[1].id}" as String
  }

  class SimpleJobBuilderHelper extends JobBuilderHelper {
    SimpleJobBuilderHelper(String name) {
      super(name)
    }
  }
}