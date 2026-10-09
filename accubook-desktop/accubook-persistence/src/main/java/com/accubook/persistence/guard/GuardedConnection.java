package com.accubook.persistence.guard;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/**
 * Wraps a {@link Connection} so every mutating statement is authorised before it
 * reaches SQLite.
 *
 * <h2>Why the connection and not the repository</h2>
 * A unit of work hands the caller a real {@code Connection}. Any check placed in
 * a repository or service is therefore optional - the caller can simply not use
 * that repository. Wrapping the connection removes the choice: there is no route
 * to the database that does not pass through here.
 *
 * <p>This is the layer that matters, because the layer above it depends on
 * developer discipline and the reference application shows how that ends -
 * <strong>55 of 71 mutating routes omitted their permission check</strong>. It
 * also answers the measured JPMS result from the P4 review: a smuggled module on
 * {@code --module-path} can reach a class by reflection, but it still has to
 * write through a connection, and this one refuses.
 *
 * <h2>Implementation</h2>
 * A JDK dynamic proxy rather than a hand-written delegate. {@code Connection} has
 * over fifty methods and {@code Statement} more; writing them out invites the
 * one forgotten override that becomes the hole. The proxy forwards everything
 * and intercepts only statement creation and execution.
 *
 * <p>{@code createStatement} is intercepted at <em>execution</em> time, since the
 * SQL is not known when the statement is made. {@code prepareStatement} is
 * checked at preparation, which is earlier and cheaper, and again on execution
 * for the {@code execute(sql)} overloads.
 */
public final class GuardedConnection {

    private GuardedConnection() {
    }

    /**
     * @param connection    the real connection
     * @param authorization consulted for each mutating statement
     * @return a connection that refuses unauthorised writes
     */
    public static Connection wrap(Connection connection, WriteAuthorization authorization) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(authorization, "authorization");
        return (Connection) Proxy.newProxyInstance(
                GuardedConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(connection, authorization));
    }

    /**
     * Authorise one statement, refusing when the table cannot be identified.
     *
     * <p>Failing closed is deliberate. An unrecognised mutating statement is a
     * statement the guard does not understand, and letting it through would make
     * the guard advisory.
     */
    static void authorize(String sql, WriteAuthorization authorization) throws java.sql.SQLException {
        if (!GuardedStatements.isMutating(sql)) {
            return;
        }
        Optional<String> table = GuardedStatements.targetTable(sql);
        if (table.isEmpty()) {
            throw new UnauthorizedWriteException("<unparsed>",
                    "the guard could not identify the table this statement writes, "
                            + "and refuses rather than allow an unchecked mutation: " + sql);
        }
        Optional<String> refusal = authorization.refuse(table.get());
        if (refusal.isPresent()) {
            throw new UnauthorizedWriteException(table.get(), refusal.get());
        }
    }

    private record ConnectionHandler(Connection target, WriteAuthorization authorization)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                if ("prepareStatement".equals(name) || "prepareCall".equals(name)) {
                    String sql = (String) args[0];
                    authorize(sql, authorization);
                    Object statement = method.invoke(target, args);
                    return wrapStatement(statement, PreparedStatement.class);
                }
                if ("createStatement".equals(name)) {
                    Object statement = method.invoke(target, args);
                    return wrapStatement(statement, Statement.class);
                }
                if ("unwrap".equals(name)) {
                    // Refusing unwrap keeps the proxy from being unwrapped back
                    // to a bare connection, which would defeat the guard.
                    throw new UnauthorizedWriteException("<connection>",
                            "unwrapping a guarded connection is not permitted");
                }
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private Object wrapStatement(Object statement, Class<?> iface) {
            return Proxy.newProxyInstance(
                    GuardedConnection.class.getClassLoader(),
                    new Class<?>[]{iface},
                    new StatementHandler((Statement) statement, authorization));
        }
    }

    private record StatementHandler(Statement target, WriteAuthorization authorization)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                // execute(sql), executeUpdate(sql), executeQuery(sql), addBatch(sql)
                boolean carriesSql = args != null && args.length > 0 && args[0] instanceof String
                        && (name.startsWith("execute") || "addBatch".equals(name));
                if (carriesSql) {
                    authorize((String) args[0], authorization);
                }
                if ("unwrap".equals(name)) {
                    throw new UnauthorizedWriteException("<statement>",
                            "unwrapping a guarded statement is not permitted");
                }
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
