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

package io.questdb.cutlass.http.processors;

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GeoHashes;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.cutlass.http.HttpChunkedResponseSocket;
import io.questdb.cutlass.http.HttpConnectionContext;
import io.questdb.cutlass.http.HttpRequestHeader;
import io.questdb.cutlass.text.Utf8Exception;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.log.LogRecord;
import io.questdb.mp.SCSequence;
import io.questdb.network.PeerDisconnectedException;
import io.questdb.network.PeerIsSlowToReadException;
import io.questdb.std.*;
import io.questdb.std.str.DirectUtf8Sequence;
import io.questdb.std.str.StringSink;
import io.questdb.std.str.Utf8s;

import java.io.Closeable;

import static io.questdb.cutlass.http.HttpConstants.*;

public class JsonQueryProcessorState implements Mutable, Closeable {
    public static final String HIDDEN = "hidden";
    static final int QUERY_METADATA = 2;
    static final int QUERY_METADATA_SUFFIX = 3;
    static final int QUERY_PREFIX = 1;
    static final int QUERY_RECORD = 5;
    static final int QUERY_RECORD_PREFIX = 9;
    static final int QUERY_RECORD_START = 4;
    static final int QUERY_RECORD_SUFFIX = 6;
    static final int QUERY_SEND_RECORDS_LOOP = 8;
    static final int QUERY_SETUP_FIRST_RECORD = 0;
    static final int QUERY_SUFFIX = 7;
    private static final Log LOG = LogFactory.getLog(JsonQueryProcessorState.class);
    private final ObjList<String> columnNames = new ObjList<>();
    private final IntList columnSkewList = new IntList();
    private final IntList columnTypesAndFlags = new IntList();
    private final StringSink columnsQueryParameter = new StringSink();
    private final int doubleScale;
    private final SCSequence eventSubSequence = new SCSequence();
    private final int floatScale;
    private final HttpConnectionContext httpConnectionContext;
    private final CharSequence keepAliveHeader;
    private final NanosecondClock nanosecondClock;
    private final StringSink query = new StringSink();
    private final ObjList<StateResumeAction> resumeActions = new ObjList<>();
    private final long statementTimeout;
    private int columnCount;
    private int columnIndex;
    private long compilerNanos;
    private boolean containsSecret;
    private long count;
    private boolean countRows = false;
    private RecordCursor cursor;
    private boolean cursorHasRows;
    private long executeStartNanos;
    private boolean explain = false;
    private boolean noMeta = false;
    private OperationFuture operationFuture;
    private boolean pausedQuery = false;
    private boolean queryCacheable = false;
    private boolean queryJitCompiled = false;
    private int queryState = QUERY_SETUP_FIRST_RECORD;
    private int queryTimestampIndex;
    private short queryType;
    private boolean quoteLargeNum = false;
    private Record record;
    private long recordCountNanos;
    private RecordCursorFactory recordCursorFactory;
    private Rnd rnd;
    private long skip;
    private long stop;
    private boolean timings = false;

    public JsonQueryProcessorState(
            HttpConnectionContext httpConnectionContext,
            NanosecondClock nanosecondClock,
            int floatScale,
            int doubleScale,
            CharSequence keepAliveHeader) {
        this.httpConnectionContext = httpConnectionContext;
        resumeActions.extendAndSet(QUERY_SETUP_FIRST_RECORD, this::onSetupFirstRecord);
        resumeActions.extendAndSet(QUERY_PREFIX, this::onQueryPrefix);
        resumeActions.extendAndSet(QUERY_METADATA, this::onQueryMetadata);
        resumeActions.extendAndSet(QUERY_METADATA_SUFFIX, this::onQueryMetadataSuffix);
        resumeActions.extendAndSet(QUERY_SEND_RECORDS_LOOP, this::onSendRecordsLoop);
        resumeActions.extendAndSet(QUERY_RECORD_PREFIX, this::onQueryRecordPrefix);
        resumeActions.extendAndSet(QUERY_RECORD, this::onQueryRecord);
        resumeActions.extendAndSet(QUERY_RECORD_SUFFIX, this::onQueryRecordSuffix);
        resumeActions.extendAndSet(QUERY_SUFFIX, this::doQuerySuffix);

        this.nanosecondClock = nanosecondClock;
        this.floatScale = floatScale;
        this.doubleScale = doubleScale;
        this.statementTimeout = httpConnectionContext.getRequestHeader().getStatementTimeout();
        this.keepAliveHeader = keepAliveHeader;
    }

