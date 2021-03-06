/**
 * Copyright 2013 Technische Universitat Wien (TUW), Distributed Systems Group
 * E184
 *
 * This work was partially supported by the European Commission in terms of the
 * CELAR FP7 project (FP7-ICT-2011-8 \#317790)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package at.ac.tuwien.dsg.mela.dataservice.persistence;

import at.ac.tuwien.dsg.mela.common.elasticityAnalysis.concepts.elasticityPathway.LightweightEncounterRateElasticityPathway;
import at.ac.tuwien.dsg.mela.common.elasticityAnalysis.concepts.elasticitySpace.ElasticitySpace;
import at.ac.tuwien.dsg.mela.common.jaxbEntities.monitoringConcepts.MetricInfo;
import at.ac.tuwien.dsg.mela.common.jaxbEntities.monitoringConcepts.MonitoredElementData;
import at.ac.tuwien.dsg.mela.common.jaxbEntities.monitoringConcepts.MonitoringData;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.Metric;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.MonitoredElement;
import at.ac.tuwien.dsg.mela.common.monitoringConcepts.ServiceMonitoringSnapshot;
import at.ac.tuwien.dsg.mela.dataservice.config.ConfigurationUtility;
import at.ac.tuwien.dsg.mela.dataservice.config.ConfigurationXMLRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.support.AbstractLobCreatingPreparedStatementCallback;
import org.springframework.jdbc.support.lob.DefaultLobHandler;
import org.springframework.jdbc.support.lob.LobCreator;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.*;
import java.util.Collection;
import java.util.List;

/**
 * Author: Daniel Moldovan E-Mail: d.moldovan@dsg.tuwien.ac.at
 */
@Service
public class PersistenceSQLAccess {

    private static final String AGGREGATED_DATA_TABLE_NAME = "AggregatedData";

    static final Logger log = LoggerFactory.getLogger(PersistenceSQLAccess.class);

    private String monitoringSequenceID;

    @Value("#{dataSource}")
    private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigurationUtility configurationUtility;

    public PersistenceSQLAccess() {
    }

    public PersistenceSQLAccess(String monitoringSequenceID) {
        this.monitoringSequenceID = monitoringSequenceID;
    }

