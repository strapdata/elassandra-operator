CASSANDRA_CONF=/etc/cassandra
CASSANDRA_HOME=/usr/share/cassandra

# it seems that the JarHell detector in ES does not support wildcard in classpath
CLASSPATH="$CASSANDRA_CONF"
for jar in "$CASSANDRA_HOME"/lib/*.jar; do
 # Filter jamm to avoid JarHell issue
    if [ "$jar" != "$CASSANDRA_HOME/lib/jamm-0.3.0.jar" ]; then
       CLASSPATH="$CLASSPATH:$jar"
    fi
done