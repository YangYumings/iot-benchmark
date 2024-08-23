/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package cn.edu.tsinghua.iot.benchmark.iotdb130;

import org.apache.iotdb.isession.util.Version;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;

import cn.edu.tsinghua.iot.benchmark.client.operation.Operation;
import cn.edu.tsinghua.iot.benchmark.conf.Config;
import cn.edu.tsinghua.iot.benchmark.conf.ConfigDescriptor;
import cn.edu.tsinghua.iot.benchmark.entity.Batch.IBatch;
import cn.edu.tsinghua.iot.benchmark.entity.DeviceSummary;
import cn.edu.tsinghua.iot.benchmark.entity.Record;
import cn.edu.tsinghua.iot.benchmark.entity.Sensor;
import cn.edu.tsinghua.iot.benchmark.entity.enums.SensorType;
import cn.edu.tsinghua.iot.benchmark.exception.DBConnectException;
import cn.edu.tsinghua.iot.benchmark.iotdb130.ModelStrategy.IoTDBModelStrategy;
import cn.edu.tsinghua.iot.benchmark.iotdb130.ModelStrategy.TableStrategy;
import cn.edu.tsinghua.iot.benchmark.iotdb130.ModelStrategy.TreeStrategy;
import cn.edu.tsinghua.iot.benchmark.iotdb130.QueryAndInsertionStrategy.IoTDBInsertionStrategy;
import cn.edu.tsinghua.iot.benchmark.iotdb130.QueryAndInsertionStrategy.JDBCStrategy;
import cn.edu.tsinghua.iot.benchmark.iotdb130.QueryAndInsertionStrategy.SessionStrategy;
import cn.edu.tsinghua.iot.benchmark.measurement.Status;
import cn.edu.tsinghua.iot.benchmark.schema.schemaImpl.DeviceSchema;
import cn.edu.tsinghua.iot.benchmark.tsdb.DBConfig;
import cn.edu.tsinghua.iot.benchmark.tsdb.IDatabase;
import cn.edu.tsinghua.iot.benchmark.tsdb.TsdbException;
import cn.edu.tsinghua.iot.benchmark.utils.TimeUtils;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggRangeQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggRangeValueQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.AggValueQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.DeviceQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.GroupByQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.LatestPointQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.PreciseQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.RangeQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.ValueRangeQuery;
import cn.edu.tsinghua.iot.benchmark.workload.query.impl.VerificationQuery;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.util.Pair;

