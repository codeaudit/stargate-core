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

package com.tuplejump.stargate.cassandra;

import com.tuplejump.stargate.util.CQLUnitD;
import org.junit.Test;

import java.util.Arrays;

/**
 * User: satya
 */
public class MatchPatternTest extends IndexTestBase {
    String keyspace = "mpks";

    public MatchPatternTest() {
        cassandraCQLUnit = CQLUnitD.getCQLUnit(null);
    }


    @Test
    public void shouldIndexPerRow() throws Exception {
        //hack to always create new Index during testing
        try {
            createKS(keyspace);
            createTableAndIndexForRow();
            countResults("MP", "", false, true);
            String home = state("home", "match", "event_type", "hom", null);
            String browse = state("browse", "wildcard", "event_type", "p*", 1);
            String buy = state("buy", "match", "event_type", "buy", null);
            String matchPattern = pattern(Arrays.asList(home, browse, buy));
            String aggregate = aggregate("user", "steps", "count", true, "$match");
            String fap = patternAggregate(matchPattern, aggregate);
            countResults("MP", "magic = '" + fap + "'", true);

        } finally {
            dropTable(keyspace, "MP");
            dropKS(keyspace);
        }
    }


    private void createTableAndIndexForRow() throws InterruptedException {
        String options = "{\n" +
                "\t\"metaColumn\":true,\n" +
                "\t\"fields\":{\n" +
                "\t\t\"event_type\":{\"type\":\"text\"}\n" +
                "\t}\n" +
                "}\n";
        getSession().execute("USE " + keyspace + ";");
        getSession().execute("CREATE TABLE MP(user text, event_time int, event_type text, browser text, state text,magic text, PRIMARY KEY(user, event_time)) ");
        getSession().execute("CREATE CUSTOM INDEX eventidx ON MP(magic) USING 'com.tuplejump.stargate.RowIndex' WITH options ={'sg_options':'" + options + "'}");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user1',1,'hom','cr', 'CA')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user2',2,'hom','ff', 'LA')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user3',3,'hom','ie', 'NY')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user1',4,'p1','cr', 'TX')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user2',5,'p2','ff', 'TX')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user3',6,'p3','ie', 'CA')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user2',7,'buy','cr', 'NY')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user1',8,'p3','ff', 'CA')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user2',9,'p3','ie', 'TX')");
        getSession().execute("insert into " + keyspace + ".MP(user,event_time,event_type, browser, state) values ('user3',10,'buy','cr', 'TX')");
    }
}


