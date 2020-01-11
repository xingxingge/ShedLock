package net.javacrumbs.shedlock.provider.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.querybuilder.QueryBuilder;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.support.AbstractStorageAccessor;
import net.javacrumbs.shedlock.support.Utils;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Optional;

import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.insertInto;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal;
import static com.datastax.oss.driver.api.querybuilder.QueryBuilder.update;

class CassandraStorageAccessor extends AbstractStorageAccessor {
    private static final String LOCK_NAME = "name";
    private static final String LOCK_UNTIL = "lockUntil";
    private static final String LOCKED_AT = "lockedAt";
    private static final String LOCKED_BY = "lockedBy";

    private final String hostname;
    private final String table;
    private final CqlSession cqlSession;
    private final ConsistencyLevel consistencyLevel;

    CassandraStorageAccessor(@NotNull CqlSession cqlSession, @NotNull String table, @NotNull ConsistencyLevel consistencyLevel) {
        this.hostname = Utils.getHostname();
        this.table = table;
        this.cqlSession = cqlSession;
        this.consistencyLevel = consistencyLevel;
    }

    @Override
    public boolean insertRecord(@NotNull LockConfiguration lockConfiguration) {
        return execute(insertInto(table)
                .value(LOCK_NAME, literal(lockConfiguration.getName()))
                .value(LOCK_UNTIL, literal(lockConfiguration.getLockAtMostUntil()))
                .value(LOCKED_AT, literal(Instant.now()))
                .value(LOCKED_BY, literal(hostname))
                .ifNotExists()
                .build());
    }

    @Override
    public boolean updateRecord(@NotNull LockConfiguration lockConfiguration) {
        return execute(update(table)
                .setColumn(LOCK_UNTIL, literal(lockConfiguration.getLockAtMostUntil()))
                .setColumn(LOCKED_AT, literal(Instant.now()))
                .setColumn(LOCKED_BY, literal(hostname))
                .whereColumn(LOCK_NAME).isEqualTo(literal(lockConfiguration.getName()))
                .ifColumn(LOCK_UNTIL).isLessThan(literal(Instant.now()))
                .build());
    }

    @Override
    public void unlock(@NotNull LockConfiguration lockConfiguration) {
        execute(update(table)
            .setColumn(LOCK_UNTIL, literal(lockConfiguration.getUnlockTime()))
            .whereColumn(LOCK_NAME).isEqualTo(literal(lockConfiguration.getName()))
            .build());
    }

    @Override
    public boolean extend(@NotNull LockConfiguration lockConfiguration) {
        return execute(update(table)
                .setColumn(LOCK_UNTIL, literal(lockConfiguration.getLockAtMostUntil()))
                .whereColumn(LOCK_NAME).isEqualTo(literal(lockConfiguration.getName()))
                .ifColumn(LOCK_UNTIL).isGreaterThanOrEqualTo(literal(Instant.now()))
                .ifColumn(LOCKED_BY).isEqualTo(literal(hostname))
                .build());
    }

    /**
     * Find existing row by primary key lock.name
     *
     * Used only for tests.
     *
     * @param name lock name
     * @return optional lock row or empty
     */
    Optional<Lock> find(String name) {
        ResultSet resultSet = cqlSession.execute(QueryBuilder.selectFrom(table)
            .column(LOCK_NAME)
            .column(LOCK_UNTIL)
            .column(LOCKED_AT)
            .column(LOCKED_BY)
            .whereColumn(LOCK_NAME).isEqualTo(literal(name))
            .build());

        Row row = resultSet.one();
        if (row != null) {
            return Optional.of(new Lock(row.getInstant(LOCK_UNTIL), row.getInstant(LOCKED_AT), row.getString(LOCKED_BY)));
        } else {
            return Optional.empty();
        }
    }



    private boolean execute(SimpleStatement statement) {
        return cqlSession.execute(statement.setConsistencyLevel(consistencyLevel)).wasApplied();
    }
}