    @Override
    public void clear() {
        columnCount = 0;
        columnSkewList.clear();
        columnTypesAndFlags.clear();
        columnNames.clear();
        queryTimestampIndex = -1;
        cursor = Misc.free(cursor);
        record = null;
        if (recordCursorFactory != null) {
            if (queryCacheable) {
                httpConnectionContext.getSelectCache().put(query, recordCursorFactory);
            } else {
                recordCursorFactory.close();
            }
            recordCursorFactory = null;
        }
        query.clear();
        columnsQueryParameter.clear();
        queryState = QUERY_SETUP_FIRST_RECORD;
        columnIndex = 0;
        countRows = false;
        explain = false;
        noMeta = false;
        timings = false;
        pausedQuery = false;
        quoteLargeNum = false;
        queryJitCompiled = false;
        operationFuture = Misc.free(operationFuture);
        skip = 0;
        count = 0;
        stop = 0;
        containsSecret = false;
    }

    @Override
    public void close() {
        cursor = Misc.free(cursor);
        recordCursorFactory = Misc.free(recordCursorFactory);
        freeAsyncOperation();
    }

    public void configure(
            HttpRequestHeader request,
            DirectUtf8Sequence query,
            long skip,
            long stop
    ) throws Utf8Exception {
        this.query.clear();
        if (!Utf8s.utf8ToUtf16(query.lo(), query.hi(), this.query)) {
            throw Utf8Exception.INSTANCE;
        }
        this.skip = skip;
        this.stop = stop;
        count = 0L;
        noMeta = Utf8s.equalsNcAscii("true", request.getUrlParam(URL_PARAM_NM));
        countRows = Utf8s.equalsNcAscii("true", request.getUrlParam(URL_PARAM_COUNT));
        timings = Utf8s.equalsNcAscii("true", request.getUrlParam(URL_PARAM_TIMINGS));
        explain = Utf8s.equalsNcAscii("true", request.getUrlParam(URL_PARAM_EXPLAIN));
        quoteLargeNum = Utf8s.equalsNcAscii("true", request.getUrlParam(URL_PARAM_QUOTE_LARGE_NUM))
                || Utf8s.equalsNcAscii("con", request.getUrlParam(URL_PARAM_SRC));
    }

    public LogRecord critical() {
        return LOG.critical().$('[').$(getFd()).$("] ");
    }

    public LogRecord error() {
        return LOG.error().$('[').$(getFd()).$("] ");
    }

    public void freeAsyncOperation() {
        operationFuture = Misc.free(operationFuture);
    }

    public SCSequence getEventSubSequence() {
        return eventSubSequence;
    }

    public long getExecutionTimeNanos() {
        return nanosecondClock.getTicks() - this.executeStartNanos;
    }

    public HttpConnectionContext getHttpConnectionContext() {
        return httpConnectionContext;
    }

    public OperationFuture getOperationFuture() {
        return operationFuture;
    }

    public CharSequence getQuery() {
        return query;
    }

    public CharSequence getQueryOrHidden() {
        return containsSecret ? HIDDEN : query;
    }

    public short getQueryType() {
        return queryType;
    }

    public Rnd getRnd() {
        return rnd;
    }

    public long getStatementTimeout() {
        return statementTimeout;
    }

    public LogRecord info() {
        return LOG.info().$('[').$(getFd()).$("] ");
    }

    public boolean isPausedQuery() {
        return pausedQuery;
    }

    public void logBufferTooSmall() {
        info().$("Response buffer is too small, state=").$(queryState).$();
    }

    public void logExecuteCached() {
        info().$("execute-cached [skip: ").$(skip)
                .$(", stop: ").$(stop).I$();
    }

    public void logExecuteNew() {
        info().$("execute-new [skip: ").$(skip)
                .$(", stop: ").$(stop).I$();
    }

    public void logSqlError(FlyweightMessageContainer container) {
        info().$("sql error [q=`").utf8(getQueryOrHidden())
                .$("`, at=").$(container.getPosition())
                .$(", message=`").utf8(container.getFlyweightMessage()).$('`').$(']').$();
    }

    public void logTimings() {
        info().$("timings ")
                .$("[compiler: ").$(compilerNanos)
                .$(", count: ").$(recordCountNanos)
                .$(", execute: ").$(nanosecondClock.getTicks() - executeStartNanos)
                .$(", q=`").utf8(getQueryOrHidden())
                .$("`]").$();
    }

