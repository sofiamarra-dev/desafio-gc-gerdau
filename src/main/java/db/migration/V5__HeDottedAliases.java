package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;

/** Acrescenta grafias confirmadas sem substituir decisões do editor. */
public class V5__HeDottedAliases extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection db = context.getConnection();
        try (var statement = db.prepareStatement("SELECT revision FROM abbreviation_state WHERE id=1 FOR UPDATE")) {
            try (var result = statement.executeQuery()) { result.next(); }
        }
        try (var statement = db.prepareStatement("SELECT meaning,active FROM abbreviation WHERE code='HE'");
             var result = statement.executeQuery()) {
            if (!result.next() || !result.getBoolean("active") || !"hora extra".equalsIgnoreCase(result.getString("meaning").strip())) return;
        }
        boolean changed = false;
        for (String[] alias : new String[][]{{"h . extra", "H. EXTRA"}, {"h . extras", "H. EXTRAS"}}) {
            try (var statement = db.prepareStatement("SELECT code FROM abbreviation_alias WHERE normalized=?")) {
                statement.setString(1, alias[0]);
                try (var result = statement.executeQuery()) { if (result.next()) continue; }
            }
            try (var statement = db.prepareStatement("INSERT INTO abbreviation_alias(normalized,code,expression,kind) VALUES (?,'HE',?,'VARIANT')")) {
                statement.setString(1, alias[0]); statement.setString(2, alias[1]); statement.executeUpdate();
            }
            changed = true;
        }
        if (changed) {
            try (var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE abbreviation SET version=version+1 WHERE code='HE'");
                statement.executeUpdate("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
            }
        }
    }
}