/** this class will create more than one connection. */
public class IoTDB implements IDatabase {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDB.class);

  public final String DELETE_SERIES_SQL;
  public final String ROOT_SERIES_NAME;
  private final DBConfig dbConfig;
  private final Random random = new Random(config.getDATA_SEED());
  private final IoTDBInsertionStrategy insertionStrategy;
  private final IoTDBModelStrategy modelStrategy;

  public static final String ALREADY_KEYWORD = "already";
  private static final Config config = ConfigDescriptor.getInstance().getConfig();
  private static final String ORDER_BY_TIME_DESC = " order by time desc ";

  public IoTDB(DBConfig dbConfig) {
    this.dbConfig = dbConfig;
    ROOT_SERIES_NAME = "root." + dbConfig.getDB_NAME();
    DELETE_SERIES_SQL = "delete storage group root." + dbConfig.getDB_NAME() + ".*";
    // init IoTDBModelStrategy and IoTDBInsertionStrategy
    modelStrategy = config.isIoTDB_ENABLE_TABLE() ? new TableStrategy(dbConfig, this) : new TreeStrategy(dbConfig, this);
    switch (dbConfig.getDB_SWITCH()) {
      case DB_IOT_130_SESSION_BY_TABLE:
      case DB_IOT_130_REST:
      case DB_IOT_130_SESSION_BY_TABLET:
      case DB_IOT_130_SESSION_BY_RECORD:
      case DB_IOT_130_SESSION_BY_RECORDS:
        insertionStrategy = new SessionStrategy(this, dbConfig);
        break;
      case DB_IOT_130_JDBC:
        insertionStrategy = new JDBCStrategy(dbConfig, this);
        break;
      default:
        throw new IllegalArgumentException("Unsupported DB SWITCH: " + dbConfig.getDB_SWITCH());
    }
  }

  /**
   * create timeseries one by one is too slow in current cluster server. therefore, we use session
   * to create time series in batch.
   *
   * @param schemaList schema of devices to register
   * @return
   * @throws TsdbException
   */
  @Override
  public Double registerSchema(List<DeviceSchema> schemaList) throws TsdbException {
    long start = System.nanoTime();
    Double time = null;
    if (config.hasWrite()) {
      Map<Session, List<TimeseriesSchema>> sessionListMap = new HashMap<>();
      try {
        // open meta session
        Session metaSession =
            new Session.Builder()
                .host(dbConfig.getHOST().get(0))
                .port(Integer.parseInt(dbConfig.getPORT().get(0)))
                .username(dbConfig.getUSERNAME())
                .password(dbConfig.getPASSWORD())
                .version(Version.V_1_0)
                .sqlDialect(dbConfig.getSQL_DIALECT())
                .build();
        metaSession.open(config.isENABLE_THRIFT_COMPRESSION());
        sessionListMap.put(metaSession, modelStrategy.createTimeseries(schemaList));
        time = modelStrategy.registerSchema(sessionListMap, schemaList);
      } catch (Exception e) {
        throw new TsdbException(e);
      } finally {
        if (!sessionListMap.isEmpty()) {
          Set<Session> sessions = sessionListMap.keySet();
          for (Session session : sessions) {
            try {
              session.close();
            } catch (IoTDBConnectionException e) {
              LOGGER.error("Schema-register session cannot be closed: {}", e.getMessage());
            }
          }
        }
      }
    }
    long end = System.nanoTime();
    return time == null ? TimeUtils.convertToSeconds(end - start, "ns") : time;
  }

  @Override
  public Status insertOneBatch(IBatch batch) throws DBConnectException {
    String deviceId = getDevicePath(batch.getDeviceSchema());
    return insertionStrategy.insertOneBatch(batch, deviceId);
  }

  @Override
  public Status insertOneBatchWithCheck(IBatch batch) throws Exception {
    return IDatabase.super.insertOneBatchWithCheck(batch);
  }

  private void handleRegisterException(Exception e) throws TsdbException {
    // ignore if already has the time series
    if (!e.getMessage().contains(ALREADY_KEYWORD) && !e.getMessage().contains("300")) {
      LOGGER.error("Register IoTDB schema failed because ", e);
      throw new TsdbException(e);
    }
  }

  /**
   * Q1: PreciseQuery SQL: select {sensors} from {devices} where time = {time}
   *
   * @param preciseQuery universal precise query condition parameters
   * @return
   */
  @Override
  public Status preciseQuery(PreciseQuery preciseQuery) {
    String strTime = preciseQuery.getTimestamp() + "";
    String sql = getSimpleQuerySqlHead(preciseQuery.getDeviceSchema()) + " WHERE time = " + strTime;
    return executeQueryAndGetStatus(sql, Operation.PRECISE_QUERY);
  }

  /**
   * Q2: RangeQuery SQL: select {sensors} from {devices} where time >= {startTime} and time <=
   * {endTime}
   *
   * @param rangeQuery universal range query condition parameters
   * @return
   */
  @Override
  public Status rangeQuery(RangeQuery rangeQuery) {
    String sql =
        getRangeQuerySql(
            rangeQuery.getDeviceSchema(),
            rangeQuery.getStartTimestamp(),
            rangeQuery.getEndTimestamp());
    return executeQueryAndGetStatus(sql, Operation.RANGE_QUERY);
  }

  /**
   * Q3: ValueRangeQuery SQL: select {sensors} from {devices} where time >= {startTime} and time <=
   * {endTime} and {sensors} > {value}
   *
   * @param valueRangeQuery contains universal range query with value filter parameters
   * @return
   */
  @Override
  public Status valueRangeQuery(ValueRangeQuery valueRangeQuery) {
    String sql = getValueRangeQuerySql(valueRangeQuery);
    return executeQueryAndGetStatus(sql, Operation.VALUE_RANGE_QUERY);
  }

  /**
   * Q4: AggRangeQuery SQL: select {AggFun}({sensors}) from {devices} where time >= {startTime} and
   * time <= {endTime}
   *
   * @param aggRangeQuery contains universal aggregation query with time filter parameters
   * @return
   */
  @Override
  public Status aggRangeQuery(AggRangeQuery aggRangeQuery) {
    String aggQuerySqlHead =
        getAggQuerySqlHead(aggRangeQuery.getDeviceSchema(), aggRangeQuery.getAggFun());
    String sql =
        addWhereTimeClause(
            aggQuerySqlHead, aggRangeQuery.getStartTimestamp(), aggRangeQuery.getEndTimestamp());
    return executeQueryAndGetStatus(sql, Operation.AGG_RANGE_QUERY);
  }

  /**
   * Q5: AggValueQuery SQL: select {AggFun}({sensors}) from {devices} where {sensors} > {value}
   *
   * @param aggValueQuery contains universal aggregation query with value filter parameters
   * @return
   */
  @Override
  public Status aggValueQuery(AggValueQuery aggValueQuery) {
    String aggQuerySqlHead =
        getAggQuerySqlHead(aggValueQuery.getDeviceSchema(), aggValueQuery.getAggFun());
    String sql =
        aggQuerySqlHead
            + " WHERE "
            + getValueFilterClause(
                    aggValueQuery.getDeviceSchema(), (int) aggValueQuery.getValueThreshold())
                .substring(4);
    return executeQueryAndGetStatus(sql, Operation.AGG_VALUE_QUERY);
  }

  /**
   * Q6: AggRangeValueQuery SQL: select {AggFun}({sensors}) from {devices} where time >= {startTime}
   * and time <= {endTime} and {sensors} > {value}
   *
   * @param aggRangeValueQuery contains universal aggregation query with time and value filters
   *     parameters
   * @return
   */
  @Override
  public Status aggRangeValueQuery(AggRangeValueQuery aggRangeValueQuery) {
    String aggQuerySqlHead =
        getAggQuerySqlHead(aggRangeValueQuery.getDeviceSchema(), aggRangeValueQuery.getAggFun());
    String sql =
        addWhereTimeClause(
            aggQuerySqlHead,
            aggRangeValueQuery.getStartTimestamp(),
            aggRangeValueQuery.getEndTimestamp());
    sql +=
        getValueFilterClause(
            aggRangeValueQuery.getDeviceSchema(), (int) aggRangeValueQuery.getValueThreshold());
    return executeQueryAndGetStatus(sql, Operation.AGG_RANGE_VALUE_QUERY);
  }

  /**
   * Q7: GroupByQuery SQL: select {AggFun}({sensors}) from {devices} group by ([{start}, {end}],
   * {Granularity}ms)
   *
   * @param groupByQuery contains universal group by query condition parameters
   * @return
   */
  @Override
  public Status groupByQuery(GroupByQuery groupByQuery) {
    String aggQuerySqlHead =
        getAggQuerySqlHead(groupByQuery.getDeviceSchema(), groupByQuery.getAggFun());
    String sql =
        addGroupByClause(
            aggQuerySqlHead,
            groupByQuery.getStartTimestamp(),
            groupByQuery.getEndTimestamp(),
            groupByQuery.getGranularity());
    return executeQueryAndGetStatus(sql, Operation.GROUP_BY_QUERY);
  }

  /**
   * Q8: LatestPointQuery SQL: select last {sensors} from {devices}
   *
   * @param latestPointQuery contains universal latest point query condition parameters
   * @return
   */
  @Override
  public Status latestPointQuery(LatestPointQuery latestPointQuery) {
    String aggQuerySqlHead = getLatestPointQuerySql(latestPointQuery.getDeviceSchema());
    return executeQueryAndGetStatus(aggQuerySqlHead, Operation.LATEST_POINT_QUERY);
  }

  /**
   * Q9: RangeQuery SQL: select {sensors} from {devices} where time >= {startTime} and time <=
   * {endTime} order by time desc
   *
   * @param rangeQuery universal range query condition parameters
   * @return
   */
  @Override
  public Status rangeQueryOrderByDesc(RangeQuery rangeQuery) {
    String sql =
        getRangeQuerySql(
                rangeQuery.getDeviceSchema(),
                rangeQuery.getStartTimestamp(),
                rangeQuery.getEndTimestamp())
            + " order by time desc";
    return executeQueryAndGetStatus(sql, Operation.RANGE_QUERY_ORDER_BY_TIME_DESC);
  }

  /**
   * Q10: ValueRangeQuery SQL: select {sensors} from {devices} where time >= {startTime} and time <=
   * {endTime} and {sensors} > {value} order by time desc
   *
   * @param valueRangeQuery contains universal range query with value filter parameters
   * @return
   */
  @Override
  public Status valueRangeQueryOrderByDesc(ValueRangeQuery valueRangeQuery) {
    String sql = getValueRangeQuerySql(valueRangeQuery) + " order by time desc";
    return executeQueryAndGetStatus(sql, Operation.VALUE_RANGE_QUERY_ORDER_BY_TIME_DESC);
  }

  @Override
  public Status groupByQueryOrderByDesc(GroupByQuery groupByQuery) {
    String aggQuerySqlHead =
        getAggQuerySqlHead(groupByQuery.getDeviceSchema(), groupByQuery.getAggFun());
    String sql =
        addGroupByClause(
            aggQuerySqlHead,
            groupByQuery.getStartTimestamp(),
            groupByQuery.getEndTimestamp(),
            groupByQuery.getGranularity());
    sql += ORDER_BY_TIME_DESC;
    return executeQueryAndGetStatus(sql, Operation.GROUP_BY_QUERY_ORDER_BY_TIME_DESC);
  }

  /**
   * Generate simple query header.
   *
   * @param devices schema list of query devices
   * @return Simple Query header. e.g. Select sensors from devices
   */
  protected String getSimpleQuerySqlHead(List<DeviceSchema> devices) {
    StringBuilder builder = modelStrategy.getSimpleQuerySqlHead(devices);
    return addFromClause(devices, builder);
  }

  private String getAggQuerySqlHead(List<DeviceSchema> devices, String aggFun) {
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT ");
    List<Sensor> querySensors = devices.get(0).getSensors();
    builder.append(aggFun).append("(").append(querySensors.get(0).getName()).append(")");
    for (int i = 1; i < querySensors.size(); i++) {
      builder
          .append(", ")
          .append(aggFun)
          .append("(")
          .append(querySensors.get(i).getName())
          .append(")");
    }
    return addFromClause(devices, builder);
  }

  /**
   * Add from Clause
   *
   * @param devices
   * @param builder
   * @return From clause, e.g. FROM devices
   */
  private String addFromClause(List<DeviceSchema> devices, StringBuilder builder) {
    return modelStrategy.addFromClause(devices, builder);
  }

  private String getValueRangeQuerySql(ValueRangeQuery valueRangeQuery) {
    String rangeQuerySql =
        getRangeQuerySql(
            valueRangeQuery.getDeviceSchema(),
            valueRangeQuery.getStartTimestamp(),
            valueRangeQuery.getEndTimestamp());
    String valueFilterClause =
        getValueFilterClause(
            valueRangeQuery.getDeviceSchema(), (int) valueRangeQuery.getValueThreshold());
    return rangeQuerySql + valueFilterClause;
  }

  private String getValueFilterClause(List<DeviceSchema> deviceSchemas, int valueThreshold) {
    return modelStrategy.getValueFilterClause(deviceSchemas, valueThreshold);
  }

  private String getLatestPointQuerySql(List<DeviceSchema> devices) {
    StringBuilder builder = new StringBuilder();
    builder.append("SELECT last ");
    List<Sensor> querySensors = devices.get(0).getSensors();
    builder.append(querySensors.get(0).getName());
    for (int i = 1; i < querySensors.size(); i++) {
      builder.append(", ").append(querySensors.get(i).getName());
    }
    return addFromClause(devices, builder);
  }

  private String getRangeQuerySql(List<DeviceSchema> deviceSchemas, long start, long end) {
    return addWhereTimeClause(getSimpleQuerySqlHead(deviceSchemas), start, end);
  }

  private String addWhereTimeClause(String prefix, long start, long end) {
    String startTime = start + "";
    String endTime = end + "";
    return prefix + " WHERE time >= " + startTime + " AND time <= " + endTime;
  }

  private String addGroupByClause(String prefix, long start, long end, long granularity) {
    return prefix + " group by ([" + start + "," + end + ")," + granularity + "ms) ";
  }

  /**
   * convert deviceSchema to the format
   *
   * @param deviceSchema
   * @return format, e.g. root.group_1.d_1
   */
  protected String getDevicePath(DeviceSchema deviceSchema) {
    StringBuilder name = new StringBuilder(ROOT_SERIES_NAME);
    name.append(".").append(deviceSchema.getGroup());
    for (Map.Entry<String, String> pair : deviceSchema.getTags().entrySet()) {
      name.append(".").append(pair.getValue());
    }
    name.append(".").append(deviceSchema.getDevice());
    return name.toString();
  }

  protected Status executeQueryAndGetStatus(String sql, Operation operation) {
    String executeSQL;
    if (config.isIOTDB_USE_DEBUG() && random.nextDouble() < config.getIOTDB_USE_DEBUG_RATIO()) {
      executeSQL = "debug " + sql;
    } else {
      executeSQL = sql;
    }
    if (!config.isIS_QUIET_MODE()) {
      LOGGER.info("{} query SQL: {}", Thread.currentThread().getName(), executeSQL);
    }

    long queryResultPointNum = 0;
    AtomicBoolean isOk = new AtomicBoolean(true);
    List<List<Object>> records = new ArrayList<>();
    try {
      Pair<Long, Boolean> result =
          insertionStrategy.executeQueryAndGetStatusImpl(executeSQL, operation, isOk, records);
      queryResultPointNum = result.getKey();
      if (!result.getValue()) {
        //        return new Status(false, queryResultPointNum, e, executeSQL);
      }
    } catch (Throwable t) {
      return new Status(false, queryResultPointNum, new Exception(t), executeSQL);
    }
    if (isOk.get()) {
      if (config.isIS_COMPARISON()) {
        return new Status(true, queryResultPointNum, executeSQL, records);
      } else {
        return new Status(true, queryResultPointNum);
      }
    } else {
      return new Status(
          false, queryResultPointNum, new Exception("Failed to execute."), executeSQL);
    }
  }

  /**
   * Using in verification
   *
   * @param verificationQuery
   */
  @Override
  public Status verificationQuery(VerificationQuery verificationQuery) {
    DeviceSchema deviceSchema = verificationQuery.getDeviceSchema();
    List<DeviceSchema> deviceSchemas = new ArrayList<>();
    deviceSchemas.add(deviceSchema);

    List<Record> records = verificationQuery.getRecords();
    if (records == null || records.size() == 0) {
      return new Status(
          false,
          new TsdbException("There are no records in verficationQuery."),
          "There are no records in verficationQuery.");
    }

    StringBuffer sql = new StringBuffer();
    sql.append(getSimpleQuerySqlHead(deviceSchemas));
    Map<Long, List<Object>> recordMap = new HashMap<>();
    sql.append(" WHERE time = ").append(records.get(0).getTimestamp());
    recordMap.put(records.get(0).getTimestamp(), records.get(0).getRecordDataValue());
    for (int i = 1; i < records.size(); i++) {
      Record record = records.get(i);
      sql.append(" or time = ").append(record.getTimestamp());
      recordMap.put(record.getTimestamp(), record.getRecordDataValue());
    }
    int point, line;
    try {
      List<Integer> resultList = insertionStrategy.verificationQueryImpl(sql.toString(), recordMap);
      point = resultList.get(0);
      line = resultList.get(1);
    } catch (Exception e) {
      LOGGER.error("Query Error: " + sql);
      return new Status(false, new TsdbException("Failed to query"), "Failed to query.");
    }
    if (recordMap.size() != line) {
      LOGGER.error(
          "Using SQL: " + sql + ",Expected line:" + recordMap.size() + " but was: " + line);
    }
    return new Status(true, point);
  }

  @Override
  public Status deviceQuery(DeviceQuery deviceQuery) throws SQLException, TsdbException {
    DeviceSchema deviceSchema = deviceQuery.getDeviceSchema();
    String sql =
        getDeviceQuerySql(
            deviceSchema, deviceQuery.getStartTimestamp(), deviceQuery.getEndTimestamp());
    if (!config.isIS_QUIET_MODE()) {
      LOGGER.info("IoTDB:" + sql);
    }
    List<List<Object>> result;
    try {
      result = insertionStrategy.deviceQueryImpl(sql);
    } catch (Exception e) {
      LOGGER.error("Query Error: " + sql + " exception:" + e.getMessage());
      return new Status(false, new TsdbException("Failed to query"), "Failed to query.");
    }
    return new Status(true, 0, sql, result);
  }

  protected String getDeviceQuerySql(
      DeviceSchema deviceSchema, long startTimeStamp, long endTimeStamp) {
    StringBuffer sql = new StringBuffer();
    List<DeviceSchema> deviceSchemas = new ArrayList<>();
    deviceSchemas.add(deviceSchema);
    sql.append(getSimpleQuerySqlHead(deviceSchemas));
    sql.append(" where time >= ").append(startTimeStamp);
    sql.append(" and time <").append(endTimeStamp);
    sql.append(" order by time desc");
    return sql.toString();
  }

  @Override
  public DeviceSummary deviceSummary(DeviceQuery deviceQuery) throws SQLException, TsdbException {
    DeviceSchema schema = deviceQuery.getDeviceSchema();
    return insertionStrategy.deviceSummary(
        schema.getDevice(),
        getTotalLineNumberSql(schema),
        getMaxTimeStampSql(schema),
        getMinTimeStampSql(schema));
  }

  @Override
  public String typeMap(SensorType iotdbSensorType) {
    return IDatabase.super.typeMap(iotdbSensorType);
  }

  protected String getTotalLineNumberSql(DeviceSchema deviceSchema) {
    return "select count(*) from " + getDevicePath(deviceSchema);
  }

  protected String getMinTimeStampSql(DeviceSchema deviceSchema) {
    return "select * from " + getDevicePath(deviceSchema) + " order by time limit 1";
  }

  protected String getMaxTimeStampSql(DeviceSchema deviceSchema) {
    return "select * from " + getDevicePath(deviceSchema) + " order by time desc limit 1";
  }

  public static String getEncodingType(SensorType dataSensorType) {
    switch (dataSensorType) {
      case BOOLEAN:
        return config.getENCODING_BOOLEAN();
      case INT32:
        return config.getENCODING_INT32();
      case INT64:
        return config.getENCODING_INT64();
      case FLOAT:
        return config.getENCODING_FLOAT();
      case DOUBLE:
        return config.getENCODING_DOUBLE();
      case TEXT:
        return config.getENCODING_TEXT();
      case STRING:
        return config.getENCODING_STRING();
      case BLOB:
        return config.getENCODING_BLOB();
      case TIMESTAMP:
        return config.getENCODING_TIMESTAMP();
      case DATE:
        return config.getENCODING_DATE();
      default:
        LOGGER.error("Unsupported data sensorType {}.", dataSensorType);
        return null;
    }
  }

  /**
   * convert deviceSchema and sensor to the format: root.group_1.d_1.s_1
   *
   * @param deviceSchema
   * @param sensor
   * @return
   */
  private String getSensorPath(DeviceSchema deviceSchema, String sensor) {
    return getDevicePath(deviceSchema) + "." + sensor;
  }

  @Override
  public void cleanup() throws TsdbException {
    insertionStrategy.cleanup();
  }

  @Override
  public void init() throws TsdbException {
    insertionStrategy.init();
  }

  @Override
  public void close() throws TsdbException {
    insertionStrategy.close();
  }

  // region should only call by SessionStrategy

  public Session buildSession(List<String> hostUrls) {
    return modelStrategy.buildSession(hostUrls);
  }

  public String getDeviceId(DeviceSchema schema) {
    return modelStrategy.getDeviceId(schema);
  }

  public Tablet createTablet(
      String insertTargetName,
      List<IMeasurementSchema> schemas,
      List<Tablet.ColumnType> columnTypes,
      int maxRowNumber) {
    return modelStrategy.createTablet(insertTargetName, schemas, columnTypes, maxRowNumber);
  }

  public void sessionCleanupImpl(Session session)
      throws IoTDBConnectionException, StatementExecutionException {
    modelStrategy.sessionCleanupImpl(session);
  }

  public void sessionInsertImpl(Session session, Tablet tablet)
      throws IoTDBConnectionException, StatementExecutionException {
    modelStrategy.sessionInsertImpl(session, tablet);
  }

  // endregion
}
