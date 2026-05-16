#!/usr/bin/env bash
set -euo pipefail

STORE="${1:-/tmp/eloydb-m1-demo}"
rm -rf "$STORE"

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 26)}"
fi

mvn -q -DskipTests compile

java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" init
java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" put user:1 alice
java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" put user:2 bob
java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" scan "user:" "user;"
java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" snapshot
java -cp target/classes org.eloydb.kv.cli.EloydbKv "$STORE" verify