    public void setCompilerNanos(long compilerNanos) {
        this.compilerNanos = compilerNanos;
    }

    public void setContainsSecret(boolean containsSecret) {
        this.containsSecret = containsSecret;
    }

    public void setOperationFuture(OperationFuture fut) {
        operationFuture = fut;
    }

    public void setPausedQuery(boolean pausedQuery) {
        this.pausedQuery = pausedQuery;
    }

    public void setQueryType(short type) {
        queryType = type;
    }

    public void setRnd(Rnd rnd) {
        this.rnd = rnd;
    }

    public void startExecutionTimer() {
        this.executeStartNanos = nanosecondClock.getTicks();
    }

    private static void putBooleanValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getBool(col));
    }

    private static void putByteValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put((int) rec.getByte(col));
    }

    private static void putCharValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        char c = rec.getChar(col);
        if (c == 0) {
            socket.putAscii("\"\"");
        } else {
            socket.putAscii('"').put(c).putAscii('"');
        }
    }

    private static void putDateValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final long d = rec.getDate(col);
        if (d == Long.MIN_VALUE) {
            socket.putAscii("null");
            return;
        }
        socket.putAscii('"').putISODateMillis(d).putAscii('"');
    }

    private static void putGeoHashStringByteValue(HttpChunkedResponseSocket socket, Record rec, int col, int bitFlags) {
        byte l = rec.getGeoByte(col);
        GeoHashes.append(l, bitFlags, socket);
    }

    private static void putGeoHashStringIntValue(HttpChunkedResponseSocket socket, Record rec, int col, int bitFlags) {
        int l = rec.getGeoInt(col);
        GeoHashes.append(l, bitFlags, socket);
    }

    private static void putGeoHashStringLongValue(HttpChunkedResponseSocket socket, Record rec, int col, int bitFlags) {
        long l = rec.getGeoLong(col);
        GeoHashes.append(l, bitFlags, socket);
    }

    private static void putGeoHashStringShortValue(HttpChunkedResponseSocket socket, Record rec, int col, int bitFlags) {
        short l = rec.getGeoShort(col);
        GeoHashes.append(l, bitFlags, socket);
    }

    private static void putIPv4Value(HttpChunkedResponseSocket socket, Record rec, int col) {
        final int i = rec.getIPv4(col);
        if (i == Numbers.IPv4_NULL) {
            socket.putAscii("null");
        } else {
            socket.putAscii('"');
            Numbers.intToIPv4Sink(socket, i);
            socket.putAscii('"');
        }
    }

    private static void putIntValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final int i = rec.getInt(col);
        if (i == Integer.MIN_VALUE) {
            socket.putAscii("null");
        } else {
            socket.put(i);
        }
    }

    private static void putLong256Value(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.putAscii('"');
        rec.getLong256(col, socket);
        socket.putAscii('"');
    }

    private static void putLongValue(HttpChunkedResponseSocket socket, Record rec, int col, boolean quoteLargeNum) {
        final long l = rec.getLong(col);
        if (l == Long.MIN_VALUE) {
            socket.putAscii("null");
        } else if (quoteLargeNum) {
            socket.putAscii('"').put(l).putAscii('"');
        } else {
            socket.put(l);
        }
    }

    private static void putRecValue(HttpChunkedResponseSocket socket) {
        putStringOrNull(socket, null);
    }

    private static void putShortValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getShort(col));
    }

    private static void putStrValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        putStringOrNull(socket, rec.getStr(col));
    }

    private static void putStringOrNull(HttpChunkedResponseSocket socket, CharSequence str) {
        if (str == null) {
            socket.putAscii("null");
        } else {
            socket.putQuoted(str);
        }
    }

    private static void putSymValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        putStringOrNull(socket, rec.getSym(col));
    }

    private static void putTimestampValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        final long t = rec.getTimestamp(col);
        if (t == Long.MIN_VALUE) {
            socket.putAscii("null");
            return;
        }
        socket.putAscii('"').putISODate(t).putAscii('"');
    }

    private static void putUuidValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        long lo = rec.getLong128Lo(col);
        long hi = rec.getLong128Hi(col);
        if (Uuid.isNull(lo, hi)) {
            socket.putAscii("null");
            return;
        }
        socket.putAscii('"');
        Numbers.appendUuid(lo, hi, socket);
        socket.putAscii('"');
    }

    private boolean addColumnToOutput(
            RecordMetadata metadata,
            CharSequence columnNames,
            int start,
            int hi
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (start == hi) {
            info().$("empty column in list '").$(columnNames).$('\'').$();
            HttpChunkedResponseSocket socket = getHttpConnectionContext().getChunkedResponseSocket();
            JsonQueryProcessor.header(socket, getHttpConnectionContext(), "", 400);
            socket.putAscii('{')
                    .putAsciiQuoted("query").putAscii(':').putQuoted(query).putAscii(',')
                    .putAsciiQuoted("error").putAscii(':').putAsciiQuoted("empty column in list")
                    .putAscii('}');
            socket.sendChunk(true);
            return true;
        }

        int columnIndex = metadata.getColumnIndexQuiet(columnNames, start, hi);
        if (columnIndex == RecordMetadata.COLUMN_NOT_FOUND) {
            info().$("invalid column in list: '").$(columnNames, start, hi).$('\'').$();
            HttpChunkedResponseSocket socket = getHttpConnectionContext().getChunkedResponseSocket();
            JsonQueryProcessor.header(socket, getHttpConnectionContext(), "", 400);
            socket.putAscii('{')
                    .putAsciiQuoted("query").putAscii(':').putQuoted(query).putAscii(',')
                    .putAsciiQuoted("error").putAscii(':').putAscii('\'').putAscii("invalid column in list: ").put(columnNames, start, hi).putAscii('\'')
                    .putAscii('}');
            socket.sendChunk(true);
            return true;
        }

        addColumnTypeAndName(metadata, columnIndex);
        this.columnSkewList.add(columnIndex);
        return false;
    }

    private void addColumnTypeAndName(RecordMetadata metadata, int i) {
        int columnType = metadata.getColumnType(i);
        int flags = GeoHashes.getBitFlags(columnType);
        this.columnTypesAndFlags.add(columnType);
        this.columnTypesAndFlags.add(flags);
        this.columnNames.add(metadata.getColumnName(i));
    }

    private void doNextRecordLoop(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (doQueryNextRecord()) {
            doRecordFetchLoop(socket, columnCount);
        } else {
            doQuerySuffix(socket, columnCount);
        }
    }

    private void doQueryMetadata(HttpChunkedResponseSocket socket, int columnCount) {
        queryState = QUERY_METADATA;
        for (; columnIndex < columnCount; columnIndex++) {
            socket.bookmark();
            if (columnIndex > 0) {
                socket.putAscii(',');
            }
            int columnType = columnTypesAndFlags.getQuick(2 * columnIndex);
            socket.putAscii('{')
                    .putAsciiQuoted("name").putAscii(':').putQuoted(columnNames.getQuick(columnIndex))
                    .putAscii(',')
                    .putAsciiQuoted("type").putAscii(':').putAsciiQuoted(ColumnType.nameOf(columnType == ColumnType.NULL ? ColumnType.STRING : columnType))
                    .putAscii('}');
        }
    }

    private void doQueryMetadataSuffix(HttpChunkedResponseSocket socket) {
        queryState = QUERY_METADATA_SUFFIX;
        socket.bookmark();
        socket.putAscii("],\"timestamp\":").put(queryTimestampIndex);
        socket.putAscii(",\"dataset\":[");
    }

    private boolean doQueryNextRecord() {
        if (cursor.hasNext()) {
            if (count < stop) {
                return true;
            } else {
                onNoMoreData();
            }
        }
        return false;
    }

    private boolean doQueryPrefix(HttpChunkedResponseSocket socket) {
        if (noMeta) {
            socket.bookmark();
            socket.putAscii('{').putAsciiQuoted("dataset").putAscii(":[");
            return false;
        }
        socket.bookmark();
        socket.putAscii('{')
                .putAsciiQuoted("query").putAscii(':').putQuoted(query).putAscii(',')
                .putAsciiQuoted("columns").putAscii(':').putAscii('[');
        columnIndex = 0;
        return true;
    }

    private void doQueryRecord(HttpChunkedResponseSocket socket, int columnCount) {
        queryState = QUERY_RECORD;
        for (; columnIndex < columnCount; columnIndex++) {
            socket.bookmark();
            if (columnIndex > 0) {
                socket.putAscii(',');
            }

            int columnIdx = columnSkewList.size() > 0 ? columnSkewList.getQuick(columnIndex) : columnIndex;
            int columnType = columnTypesAndFlags.getQuick(2 * columnIndex);
            switch (ColumnType.tagOf(columnType)) {
                case ColumnType.BOOLEAN:
                    putBooleanValue(socket, record, columnIdx);
                    break;
                case ColumnType.BYTE:
                    putByteValue(socket, record, columnIdx);
                    break;
                case ColumnType.DOUBLE:
                    putDoubleValue(socket, record, columnIdx);
                    break;
                case ColumnType.FLOAT:
                    putFloatValue(socket, record, columnIdx);
                    break;
                case ColumnType.INT:
                    putIntValue(socket, record, columnIdx);
                    break;
                case ColumnType.LONG:
                    putLongValue(socket, record, columnIdx, quoteLargeNum);
                    break;
                case ColumnType.DATE:
                    putDateValue(socket, record, columnIdx);
                    break;
                case ColumnType.TIMESTAMP:
                    putTimestampValue(socket, record, columnIdx);
                    break;
                case ColumnType.SHORT:
                    putShortValue(socket, record, columnIdx);
                    break;
                case ColumnType.CHAR:
                    putCharValue(socket, record, columnIdx);
                    break;
                case ColumnType.STRING:
                    putStrValue(socket, record, columnIdx);
                    break;
                case ColumnType.SYMBOL:
                    putSymValue(socket, record, columnIdx);
                    break;
                case ColumnType.BINARY:
                    putBinValue(socket);
                    break;
                case ColumnType.LONG256:
                    putLong256Value(socket, record, columnIdx);
                    break;
                case ColumnType.GEOBYTE:
                    putGeoHashStringByteValue(socket, record, columnIdx, columnTypesAndFlags.getQuick(2 * columnIndex + 1));
                    break;
                case ColumnType.GEOSHORT:
                    putGeoHashStringShortValue(socket, record, columnIdx, columnTypesAndFlags.getQuick(2 * columnIndex + 1));
                    break;
                case ColumnType.GEOINT:
                    putGeoHashStringIntValue(socket, record, columnIdx, columnTypesAndFlags.getQuick(2 * columnIndex + 1));
                    break;
                case ColumnType.GEOLONG:
                    putGeoHashStringLongValue(socket, record, columnIdx, columnTypesAndFlags.getQuick(2 * columnIndex + 1));
                    break;
                case ColumnType.RECORD:
                    putRecValue(socket);
                    break;
                case ColumnType.NULL:
                    socket.putAscii("null");
                    break;
                case ColumnType.LONG128:
                    throw new UnsupportedOperationException();
                case ColumnType.UUID:
                    putUuidValue(socket, record, columnIdx);
                    break;
                case ColumnType.IPv4:
                    putIPv4Value(socket, record, columnIdx);
                    break;
                default:
                    assert false : "Not supported type in output " + ColumnType.nameOf(columnType);
                    socket.putAscii("null"); // To make JSON valid
                    break;
            }
        }
    }

    private void doQueryRecordPrefix(HttpChunkedResponseSocket socket) {
        queryState = QUERY_RECORD_PREFIX;
        socket.bookmark();
        if (count > skip) {
            socket.putAscii(',');
        }
        socket.putAscii('[');
        columnIndex = 0;
        count++;
    }

    private void doQueryRecordSuffix(HttpChunkedResponseSocket socket) {
        queryState = QUERY_RECORD_SUFFIX;
        socket.bookmark();
        socket.putAscii(']');
    }

    private void doQuerySuffix(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        // we no longer need cursor when we reached query suffix
        // closing cursor here guarantees that by the time http client finished reading response the table
        // is released
        cursor = Misc.free(cursor);
        queryState = QUERY_SUFFIX;
        if (count > -1) {
            logTimings();
            socket.bookmark();
            socket.putAscii(']');
            socket.putAscii(',').putAsciiQuoted("count").putAscii(':').put(count);
            if (timings) {
                socket.putAscii(',').putAsciiQuoted("timings").putAscii(':')
                        .putAscii('{')
                        .putAsciiQuoted("compiler").putAscii(':').put(compilerNanos).putAscii(',')
                        .putAsciiQuoted("execute").putAscii(':').put(nanosecondClock.getTicks() - executeStartNanos).putAscii(',')
                        .putAsciiQuoted("count").putAscii(':').put(recordCountNanos)
                        .putAscii('}');
            }
            if (explain) {
                socket.putAscii(',').putAsciiQuoted("explain").putAscii(':')
                        .putAscii('{')
                        .putAsciiQuoted("jitCompiled").putAscii(':').putAscii(queryJitCompiled ? "true" : "false")
                        .putAscii('}');
            }
            socket.putAscii('}');
            count = -1;
            socket.sendChunk(true);
            return;
        }
        socket.done();
    }

    private void doRecordFetchLoop(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        do {
            doQueryRecordPrefix(socket);
            doQueryRecord(socket, columnCount);
            doQueryRecordSuffix(socket);
        } while (doQueryNextRecord());
        doQuerySuffix(socket, columnCount);
    }

    private int getFd() {
        return httpConnectionContext.getFd();
    }

    private void onNoMoreData() {
        long nanos = nanosecondClock.getTicks();
        if (countRows) {
            // this is the tail end of the cursor
            // we don't need to read records, just round up record count
            final RecordCursor cursor = this.cursor;
            final long size = cursor.size();
            if (size < 0) {
                LOG.info().$("counting").$();
                long count = 1;
                while (cursor.hasNext()) {
                    count++;
                }
                this.count += count;
            } else {
                this.count = size;
            }
        }
        recordCountNanos = nanosecondClock.getTicks() - nanos;
    }

    private void onQueryMetadata(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryMetadata(socket, columnCount);
        onQueryMetadataSuffix(socket, columnCount);
    }

    private void onQueryMetadataSuffix(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryMetadataSuffix(socket);
        onSendRecordsLoop(socket, columnCount);
    }

    private void onQueryPrefix(HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (doQueryPrefix(socket)) {
            doQueryMetadata(socket, columnCount);
            doQueryMetadataSuffix(socket);
        }
        onSendRecordsLoop(socket, columnCount);
    }

    private void onQueryRecord(HttpChunkedResponseSocket socket, int columnCount) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecord(socket, columnCount);
        onQueryRecordSuffix(socket, columnCount);
    }

    private void onQueryRecordPrefix(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecordPrefix(socket);
        onQueryRecord(socket, columnCount);
    }

    private void onQueryRecordSuffix(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        doQueryRecordSuffix(socket);
        doNextRecordLoop(socket, columnCount);
    }

    private void onSendRecordsLoop(
            HttpChunkedResponseSocket socket,
            int columnCount
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        if (cursorHasRows) {
            doRecordFetchLoop(socket, columnCount);
        } else {
            doQuerySuffix(socket, columnCount);
        }
    }

    private void onSetupFirstRecord(HttpChunkedResponseSocket socket, int columnCount) throws PeerIsSlowToReadException, PeerDisconnectedException {
        // If there is an exception in the first record setup then upper layers will handle it:
        // Either they will send error or pause execution on DataUnavailableException
        setupFirstRecord();
        // If we make it past setup then we optimistically send HTTP 200 header.
        // There is still a risk of exception while iterating over cursor, but there is not much we can do about it.
        // Trying to access the first record before sending HTTP headers will already catch many errors.
        // So we can send an appropriate HTTP error code. If there is an error past the first record then
        // we have no choice but to disconnect :( - because we have already sent HTTP 200 header.

        // We assume HTTP headers will always fit into our buffer => a state transition to the QUERY_PREFIX
        // before actually sending HTTP headers. Otherwise, we would have to make JsonQueryProcessor.header() idempotent
        queryState = QUERY_PREFIX;
        JsonQueryProcessor.header(socket, getHttpConnectionContext(), keepAliveHeader, 200);
        onQueryPrefix(socket, columnCount);
    }

    private void putBinValue(HttpChunkedResponseSocket socket) {
        socket.putAscii('[');
        socket.putAscii(']');
    }

    private void putDoubleValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getDouble(col), doubleScale);
    }

    private void putFloatValue(HttpChunkedResponseSocket socket, Record rec, int col) {
        socket.put(rec.getFloat(col), floatScale);
    }

    private void setupFirstRecord() {
        if (skip > 0) {
            final RecordCursor cursor = this.cursor;
            long target = skip + 1;
            while (target > 0 && cursor.hasNext()) {
                target--;
            }
            if (target > 0) {
                cursorHasRows = false;
                return;
            }
            count = skip;
        } else {
            if (!cursor.hasNext()) {
                cursorHasRows = false;
                return;
            }
        }

        columnIndex = 0;
        record = cursor.getRecord();
        cursorHasRows = true;
    }

    static void prepareExceptionJson(
            HttpChunkedResponseSocket socket,
            int position,
            CharSequence message,
            CharSequence query
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        socket.putAscii('{')
                .putAsciiQuoted("query").putAscii(':').putQuoted(query == null ? "" : query).putAscii(',')
                .putAsciiQuoted("error").putAscii(':').putQuoted(message).putAscii(',')
                .putAsciiQuoted("position").putAscii(':').put(position)
                .putAscii('}');
        socket.sendChunk(true);
    }

    static void prepareExceptionJson(
            HttpChunkedResponseSocket socket,
            int position,
            CharSequence message,
            DirectUtf8Sequence query
    ) throws PeerDisconnectedException, PeerIsSlowToReadException {
        socket.putAscii('{')
                .putAsciiQuoted("query").putAscii(':').putQuoted(query == null ? "" : query.asAsciiCharSequence()).putAscii(',')
                .putAsciiQuoted("error").putAscii(':').putQuoted(message).putAscii(',')
                .putAsciiQuoted("position").putAscii(':').put(position)
                .putAscii('}');
        socket.sendChunk(true);
    }

    boolean of(
            RecordCursorFactory factory,
            SqlExecutionContextImpl sqlExecutionContext
    ) throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {
        return of(factory, true, sqlExecutionContext);
    }

    boolean of(
            RecordCursorFactory factory,
            boolean queryCacheable,
            SqlExecutionContextImpl sqlExecutionContext
    ) throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {
        this.recordCursorFactory = factory;
        this.queryCacheable = queryCacheable;
        this.queryJitCompiled = factory.usesCompiledFilter();
        // Enable column pre-touch in REST API only when LIMIT K,N is not specified since when limit is defined
        // we do a no-op loop over the cursor to calculate the total row count and pre-touch only slows things down.
        sqlExecutionContext.setColumnPreTouchEnabled(stop == Long.MAX_VALUE);
        this.cursor = factory.getCursor(sqlExecutionContext);
        final RecordMetadata metadata = factory.getMetadata();
        this.queryTimestampIndex = metadata.getTimestampIndex();
        HttpRequestHeader header = httpConnectionContext.getRequestHeader();
        DirectUtf8Sequence columnNames = header.getUrlParam(URL_PARAM_COLS);
        int columnCount;
        columnSkewList.clear();
        if (columnNames != null) {
            columnsQueryParameter.clear();
            if (!Utf8s.utf8ToUtf16(columnNames.lo(), columnNames.hi(), columnsQueryParameter)) {
                info().$("utf8 error when decoding column list '").$(columnNames).$('\'').$();
                HttpChunkedResponseSocket socket = getHttpConnectionContext().getChunkedResponseSocket();
                JsonQueryProcessor.header(socket, getHttpConnectionContext(), "", 400);
                socket.putAscii('{')
                        .putAsciiQuoted("error").putAscii(':').putAsciiQuoted("utf8 error in column list")
                        .putAscii('}');
                socket.sendChunk(true);
                return false;
            }

            columnCount = 1;
            int start = 0;
            int comma = 0;
            while (comma > -1) {
                comma = Chars.indexOf(columnsQueryParameter, start, ',');
                if (comma > -1) {
                    if (addColumnToOutput(metadata, columnsQueryParameter, start, comma)) {
                        return false;
                    }
                    start = comma + 1;
                    columnCount++;
                } else {
                    int hi = columnsQueryParameter.length();
                    if (addColumnToOutput(metadata, columnsQueryParameter, start, hi)) {
                        return false;
                    }
                }
            }
        } else {
            columnCount = metadata.getColumnCount();
            for (int i = 0; i < columnCount; i++) {
                addColumnTypeAndName(metadata, i);
            }
        }
        this.columnCount = columnCount;
        return true;
    }

    void resume(HttpChunkedResponseSocket socket) throws PeerDisconnectedException, PeerIsSlowToReadException {
        resumeActions.getQuick(queryState).onResume(socket, columnCount);
    }

    void setQueryCacheable(boolean queryCacheable) {
        this.queryCacheable = queryCacheable;
    }

    @FunctionalInterface
    interface StateResumeAction {
        void onResume(
                HttpChunkedResponseSocket socket,
                int columnCount
        ) throws PeerDisconnectedException, PeerIsSlowToReadException;
    }
}