    @PostConstruct
    public void init() {
        log.debug("Creating new JdbcTemplate with datasource {}", dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void writeMonitoringSequenceId(String sequenceId) {

        String checkIfExistsSql = "select count(1) from MonitoringSeq where ID=?";
        long result = jdbcTemplate.queryForObject(checkIfExistsSql, Long.class, sequenceId);
        if (result < 1) {
            log.debug("Inserting sequenceId into MontoringSeq");
            String sql = "insert into MonitoringSeq (ID) VALUES (?)";
            jdbcTemplate.update(sql, sequenceId);
        }
    }


    /**
     * @param monitoringData MonitoringData objects collected from different
     *                       data sources
     */
    public void writeRawMonitoringData(String timestamp, Collection<MonitoringData> monitoringData) {

        String sql = "insert into RawCollectedData (monSeqID, timestampID, metricName, metricUnit, metrictype, value, monitoredElementID, monitoredElementLevel) "
                + "VALUES "
                + "( (select ID from MonitoringSeq where id='"
                + monitoringSequenceID
                + "')"
                + ", ( select ID from Timestamp where monseqid=(select ID from MonitoringSeq where ID='"
                + monitoringSequenceID
                + "')"
                + " AND timestamp=? )" + ",?,?,?,?,?,?)";

        for (MonitoringData data : monitoringData) {
            // for all monitored metrics insert in the metric values
            for (MonitoredElementData elementData : data.getMonitoredElementDatas()) {
                MonitoredElement element = elementData.getMonitoredElement();

                for (MetricInfo metricInfo : elementData.getMetrics()) {
                    jdbcTemplate.update(sql, timestamp, metricInfo.getName(),
                            metricInfo.getUnits(), metricInfo.getType(), metricInfo.getValue(),
                            element.getId(), element.getLevel().toString());
                }
            }
        }

    }

    /**
     * @param monSeqID currently ignored. Left for future extention
     */
    public void writeInTimestamp(String timestamp, String monSeqID) {
        String sql = "insert into Timestamp (monSeqID, timestamp) VALUES ( (SELECT ID from MonitoringSeq where id='"
                + monitoringSequenceID
                + "'), ?)";

        jdbcTemplate.update(sql, timestamp);
    }

    public void writeMonitoringData(String timestamp, ServiceMonitoringSnapshot monitoringSnapshot) {
        // if the firstMonitoringSequenceTimestamp is null, insert new
        // monitoring sequence
        String sql = "INSERT INTO " + AGGREGATED_DATA_TABLE_NAME + " (data, monSeqID, timestampID) "
                + "VALUES (?, ?, (SELECT ID from Timestamp where timestamp=? AND monSeqID=?))";

        jdbcTemplate.update(sql, monitoringSnapshot, monitoringSequenceID, timestamp, monitoringSequenceID);
    }


    public void writeElasticitySpace(ElasticitySpace elasticitySpace) {

        //delete previous entry
        String sql = "DELETE FROM ElasticitySpace WHERE monseqid=?";
        jdbcTemplate.update(sql, elasticitySpace.getService().getId());

        //add new entry
        sql = "INSERT INTO ElasticitySpace (monSeqID, timestampID, elasticitySpace) "
                + "VALUES "
                + "( (SELECT ID FROM MonitoringSeq WHERE id='"
                + monitoringSequenceID + "')" + ", ? " + ", ? )";

        jdbcTemplate.update(sql, elasticitySpace.getTimestampID(), elasticitySpace);

    }

    public void writeElasticityPathway(String timestamp, LightweightEncounterRateElasticityPathway elasticityPathway) {
        String sql = "insert into ElasticitySpace (monSeqID, timestampID, elasticitySpace) " + "VALUES " + "( (select ID from MonitoringSeq where id='"
                + monitoringSequenceID + "')" + ", ? " + ", ? )";

        jdbcTemplate.update(sql, monitoringSequenceID);


        sql = "insert into ElasticityPathway (monSeqID, timestampID, elasticityPathway) " + "VALUES "
                + "( (select ID from MonitoringSeq where id='" + monitoringSequenceID + "')"
                + ", (select ID from Timestamp where monseqid=(select ID from MonitoringSeq where ID='" + monitoringSequenceID + "')"
                + " AND timestamp= ? )" + ", ?)";

        jdbcTemplate.update(sql, timestamp, elasticityPathway);
    }

    public ElasticitySpace extractLatestElasticitySpace() {
        String sql = "SELECT timestampID, elasticitySpace from ElasticitySpace where monSeqID=?;";
        RowMapper<ElasticitySpace> rowMapper = new RowMapper<ElasticitySpace>() {
            public ElasticitySpace mapRow(ResultSet rs, int rowNum) throws SQLException {
                int timestamp = rs.getInt(1);
                ElasticitySpace space = (ElasticitySpace) rs.getObject(2);
                space.setTimestampID(timestamp);
                return space;
            }
        };

        List<ElasticitySpace> spaces = jdbcTemplate.query(sql, rowMapper, monitoringSequenceID);
        if (spaces.isEmpty()) {
            return null;
        } else {
            return spaces.get(0);
        }
    }

    public LightweightEncounterRateElasticityPathway extractLatestElasticityPathway() {
        String sql = "SELECT elasticityPathway from ElasticityPathway where monSeqID=?;";
        RowMapper<LightweightEncounterRateElasticityPathway> rowMapper = new RowMapper<LightweightEncounterRateElasticityPathway>() {
            public LightweightEncounterRateElasticityPathway mapRow(ResultSet rs, int rowNum) throws SQLException {
                return (LightweightEncounterRateElasticityPathway) rs.getObject(1);
            }
        };

        return jdbcTemplate.queryForObject(sql, rowMapper, monitoringSequenceID);
    }

    /**
     * @param startIndex from which monitored entry ID to start extracting
     * @param count      max number of elements to return
     * @return returns maximum count elements
     */
    public List<ServiceMonitoringSnapshot> extractMonitoringData(int startIndex, int count) {
        String sql = "SELECT data from " + AGGREGATED_DATA_TABLE_NAME + " where " + "ID > (?) AND ID < (?) AND monSeqID=(?);";
        RowMapper<ServiceMonitoringSnapshot> rowMapper = new RowMapper<ServiceMonitoringSnapshot>() {
            public ServiceMonitoringSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
                return (ServiceMonitoringSnapshot) rs.getObject(1);
            }
        };

        return jdbcTemplate.query(sql, rowMapper, startIndex, startIndex + count, monitoringSequenceID);
    }

    /**
     * @return returns maximum count elements
     */
    public ServiceMonitoringSnapshot extractLatestMonitoringData() {
        String sql = "SELECT timestampID, data from "
                + AGGREGATED_DATA_TABLE_NAME
                + " where " + "ID = (SELECT MAX(ID) from "
                + AGGREGATED_DATA_TABLE_NAME
                + " where monSeqID=?);";

        RowMapper<ServiceMonitoringSnapshot> rowMapper = new RowMapper<ServiceMonitoringSnapshot>() {
            public ServiceMonitoringSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
                int sTimestamp = rs.getInt(1);
                ServiceMonitoringSnapshot monitoringSnapshot = (ServiceMonitoringSnapshot) rs.getObject(2);
                monitoringSnapshot.setTimestampID(sTimestamp);
                return monitoringSnapshot;
            }
        };

        return jdbcTemplate.queryForObject(sql, rowMapper, monitoringSequenceID); // todo does this do the expected thing?
    }

