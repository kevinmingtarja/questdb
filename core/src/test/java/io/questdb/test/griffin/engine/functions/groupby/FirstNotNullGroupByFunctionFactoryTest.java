/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.test.griffin.engine.functions.groupby;

import io.questdb.cairo.TableWriter;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.test.AbstractCairoTest;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public class FirstNotNullGroupByFunctionFactoryTest extends AbstractCairoTest {

    @Test
    public void testAllNull() throws Exception {

        assertQuery("a0\ta1\ta2\ta3\ta4\ta5\ta6\ta7\ta8\ta9\n" +
                        "\t\tNaN\tNaN\tNaN\tNaN\t\t\t\t\n",
                "select first_not_null(a0)a0,first_not_null(a1)a1,first_not_null(a2)a2,first_not_null(a3)a3,first_not_null(a4)a4," +
                        "first_not_null(a5)a5,first_not_null(a6)a6,first_not_null(a7)a7,first_not_null(a8)a8,first_not_null(a9)a9 from tab",
                "create table tab as (select cast(null as char)a0,cast(null as date)a1,cast(null as double)a2,cast(null as float)a3,cast(null as int)a4,\n" +
                        "cast(null as long)a5,cast(null as symbol)a6,cast(null as timestamp)a7,cast(null as uuid)a8,cast(null as string)a9\n" +
                        "from long_sequence(3))",
                null,
                false,
                true
        );
    }

    @Test
    public void testFirstNotNull() throws Exception {

        UUID firstUuid = UUID.randomUUID();

        ddl("create table tab (a0 char,a1 date,a2 double,a3 float,a4 int,a5 long,a6 symbol,a7 timestamp,a8 uuid,a9 string)");
        insert("insert into tab values(null,null,null,null,null,null,null,null,null,null)");
        insert("insert into tab values('a',to_date('2023-10-23','yyyy-MM-dd'),2.2,3.3,4,5,'a_symbol',to_timestamp('2023-10-23T12:34:59.000000','yyyy-MM-ddTHH:mm:ss.SSSUUU'),'" + firstUuid + "','a_string')");
        insert("insert into tab values('b',to_date('2023-10-22','yyyy-MM-dd'),22.2,33.3,44,55,'b_symbol',to_timestamp('2023-10-22T01:02:03.000000','yyyy-MM-ddTHH:mm:ss.SSSUUU'),rnd_uuid4(),'b_string')");
        assertSql("a0\ta1\ta2\ta3\ta4\ta5\ta6\ta7\ta8\ta9\n" +
                        "a\t2023-10-23T00:00:00.000Z\t2.2\t3.3000\t4\t5\ta_symbol\t2023-10-23T12:34:59.000000Z\t" + firstUuid + "\ta_string\n",
                "select first_not_null(a0)a0,first_not_null(a1)a1,first_not_null(a2)a2,first_not_null(a3)a3,first_not_null(a4)a4," +
                        "first_not_null(a5)a5,first_not_null(a6)a6,first_not_null(a7)a7,first_not_null(a8)a8,first_not_null(a9)a9 from tab");
    }
}
