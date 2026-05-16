# KV Torture Runbook

## Smoke

```bash
mvn test
```

## Manual CLI

After packaging, run:

```bash
java -cp target/classes org.eloydb.kv.cli.EloydbKv /tmp/eloydb init
java -cp target/classes org.eloydb.kv.cli.EloydbKv /tmp/eloydb put hello world
java -cp target/classes org.eloydb.kv.cli.EloydbKv /tmp/eloydb get hello
java -cp target/classes org.eloydb.kv.cli.EloydbKv /tmp/eloydb scan h i
java -cp target/classes org.eloydb.kv.cli.EloydbKv /tmp/eloydb verify
```

## Crash-Recovery Check

Start a loop that writes keys, kill it, then reopen the same directory and run `verify`. Recovery replays only committed WAL transactions and truncates a torn tail.

The full 24-hour randomized `kill -9` soak from `docs/spec/MILESTONE_1.md` is not automated yet.
