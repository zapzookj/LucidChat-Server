package com.spring.aichat.service.tts;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TtsMigrationTest {
    @Test void actualV39PreservesRoomsAndEnforcesResponseAndChargeConstraints() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:tts_" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "")) {
            var statement = db.createStatement();
            statement.execute("CREATE TABLE chat_rooms(id BIGINT PRIMARY KEY)");
            statement.execute("INSERT INTO chat_rooms(id) VALUES(1),(2)");
            var migration = new ClassPathResource("db/migration/V39__tts_response_audio.sql");
            ScriptUtils.executeSqlScript(db, migration);
            ScriptUtils.executeSqlScript(db, migration);
            try (var rooms = statement.executeQuery("SELECT COUNT(*), SUM(CASE WHEN tts_enabled=FALSE THEN 1 ELSE 0 END), COUNT(tts_energy_cost_accepted) FROM chat_rooms")) {
                assertThat(rooms.next()).isTrue();
                assertThat(rooms.getInt(1)).isEqualTo(2);
                assertThat(rooms.getInt(2)).isEqualTo(2);
                assertThat(rooms.getInt(3)).isZero();
            }
            String insert = "INSERT INTO tts_response_audio(user_id,room_id,log_id,source_hash,status,attempt_id,request_key,model,clips_json,from_free,from_paid,created_at,updated_at) "
                + "VALUES(1,1,'%s','hash','%s','attempt','request','eleven_v4','[]',%d,%d,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)";
            statement.execute(insert.formatted("log1", "QUEUED", 1, 0));
            statement.execute(insert.formatted("log2", "READY", 0, 1));
            assertThatThrownBy(() -> statement.execute(insert.formatted("log1", "QUEUED", 1, 0))).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute(insert.formatted("bad-free", "QUEUED", -1, 1))).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute(insert.formatted("bad-paid", "QUEUED", 1, -1))).isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.execute(insert.formatted("bad-status", "UNKNOWN", 1, 0))).isInstanceOf(java.sql.SQLException.class);
            try (var jobs = statement.executeQuery("SELECT COUNT(*), COUNT(DISTINCT log_id), SUM(from_free), SUM(from_paid), SUM(CASE WHEN cleanup_json='[]' AND refunded=FALSE THEN 1 ELSE 0 END) FROM tts_response_audio")) {
                assertThat(jobs.next()).isTrue();
                assertThat(jobs.getInt(1)).isEqualTo(2);
                assertThat(jobs.getInt(2)).isEqualTo(2);
                assertThat(jobs.getInt(3)).isEqualTo(1);
                assertThat(jobs.getInt(4)).isEqualTo(1);
                assertThat(jobs.getInt(5)).isEqualTo(2);
            }
        }
    }
}
