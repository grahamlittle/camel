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
package org.apache.camel.component.sjms.it;

import java.util.concurrent.TimeUnit;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.component.sjms.support.JmsTestSupport;
import org.apache.camel.util.StopWatch;

import org.junit.Test;

/**
 * @version 
 */
public class AsyncJmsInOutIT extends JmsTestSupport {

    @Test
    public void testAsynchronous() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedMessageCount(100);
        mock.expectsNoDuplicates(body());

        StopWatch watch = new StopWatch();

        for (int i = 0; i < 100; i++) {
            template.sendBody("seda:start", "" + i);
        }

        // just in case we run on slow boxes
        assertMockEndpointsSatisfied(20, TimeUnit.SECONDS);

        log.info("Took " + watch.stop() + " ms. to process 100 messages request/reply over JMS");
    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                // in a fully sync mode it would take at least 5 + 5 sec to process the 100 messages
                // (there are delays in both routes)
                // however due async routing, we can leverage the fact to let threads non blocked
                // in the first route, and therefore can have the messages processed faster
                // because we can have messages wait concurrently in both routes
                // this means the async processing model is about 2x faster

                from("seda:start")
                    // we can only send at fastest the 100 msg in 5 sec due the delay
                    .delay(50)
                    .to("sjms:queue:bar?synchronous=false&exchangePattern=InOut")
                    .to("mock:result");

                from("sjms:queue:bar?synchronous=false&exchangePattern=InOut")
                    .log("Using ${threadName} to process ${body}")
                    // we can only process at fastest the 100 msg in 5 sec due the delay
                    .delay(50)
                    .transform(body().prepend("Bye "));
            }
        };
    }
}
