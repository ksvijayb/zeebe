/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.processinstance;

import static io.camunda.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.camunda.zeebe.engine.processing.streamprocessor.CommandProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.processing.variable.VariableBehavior;
import io.camunda.zeebe.engine.state.KeyGenerator;
import io.camunda.zeebe.engine.state.deployment.DeployedProcess;
import io.camunda.zeebe.engine.state.immutable.ProcessState;
import io.camunda.zeebe.msgpack.spec.MsgpackReaderException;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceCreationRecord;
import io.camunda.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.camunda.zeebe.protocol.record.RejectionType;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceCreationIntent;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.util.StringUtil;
import java.util.HashMap;
import java.util.Map;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class CreateProcessInstanceProcessor
    implements CommandProcessor<ProcessInstanceCreationRecord> {

  private static final String ERROR_MESSAGE_NO_IDENTIFIER_SPECIFIED =
      "Expected at least a bpmnProcessId or a key greater than -1, but none given";
  private static final String ERROR_MESSAGE_NOT_FOUND_BY_PROCESS =
      "Expected to find process definition with process ID '%s', but none found";
  private static final String ERROR_MESSAGE_NOT_FOUND_BY_PROCESS_AND_VERSION =
      "Expected to find process definition with process ID '%s' and version '%d', but none found";
  private static final String ERROR_MESSAGE_NOT_FOUND_BY_KEY =
      "Expected to find process definition with key '%d', but none found";
  private static final String ERROR_MESSAGE_NO_NONE_START_EVENT =
      "Expected to create instance of process with none start event, but there is no such event";
  private static final String ERROR_INVALID_VARIABLES_REJECTION_MESSAGE =
      "Expected to set variables from document, but the document is invalid: '%s'";
  private static final String ERROR_INVALID_VARIABLES_LOGGED_MESSAGE =
      "Expected to set variables from document, but the document is invalid";

  private final ProcessInstanceRecord newProcessInstance = new ProcessInstanceRecord();
  private final ProcessState processState;
  private final VariableBehavior variableBehavior;
  private final KeyGenerator keyGenerator;
  private final TypedCommandWriter commandWriter;
  private final StateWriter stateWriter;

  public CreateProcessInstanceProcessor(
      final ProcessState processState,
      final KeyGenerator keyGenerator,
      final Writers writers,
      final VariableBehavior variableBehavior) {
    this.processState = processState;
    this.variableBehavior = variableBehavior;
    this.keyGenerator = keyGenerator;
    commandWriter = writers.command();
    stateWriter = writers.state();
  }

  @Override
  public boolean onCommand(
      final TypedRecord<ProcessInstanceCreationRecord> command,
      final CommandControl<ProcessInstanceCreationRecord> controller) {
    final ProcessInstanceCreationRecord record = command.getValue();
    final DeployedProcess process = getProcess(record, controller);
    if (process == null || !isValidProcess(controller, process)) {
      return true;
    }

    final long processInstanceKey = keyGenerator.nextKey();
    if (!setVariablesFromDocument(
        controller, record, process.getKey(), processInstanceKey, process.getBpmnProcessId())) {
      return true;
    }

    final var processInstance = initProcessInstanceRecord(process, processInstanceKey);
    if (record.startInstructions().isEmpty()) {
      commandWriter.appendFollowUpCommand(
          processInstanceKey, ProcessInstanceIntent.ACTIVATE_ELEMENT, processInstance);
    } else {
      stateWriter.appendFollowUpEvent(
          processInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, processInstance);
      stateWriter.appendFollowUpEvent(
          processInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATED, processInstance);

      final Map<DirectBuffer, Long> activatedFlowScopeIds = new HashMap<>();
      activatedFlowScopeIds.put(processInstance.getElementIdBuffer(), processInstanceKey);
      record
          .startInstructions()
          .forEach(
              instruction -> {
                final DirectBuffer elementId =
                    new UnsafeBuffer(StringUtil.getBytes(instruction.getElementId()));
                final long flowScopeKey =
                    activateFlowScopes(
                        process, processInstanceKey, elementId, activatedFlowScopeIds);
                final long elementKey = keyGenerator.nextKey();
                final ProcessInstanceRecord elementRecord =
                    createProcessInstanceRecord(
                        process, processInstanceKey, elementId, flowScopeKey);
                commandWriter.appendFollowUpCommand(
                    elementKey, ProcessInstanceIntent.ACTIVATE_ELEMENT, elementRecord);
              });
    }

    record
        .setProcessInstanceKey(processInstanceKey)
        .setBpmnProcessId(process.getBpmnProcessId())
        .setVersion(process.getVersion())
        .setProcessDefinitionKey(process.getKey());
    controller.accept(ProcessInstanceCreationIntent.CREATED, record);
    return true;
  }

  private boolean isValidProcess(
      final CommandControl<ProcessInstanceCreationRecord> controller,
      final DeployedProcess process) {
    if (process.getProcess().getNoneStartEvent() == null) {
      controller.reject(RejectionType.INVALID_STATE, ERROR_MESSAGE_NO_NONE_START_EVENT);
      return false;
    }

    return true;
  }

  private boolean setVariablesFromDocument(
      final CommandControl<ProcessInstanceCreationRecord> controller,
      final ProcessInstanceCreationRecord record,
      final long processDefinitionKey,
      final long processInstanceKey,
      final DirectBuffer bpmnProcessId) {
    try {
      variableBehavior.mergeLocalDocument(
          processInstanceKey,
          processDefinitionKey,
          processInstanceKey,
          bpmnProcessId,
          record.getVariablesBuffer());
    } catch (final MsgpackReaderException e) {
      Loggers.PROCESS_PROCESSOR_LOGGER.error(ERROR_INVALID_VARIABLES_LOGGED_MESSAGE, e);
      controller.reject(
          RejectionType.INVALID_ARGUMENT,
          String.format(ERROR_INVALID_VARIABLES_REJECTION_MESSAGE, e.getMessage()));

      return false;
    }

    return true;
  }

  private ProcessInstanceRecord initProcessInstanceRecord(
      final DeployedProcess process, final long processInstanceKey) {
    newProcessInstance.reset();
    newProcessInstance.setBpmnProcessId(process.getBpmnProcessId());
    newProcessInstance.setVersion(process.getVersion());
    newProcessInstance.setProcessDefinitionKey(process.getKey());
    newProcessInstance.setProcessInstanceKey(processInstanceKey);
    newProcessInstance.setBpmnElementType(BpmnElementType.PROCESS);
    newProcessInstance.setElementId(process.getProcess().getId());
    newProcessInstance.setFlowScopeKey(-1);
    return newProcessInstance;
  }

  private DeployedProcess getProcess(
      final ProcessInstanceCreationRecord record, final CommandControl controller) {
    final DeployedProcess process;

    final DirectBuffer bpmnProcessId = record.getBpmnProcessIdBuffer();

    if (bpmnProcessId.capacity() > 0) {
      if (record.getVersion() >= 0) {
        process = getProcess(bpmnProcessId, record.getVersion(), controller);
      } else {
        process = getProcess(bpmnProcessId, controller);
      }
    } else if (record.getProcessDefinitionKey() >= 0) {
      process = getProcess(record.getProcessDefinitionKey(), controller);
    } else {
      controller.reject(RejectionType.INVALID_ARGUMENT, ERROR_MESSAGE_NO_IDENTIFIER_SPECIFIED);
      process = null;
    }

    return process;
  }

  private DeployedProcess getProcess(
      final DirectBuffer bpmnProcessId, final CommandControl controller) {
    final DeployedProcess process = processState.getLatestProcessVersionByProcessId(bpmnProcessId);
    if (process == null) {
      controller.reject(
          RejectionType.NOT_FOUND,
          String.format(ERROR_MESSAGE_NOT_FOUND_BY_PROCESS, bufferAsString(bpmnProcessId)));
    }

    return process;
  }

  private DeployedProcess getProcess(
      final DirectBuffer bpmnProcessId, final int version, final CommandControl controller) {
    final DeployedProcess process =
        processState.getProcessByProcessIdAndVersion(bpmnProcessId, version);
    if (process == null) {
      controller.reject(
          RejectionType.NOT_FOUND,
          String.format(
              ERROR_MESSAGE_NOT_FOUND_BY_PROCESS_AND_VERSION,
              bufferAsString(bpmnProcessId),
              version));
    }

    return process;
  }

  private DeployedProcess getProcess(final long key, final CommandControl controller) {
    final DeployedProcess process = processState.getProcessByKey(key);
    if (process == null) {
      controller.reject(
          RejectionType.NOT_FOUND, String.format(ERROR_MESSAGE_NOT_FOUND_BY_KEY, key));
    }

    return process;
  }

  private long activateFlowScopes(
      final DeployedProcess process,
      final long processInstanceKey,
      final DirectBuffer elementId,
      final Map<DirectBuffer, Long> activatedFlowScopeIds) {
    final ExecutableFlowElement flowScope =
        process.getProcess().getElementById(elementId).getFlowScope();

    if (!activatedFlowScopeIds.containsKey(flowScope.getId())) {
      final long flowScopeKey =
          activateFlowScopes(process, processInstanceKey, flowScope.getId(), activatedFlowScopeIds);
      final ProcessInstanceRecord flowScopeRecord =
          createProcessInstanceRecord(process, processInstanceKey, flowScope.getId(), flowScopeKey);

      final long key = keyGenerator.nextKey();
      activatedFlowScopeIds.put(flowScope.getId(), key);
      stateWriter.appendFollowUpEvent(
          key, ProcessInstanceIntent.ELEMENT_ACTIVATING, flowScopeRecord);
      stateWriter.appendFollowUpEvent(
          key, ProcessInstanceIntent.ELEMENT_ACTIVATED, flowScopeRecord);
      return flowScopeKey;
    }

    return activatedFlowScopeIds.get(flowScope.getId());
  }

  private ProcessInstanceRecord createProcessInstanceRecord(
      final DeployedProcess process,
      final long processInstanceKey,
      final DirectBuffer elementId,
      final long flowScopeKey) {
    final ProcessInstanceRecord record = new ProcessInstanceRecord();
    record.setBpmnProcessId(process.getBpmnProcessId());
    record.setVersion(process.getVersion());
    record.setProcessDefinitionKey(process.getKey());
    record.setProcessInstanceKey(processInstanceKey);
    record.setBpmnElementType(process.getProcess().getElementById(elementId).getElementType());
    record.setElementId(elementId);
    // TODO check with Philipp which of these 2 need to be set and with what values
    record.setFlowScopeKey(flowScopeKey);
    record.setParentElementInstanceKey(flowScopeKey);
    return record;
  }
}
