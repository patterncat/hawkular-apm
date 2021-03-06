/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package org.hawkular.apm.api.internal.actions;

import java.util.ArrayList;
import java.util.Map;

import org.hawkular.apm.api.model.Property;
import org.hawkular.apm.api.model.Severity;
import org.hawkular.apm.api.model.config.Direction;
import org.hawkular.apm.api.model.config.btxn.Processor;
import org.hawkular.apm.api.model.config.btxn.ProcessorAction;
import org.hawkular.apm.api.model.config.btxn.SetPropertyAction;
import org.hawkular.apm.api.model.trace.Issue;
import org.hawkular.apm.api.model.trace.Node;
import org.hawkular.apm.api.model.trace.ProcessorIssue;
import org.hawkular.apm.api.model.trace.Trace;

/**
 * This handler is associated with the SetProperty action.
 *
 * @author gbrown
 */
public class SetPropertyActionHandler extends ExpressionBasedActionHandler {

    public static final String NAME_MUST_BE_SPECIFIED = "Name must be specified";

    /**
     * This constructor initialises the action.
     *
     * @param action The action
     */
    public SetPropertyActionHandler(ProcessorAction action) {
        super(action);
    }

    /**
     * This method initialises the process action handler.
     *
     * @param processor The processor
     */
    @Override
    public void init(Processor processor) {
        super.init(processor);

        SetPropertyAction action = (SetPropertyAction) getAction();

        if (action.getName() == null || action.getName().trim().isEmpty()) {
            ProcessorIssue pi = new ProcessorIssue();
            pi.setProcessor(processor.getDescription());
            pi.setAction(getAction().getDescription());
            pi.setField("name");
            pi.setSeverity(Severity.Error);
            pi.setDescription(NAME_MUST_BE_SPECIFIED);

            if (getIssues() == null) {
                setIssues(new ArrayList<Issue>());
            }
            getIssues().add(0, pi);
        }
    }

    @Override
    public boolean process(Trace trace, Node node, Direction direction, Map<String, ?> headers,
            Object[] values) {
        if (super.process(trace, node, direction, headers, values)) {
            String value = getValue(trace, node, direction, headers, values);
            if (value != null && ((SetPropertyAction) getAction()).getName() != null) {
                node.getProperties().add(new Property(((SetPropertyAction) getAction()).getName(), value,
                        ((SetPropertyAction) getAction()).getType()));
                return true;
            }
        }
        return false;
    }

}
