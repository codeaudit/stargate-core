/*
 * Copyright 2014, Tuplejump Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tuplejump.stargate.lucene.query.function;

import org.codehaus.jackson.annotate.JsonProperty;

/**
 * User: satya
 */
public class AggregateHolder {

    AggregateFunction aggregateFunction;

    public AggregateHolder(@JsonProperty("aggregates") AggregateFactory[] aggregates, @JsonProperty("distinct") boolean distinct, @JsonProperty("groupBy") String[] groupBy, @JsonProperty("chunkSize") Integer chunkSize, @JsonProperty("imports") String[] imports, @JsonProperty("noScript") boolean noScript) {
        this.aggregateFunction = new AggregateFunction(aggregates, distinct, groupBy, chunkSize, imports, noScript);
    }

    public AggregateFunction getAggregateFunction() {
        return aggregateFunction;
    }
}
