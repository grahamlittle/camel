/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.sjms.consumer;

import java.util.concurrent.ExecutorService;

import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Session;

import org.apache.camel.AsyncProcessor;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.component.sjms.TransactionCommitStrategy;
import org.apache.camel.component.sjms.jms.JmsMessageExchangeHelper;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.camel.util.ObjectHelper.wrapRuntimeCamelException;

/**
 * TODO Add Class documentation for DefaultMessageHandler
 */
public abstract class DefaultMessageHandler implements MessageListener {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ExecutorService executor;

    private Endpoint endpoint;
    private AsyncProcessor processor;
    private Session session;
    private boolean transacted;
    private boolean synchronous = true;
    private Synchronization synchronization;
    private boolean topic;
    private TransactionCommitStrategy commitStrategy;

    public DefaultMessageHandler(Endpoint endpoint, ExecutorService executor) {
        this.endpoint = endpoint;
        this.executor = executor;
    }

    public DefaultMessageHandler(Endpoint endpoint, ExecutorService executor, Synchronization synchronization) {
        super();
        this.synchronization = synchronization;
        this.endpoint = endpoint;
        this.executor = executor;
    }

    public DefaultMessageHandler(Endpoint endpoint, ExecutorService executor, TransactionCommitStrategy commitStrategy) {
        super();
        this.endpoint = endpoint;
        this.executor = executor;
        this.commitStrategy = commitStrategy;
    }

    @Override
    public void onMessage(Message message) {
        handleMessage(message);
    }

    /**
     * @param message
     */
    private void handleMessage(Message message) {
        RuntimeCamelException rce = null;
        try {
            final DefaultExchange exchange = (DefaultExchange)JmsMessageExchangeHelper.createExchange(message, getEndpoint());
            
            log.debug("Processing Exchange.id:{}", exchange.getExchangeId());
            
            if (isTransacted() && synchronization != null) {
                exchange.addOnCompletion(synchronization);
            }
            try {
                if (isTransacted() || isSynchronous()) {
                    log.debug("  Handling synchronous message: {}", exchange.getIn().getBody());
                    doHandleMessage(exchange);
                } else {
                    log.debug("  Handling asynchronous message: {}", exchange.getIn().getBody());
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                doHandleMessage(exchange);
                            } catch (Exception e) {
                                ObjectHelper.wrapRuntimeCamelException(e);
                            }

                        }
                    });
                }
            } catch (Exception e) {
                if (exchange != null) {
                    if (exchange.getException() == null) {
                        exchange.setException(e);
                    } else {
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            rce = wrapRuntimeCamelException(e);
        } finally {
            if (rce != null) {
                throw rce;
            }
        }
    }

    public abstract void doHandleMessage(final Exchange exchange);

    public abstract void close();

    public void setTransacted(boolean transacted) {
        this.transacted = transacted;
    }

    public boolean isTransacted() {
        return transacted;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public AsyncProcessor getProcessor() {
        return processor;
    }

    public void setProcessor(AsyncProcessor processor) {
        this.processor = processor;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Session getSession() {
        return session;
    }

    public void setSynchronous(boolean async) {
        this.synchronous = async;
    }

    public boolean isSynchronous() {
        return synchronous;
    }

    public void setTopic(boolean topic) {
        this.topic = topic;
    }

    public boolean isTopic() {
        return topic;
    }

    public TransactionCommitStrategy getCommitStrategy() {
        return commitStrategy;
    }
}
