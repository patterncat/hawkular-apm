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
package org.hawkular.apm.server.kafka;

import java.util.logging.Logger;

import org.hawkular.apm.api.model.trace.Trace;
import org.hawkular.apm.api.services.ServiceResolver;
import org.hawkular.apm.server.processor.tracecompletiontime.TraceCompletionInformation;
import org.hawkular.apm.server.processor.tracecompletiontime.TraceCompletionInformationInitiator;
import org.hawkular.apm.server.processor.tracecompletiontime.TraceCompletionInformationPublisher;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * @author gbrown
 */
public class TraceCompletionInformationInitiatorKafka
        extends AbstractConsumerKafka<Trace, TraceCompletionInformation> {

    private static final Logger log = Logger.getLogger(TraceCompletionInformationInitiatorKafka.class.getName());

    private static final String GROUP_ID = "TraceCompletionInformationInitiator";

    private static final String TOPIC = "Traces";

    public TraceCompletionInformationInitiatorKafka() {
        super(TOPIC, GROUP_ID);

        setPublisher(ServiceResolver.getSingletonService(TraceCompletionInformationPublisher.class));

        if (getPublisher() == null) {
            log.severe("Trace Completion Information Publisher not found - possibly not configured correctly");
        } else {

            setTypeReference(new TypeReference<Trace>() {
            });

            setProcessor(new TraceCompletionInformationInitiator());
        }
    }
}