    public List<ServiceMonitoringSnapshot> extractMonitoringData(int timestamp) {
        String sql = "SELECT timestampID, data from " + AGGREGATED_DATA_TABLE_NAME + " where monSeqID=? and timestampID > ?;";
        RowMapper<ServiceMonitoringSnapshot> rowMapper = new RowMapper<ServiceMonitoringSnapshot>() {
            public ServiceMonitoringSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
                int sTimestamp = rs.getInt(1);
                ServiceMonitoringSnapshot snapshot = (ServiceMonitoringSnapshot) rs.getObject(2);
                snapshot.setTimestampID(sTimestamp);
                return snapshot;
            }
        };

        return jdbcTemplate.query(sql, rowMapper, monitoringSequenceID, timestamp);
    }

    /**
     * @return returns maximum count elements
     */
    public List<ServiceMonitoringSnapshot> extractMonitoringData() {
        String sql = "SELECT timestampID, data from " + AGGREGATED_DATA_TABLE_NAME + " where monSeqID=?;";
        RowMapper<ServiceMonitoringSnapshot> rowMapper = new RowMapper<ServiceMonitoringSnapshot>() {
            public ServiceMonitoringSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
                int sTimestamp = rs.getInt(1);
                ServiceMonitoringSnapshot snapshot = (ServiceMonitoringSnapshot) rs.getObject(2);
                snapshot.setTimestampID(sTimestamp);
                return snapshot;
            }
        };

        return jdbcTemplate.query(sql, rowMapper, monitoringSequenceID);
    }

    public List<Metric> getAvailableMetrics(MonitoredElement monitoredElement) {
        String sql = "SELECT metricName, metricUnit, metrictype  from RawCollectedData where "
                + "timestampID = (SELECT MAX(ID) from Timestamp where monSeqID=?)"
                + " AND monitoredElementID=? AND monitoredElementLevel=?;";

        RowMapper<Metric> rowMapper = new RowMapper<Metric>() {
            public Metric mapRow(ResultSet rs, int rowNum) throws SQLException {
                String metricName = rs.getString("metricName");
                String metricUnit = rs.getString("metricUnit");
                return new Metric(metricName, metricUnit);
            }
        };

        return jdbcTemplate.query(sql, rowMapper,
                monitoringSequenceID,
                monitoredElement.getId(),
                monitoredElement.getLevel().toString());
    }

    public ConfigurationXMLRepresentation getLatestConfiguration() {
        String sql = "SELECT configuration from Configuration where ID=(Select max(ID) from Configuration)";
        ConfigurationXMLRepresentation configurationXMLRepresentation = null;
        try {
            RowMapper<String> rowMapper = new RowMapper<String>() {
                public String mapRow(ResultSet rs, int i) throws SQLException {
                    return new DefaultLobHandler().getClobAsString(rs, "configuration");
                }
            };

            List<String> configs = jdbcTemplate.query(sql, rowMapper);

            for (String config : configs) {
                JAXBContext context = JAXBContext.newInstance(ConfigurationXMLRepresentation.class);
                configurationXMLRepresentation = (ConfigurationXMLRepresentation) context.createUnmarshaller()
                        .unmarshal(new StringReader(config));
            }


        } catch (BadSqlGrammarException e) {
            log.error("Cannot load configuration from database: " + e.getMessage());
        } catch (JAXBException e) {
            log.error("Cannot unmarshall configuration in XML object: " + e.getMessage());
        }


        if (configurationXMLRepresentation == null) {
            return configurationUtility.createDefaultConfiguration();
        } else {
            return configurationXMLRepresentation;
        }
    }

    /**
     * @param configurationXMLRepresentation the used MELA configuration to be
     *                                       persisted in XML and reused
     */
    public void writeConfiguration(final ConfigurationXMLRepresentation configurationXMLRepresentation) {
        final StringWriter stringWriter = new StringWriter();
        try {
            JAXBContext context = JAXBContext.newInstance(ConfigurationXMLRepresentation.class);
            context.createMarshaller().marshal(configurationXMLRepresentation, stringWriter);
        } catch (JAXBException e) {
            log.warn("Cannot marshal configuration into string: " + e);
            return;
        }

        String sql = "INSERT INTO Configuration (configuration) " + "VALUES (?)";
        jdbcTemplate.execute(sql, new AbstractLobCreatingPreparedStatementCallback(new DefaultLobHandler()) {
            protected void setValues(PreparedStatement ps, LobCreator lobCreator) throws SQLException {
                lobCreator.setClobAsString(ps, 1, stringWriter.toString());
            }
        });
    }

    // todo recreate persistence context here, invoked if service configuration changes (actually re-instantiates the PersistenceSQLAccess object)
    public void refresh() {

    }

    public void setMonitoringId(String monitoringSequenceID) {
        this.monitoringSequenceID = monitoringSequenceID;
    }
}
